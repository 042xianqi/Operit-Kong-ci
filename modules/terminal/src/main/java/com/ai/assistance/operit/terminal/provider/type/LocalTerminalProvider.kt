package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.GuestRootfsPrep
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.PackageSourceSpec
import com.ai.assistance.operit.terminal.data.ProotCommand
import com.ai.assistance.operit.terminal.data.ProotSpec
import com.ai.assistance.operit.terminal.data.TerminalRuntime
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.runtime.NativeTerminal
import com.ai.assistance.operit.terminal.utils.AlpineRuntime
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.TerminalProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地终端提供者
 *
 * 使用 proot + Ubuntu 环境提供本地 Linux 终端。
 * 可见终端继续走 PTY，不可见执行走后台复用 shell。
 */
class LocalTerminalProvider(
    private val context: Context
) : TerminalProvider {

    private data class HiddenExecShell(
        val key: String,
        val process: Process,
        val writer: java.io.BufferedWriter,
        val outputChannel: Channel<String>,
        val readJob: kotlinx.coroutines.Job,
        /** rootfs 内的目标 shell（Alpine 为 `/bin/sh`），命令包络用它而不是写死 bash。 */
        val shellCommand: String,
        val mutex: Mutex = Mutex()
    )

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    private val sourceManager = SourceManager(context)
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val hiddenExecShells = ConcurrentHashMap<String, HiddenExecShell>()
    private val hiddenExecShellMutex = Mutex()
    private val hiddenExecScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fileSystemProvider = LocalFileSystemProvider(context)

    companion object {
        private const val TAG = "LocalTerminalProvider"
        private const val READY_MARKER = "TERMINAL_READY"
        private const val BEGIN_MARKER_PREFIX = "__OPERIT_HIDDEN_BEGIN__:"
        private const val END_MARKER_PREFIX = "__OPERIT_HIDDEN_END__:"
        private const val PID_MARKER_PREFIX = "__OPERIT_HIDDEN_PID__:"
        private const val HIDDEN_EXEC_CANCEL_SETTLE_TIMEOUT_MS = 3_000L
    }

    override suspend fun isConnected(): Boolean {
        return true
    }

    override suspend fun connect(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        activeSessions.keys.toList().forEach { sessionId ->
            closeSession(sessionId)
        }
        hiddenExecShells.keys.toList().forEach { key ->
            closeHiddenExecShell(key)
        }
        hiddenExecScope.cancel()
    }

    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> =
        startLocalSession(sessionId, automation = false)

    suspend fun startAutomationSession(sessionId: String): Result<Pair<TerminalSession, Pty>> =
        startLocalSession(sessionId, automation = true)

    private suspend fun startLocalSession(sessionId: String, automation: Boolean): Result<Pair<TerminalSession, Pty>> {
        return withContext(Dispatchers.IO) {
            try {
                val mode = if (automation) LaunchMode.AUTOMATION_VISIBLE else LaunchMode.VISIBLE
                val pty =
                    if (prefs.getBoolean("chroot_enabled", false)) {
                        // chroot 模式需要宿主 root（su + mount），proot argv 表达不了；
                        // 保留旧的脚本路径，且脚本里已不再改写 /etc/profile、不再跑探针。
                        logTerminalInit("chroot_enabled=1: using the legacy shell launcher")
                        Pty.start(
                            arrayOf(File(binDir, "bash").absolutePath, "-c", "source \$HOME/common.sh && start_shell"),
                            buildEnvironment(),
                            filesDir,
                        )
                    } else {
                        // 直接执行 proot（不经 shell）：argv/环境唯一来源是 liboperit_terminal.so。
                        val launch = buildProotCommand(mode)
                        Log.d(TAG, "Starting local terminal session with proot argv: ${launch.describeArgv()}")
                        Pty.start(launch.argv.toTypedArray(), launch.environment, launch.workingDirectory)
                    }

                val session = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )

                activeSessions[sessionId] = session
                Result.success(Pair(session, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local terminal session", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun closeSession(sessionId: String) {
        activeSessions.remove(sessionId)?.let { session ->
            destroyProcessTree(session.process)
            Log.d(TAG, "Closed local terminal session: $sessionId")
        }
    }

    override suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long
    ): HiddenExecResult {
        val shell =
            try {
                getOrCreateHiddenExecShell(executorKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare hidden exec shell: $executorKey", e)
                return HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_START_FAILED,
                    error = e.message ?: "Failed to prepare hidden exec shell"
                )
            }

        return try {
            val result = withTimeout(timeoutMs) {
                shell.mutex.withLock {
                    val token = UUID.randomUUID().toString()
                    Log.d(TAG, "Hidden exec command started: key=$executorKey token=$token timeoutMs=$timeoutMs command=${command.lineSequence().firstOrNull().orEmpty().take(200)}")
                    val wrappedCommand = buildHiddenExecEnvelope(shell.shellCommand, command, token)
                    withContext(Dispatchers.IO) {
                        shell.writer.write(wrappedCommand)
                        shell.writer.flush()
                    }
                    collectHiddenExecResult(shell, token, timeoutMs)
                }
            }
            if (!result.isOk) {
                closeHiddenExecShell(shell)
            }
            result
        } catch (e: TimeoutCancellationException) {
            closeHiddenExecShell(shell)
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Hidden exec command timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            closeHiddenExecShell(shell)
            Log.e(TAG, "Failed to execute hidden command in shell: $executorKey", e)
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.EXECUTION_ERROR,
                error = e.message ?: "Failed to execute hidden command"
            )
        }
    }

    override fun getFileSystemProvider(): FileSystemProvider {
        return fileSystemProvider
    }

    override suspend fun getWorkingDirectory(): String {
        return filesDir.absolutePath
    }

    override fun getEnvironment(): Map<String, String> {
        return buildEnvironment()
    }

    private suspend fun getOrCreateHiddenExecShell(executorKey: String): HiddenExecShell {
        hiddenExecShells[executorKey]?.let { existing ->
            if (existing.process.isAlive) {
                return existing
            }
            closeHiddenExecShell(executorKey)
        }

        return hiddenExecShellMutex.withLock {
            hiddenExecShells[executorKey]?.let { existing ->
                if (existing.process.isAlive) {
                    return@withLock existing
                }
                closeHiddenExecShell(executorKey)
            }

            val created = createHiddenExecShell(executorKey)
            hiddenExecShells[executorKey] = created
            created
        }
    }

    private suspend fun createHiddenExecShell(executorKey: String): HiddenExecShell {
        val spec = resolveSessionSpec()
        val launch = buildProotCommand(spec, LaunchMode.HIDDEN_EXEC)
        val process =
            withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(*launch.argv.toTypedArray())
                builder.directory(launch.workingDirectory)
                builder.redirectErrorStream(true)
                builder.environment().apply {
                    clear()
                    putAll(launch.environment)
                }
                builder.start()
            }
        val outputChannel = Channel<String>(Channel.UNLIMITED)
        val readJob =
            hiddenExecScope.launch {
                val input = process.inputStream
                val buffer = ByteArray(4096)
                try {
                    while (isActive) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count > 0) {
                            outputChannel.send(String(buffer, 0, count, Charsets.UTF_8))
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Hidden exec reader failed for $executorKey", e)
                    }
                } finally {
                    outputChannel.close()
                }
            }

        val shell =
            HiddenExecShell(
                key = executorKey,
                process = process,
                writer = process.outputStream.bufferedWriter(Charsets.UTF_8),
                outputChannel = outputChannel,
                readJob = readJob,
                shellCommand = hiddenExecShellCommand(spec)
            )

        val readyResult = awaitHiddenExecReady(shell)
        if (!readyResult.isOk) {
            closeHiddenExecShell(shell)
            throw IllegalStateException(
                readyResult.error.ifBlank { "Hidden exec shell did not become ready" }
            )
        }

        Log.d(TAG, "Created hidden exec shell: $executorKey")
        return shell
    }

    private suspend fun awaitHiddenExecReady(shell: HiddenExecShell): HiddenExecResult {
        return try {
            val rawOutput: String =
                withTimeout(30000L) {
                    val builder = StringBuilder()
                    while (true) {
                        val chunk =
                            shell.outputChannel.receiveCatching().getOrNull()
                                ?: break
                        logHiddenExecChunk(shell.key, "ready", chunk)
                        builder.append(chunk)
                        if (builder.indexOf(READY_MARKER) >= 0) {
                            break
                        }
                    }
                    builder.toString()
                }

            if (rawOutput.contains(READY_MARKER)) {
                HiddenExecResult(output = "", exitCode = 0)
            } else if (!shell.process.isAlive) {
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.PROCESS_EXITED,
                    error = "Hidden exec shell exited before ready",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )
            } else {
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_NOT_READY,
                    error = "Hidden exec shell did not emit ready marker",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )
            }
        } catch (e: TimeoutCancellationException) {
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Timed out while waiting for hidden exec shell to become ready"
            )
        }
    }

    private suspend fun collectHiddenExecResult(
        shell: HiddenExecShell,
        token: String,
        timeoutMs: Long
    ): HiddenExecResult {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val deadline = System.currentTimeMillis() + timeoutMs
        val builder = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            val chunk =
                withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) {
                    shell.outputChannel.receiveCatching().getOrNull()
                } ?: break
            logHiddenExecChunk(shell.key, token, chunk)
            builder.append(chunk)
            if (builder.indexOf(endMarkerPrefix) >= 0) {
                break
            }
        }

        val rawOutput = builder.toString()
        if (rawOutput.indexOf(endMarkerPrefix) >= 0) {
            return parseHiddenExecOutput(rawOutput, token)
        }

        if (!shell.process.isAlive) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.PROCESS_EXITED,
                error = "Hidden exec shell exited while executing command",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        cancelHiddenExecCommand(rawOutput, token)
        return HiddenExecResult(
            output = extractHiddenExecOutput(rawOutput, token),
            exitCode = -1,
            state = HiddenExecResult.State.TIMEOUT,
            error = "Hidden exec command timed out after ${timeoutMs}ms",
            rawOutputPreview = rawOutput.takeLast(1200)
        )
    }

    private fun parseHiddenExecOutput(
        rawOutput: String,
        token: String
    ): HiddenExecResult {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val beginIndex = rawOutput.indexOf(beginMarker)
        if (beginIndex < 0) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.MISSING_BEGIN_MARKER,
                error = "Hidden exec output missing begin marker",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        val payloadStart = beginIndex + beginMarker.length
        val endIndex = rawOutput.indexOf(endMarkerPrefix, payloadStart)
        if (endIndex < 0) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.MISSING_END_MARKER,
                error = "Hidden exec output missing end marker",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        val commandOutput = extractHiddenExecOutput(rawOutput, token)

        val exitCodeText =
            rawOutput
                .substring(endIndex + endMarkerPrefix.length)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return HiddenExecResult(
                    output = commandOutput,
                    exitCode = -1,
                    state = HiddenExecResult.State.INVALID_EXIT_CODE,
                    error = "Hidden exec output missing exit code",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )

        val exitCode =
            exitCodeText.toIntOrNull()
                ?: return HiddenExecResult(
                    output = commandOutput,
                    exitCode = -1,
                    state = HiddenExecResult.State.INVALID_EXIT_CODE,
                    error = "Invalid hidden exec exit code: $exitCodeText",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )

        return HiddenExecResult(
            output = commandOutput,
            exitCode = exitCode
        )
    }

    private suspend fun collectHiddenExecTimeoutOutput(
        shell: HiddenExecShell,
        token: String,
        initialRawOutput: String
    ): String {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val builder = StringBuilder(initialRawOutput)
        withTimeoutOrNull(HIDDEN_EXEC_CANCEL_SETTLE_TIMEOUT_MS) {
            while (builder.indexOf(endMarkerPrefix) < 0) {
                val chunk =
                    shell.outputChannel.receiveCatching().getOrNull()
                        ?: break
                builder.append(chunk)
            }
        }
        return builder.toString()
    }

    private fun cancelHiddenExecCommand(rawOutput: String, token: String) {
        val pid = extractHiddenExecPid(rawOutput, token) ?: return
        runCatching {
            Os.kill((-pid).toInt(), OsConstants.SIGTERM)
        }.onFailure { error ->
            Log.w(TAG, "Failed to send SIGTERM to hidden exec process group $pid", error)
        }
        Thread.sleep(100)
        runCatching {
            Os.kill((-pid).toInt(), OsConstants.SIGKILL)
        }.onFailure { error ->
            Log.w(TAG, "Failed to send SIGKILL to hidden exec process group $pid", error)
        }
    }

    private fun extractHiddenExecPid(rawOutput: String, token: String): Long? {
        val pidMarkerPrefix = "$PID_MARKER_PREFIX$token:"
        val markerIndex = rawOutput.indexOf(pidMarkerPrefix)
        if (markerIndex < 0) {
            return null
        }
        val pidText =
            rawOutput
                .substring(markerIndex + pidMarkerPrefix.length)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        return pidText?.toLongOrNull()
    }

    private fun extractHiddenExecOutput(rawOutput: String, token: String): String {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val beginIndex = rawOutput.indexOf(beginMarker)
        if (beginIndex < 0) {
            return ""
        }
        val payloadStart = beginIndex + beginMarker.length
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val endIndex = rawOutput.indexOf(endMarkerPrefix, payloadStart).let { index ->
            if (index >= 0) index else rawOutput.length
        }
        return rawOutput
            .substring(payloadStart, endIndex)
            .lineSequence()
            .filterNot { it.startsWith("$PID_MARKER_PREFIX$token:") }
            .joinToString("\n")
            .trimStart('\n', '\r')
            .trimEnd('\n', '\r')
    }

    private suspend fun closeHiddenExecShell(executorKey: String) {
        hiddenExecShells[executorKey]?.let { shell -> closeHiddenExecShell(shell) }
    }

    private suspend fun closeHiddenExecShell(shell: HiddenExecShell) {
        // 按实例移除缓存，避免误删同键的新会话，同时回收旧会话的整个进程组。
        hiddenExecShells.remove(shell.key, shell)
        withContext(Dispatchers.IO) {
            runCatching { shell.writer.close() }
            destroyProcessTree(shell.process)
            runCatching { shell.readJob.cancel() }
            runCatching { shell.outputChannel.close() }
        }
        Log.d(TAG, "Closed hidden exec shell: ${shell.key}")
    }

    private fun destroyProcessTree(process: Process) {
        val pid = pidOf(process)
        if (pid != null && pid > 0) {
            runCatching { Os.kill((-pid).toInt(), OsConstants.SIGTERM) }
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
            }
            runCatching { Os.kill((-pid).toInt(), OsConstants.SIGKILL) }
        }
        runCatching { process.destroy() }
        // 保留超时回收的强制终止行为，关闭流程不再无限等待进程退出。
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
        }
    }

    private fun pidOf(process: Process): Long? {
        return runCatching {
            val method = Process::class.java.getMethod("pid")
            when (val value = method.invoke(process)) {
                is Long -> value
                is Int -> value.toLong()
                else -> null
            }
        }.getOrNull() ?: runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            when (val value = field.get(process)) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }
        }.getOrNull()
    }

    /**
     * 一次本地会话的发行版信息。
     *
     * 数据来源与 `TerminalManager.generateStartScript()` 相同（注册表 → 默认实例 →
     * `TerminalRuntime.forDistribution`），但这里只在**宿主侧**使用：
     * 不再把 rootfs、shell 路径写进 shell 脚本让 shell 自己再拼一遍 proot 参数。
     */
    private data class LocalSessionSpec(
        val rootfs: File,
        val runtime: TerminalRuntime,
        val sessionInit: String,
    ) {
        val isAlpine: Boolean get() = runtime.packageSource == PackageSourceSpec.AlpineRepositories
    }

    private enum class LaunchMode(val automation: Boolean) {
        /** 用户可见的交互终端。 */
        VISIBLE(false),
        /** 工具调用的自动化终端（可见，但关闭分页与交互提示）。 */
        AUTOMATION_VISIBLE(true),
        /** 后台隐藏执行 shell（不可见，网络/命令复用）。 */
        HIDDEN_EXEC(true),
    }

    private fun resolveSessionSpec(): LocalSessionSpec {
        val settings = runCatching { TerminalProfileStore(context).load() }.getOrNull()
        val profile = settings?.defaultProfile() ?: settings?.profiles?.firstOrNull()
        val rootfs =
            profile?.rootfsPath?.let { File(it) }
                ?: File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/${AlpineRuntime.PROFILE_ID}")
        val runtime = TerminalRuntime.forDistribution(profile?.distributionId)
        val initialization = runCatching { TerminalManager.localSessionInitFor(context) }
            .onFailure { Log.w(TAG, "Cannot build the local session init script", it) }
            .getOrDefault(":")
        return LocalSessionSpec(rootfs = rootfs, runtime = runtime, sessionInit = initialization)
    }

    /**
     * 拼出一次 proot 启动。
     *
     * 探针与真正启动曾各自维护一份 argv（shell 里 `run_proot_probe` 与 `exec_proot_binary`），
     * 现在只有 Rust 侧一份实现；可见终端与隐藏执行共用同一份 proot 前缀，只有 guest 命令不同。
     */
    private fun buildProotCommand(mode: LaunchMode): ProotCommand =
        buildProotCommand(resolveSessionSpec(), mode)

    private fun buildProotCommand(spec: LocalSessionSpec, mode: LaunchMode): ProotCommand {
        runLegacyRootfsInstallIfNeeded(spec)
        val promptEnv = prepareGuestRootfs(spec)
        val command =
            NativeTerminal.build(
                ProotSpec(
                    proot = File(binDir, "proot"),
                    loader = File(binDir, "loader"),
                    tmpDir = File(filesDir, "tmp").apply { mkdirs() },
                    rootfs = spec.rootfs,
                    processWorkingDirectory = filesDir,
                    binds = buildBinds(),
                    guestCommand = buildGuestCommand(spec, mode),
                    guestEnvironment = buildGuestEnvironment(spec, mode, promptEnv),
                )
            )
        logTerminalInit(
            "proot launch (${mode.name}) runtime=${NativeTerminal.version} " +
                "distro=${spec.runtime.label} rootfs=${spec.rootfs.absolutePath}\n" +
                command.logLines()
        )
        return command
    }

    private fun buildBinds(): List<PRootBindMount> {
        val tmpDir = File(filesDir, "tmp")
        val binds =
            PRootMountMapping.buildRuntimeBindMounts(
                homeDir = filesDir.absolutePath,
                appDataDir = context.applicationInfo.dataDir,
                packageName = context.packageName,
                chrootEnabled = false,
            ).toMutableList()
        binds += PRootBindMount(tmpDir.absolutePath)
        if (prefs.getBoolean("shared_tmp_enabled", true)) {
            binds += PRootBindMount(tmpDir.absolutePath, "/dev/shm")
        }
        return binds
    }

    /**
     * guest 命令：先用一条 `-c` 完成初始化并打印宿主等待的两个标记，再 `exec` 交互 shell。
     *
     * 与 Operit2 一致的地方：proot 后面直接跟 rootfs 内的 shell（`--login -i` / `-i`），
     * 没有任何「source common.sh」的中间层。
     * 不同处：Kong 的 `OutputProcessor` 以 `LOGIN_SUCCESSFUL` + `TERMINAL_READY` 判定就绪、
     * 以 OSC 777 获取 cwd，所以标记仍由这条 `-c` 输出。
     * 初始化脚本失败**不再**杀掉会话（原来是 `|| exit $?`，Alpine 没有 bash 时必死）。
     */
    private fun buildGuestCommand(spec: LocalSessionSpec, mode: LaunchMode): List<String> {
        val shell = spec.runtime.shellPath
        val shellArgs =
            when {
                mode == LaunchMode.HIDDEN_EXEC ->
                    if (spec.isAlpine) emptyList() else listOf("--noprofile", "--norc")
                spec.isAlpine -> listOf("-i")
                else -> listOf("--login", "-i")
            }
        val bootstrap =
            buildString {
                append("if [ -n \"\$OPERIT_LOCAL_SESSION_INIT\" ]; then ")
                append("eval \"\$OPERIT_LOCAL_SESSION_INIT\" >/dev/null 2>&1 || ")
                append("echo 'operit: local session init failed; continuing' >&2; fi\n")
                append("echo LOGIN_SUCCESSFUL\n")
                append("echo TERMINAL_READY\n")
                append("umask 0002\n")
                append("exec ").append(shell)
                shellArgs.forEach { append(' ').append(it) }
                append('\n')
            }
        return listOf(shell, "-c", bootstrap)
    }

    /**
     * guest 环境变量。
     *
     * 提示符相关（`ENV` / `PROMPT_COMMAND` / `PS1`）不再硬编码：由 native 的
     * `prompt.rs` 针对发行版决定并随 rootfs 准备结果返回。这里只补宿主自己的变量。
     */
    private fun buildGuestEnvironment(
        spec: LocalSessionSpec,
        mode: LaunchMode,
        promptEnv: Map<String, String>,
    ): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        environment["TMPDIR"] = File(filesDir, "tmp").absolutePath
        environment["OPERIT_LOCAL_SESSION_INIT"] = spec.sessionInit
        environment.putAll(promptEnv)
        if (mode.automation) {
            environment["OPERIT_AUTOMATION"] = "1"
            environment["PAGER"] = "cat"
            environment["GIT_PAGER"] = "cat"
            environment["GIT_TERMINAL_PROMPT"] = "0"
        }
        return environment
    }

    /**
     * 可下载发行版（Ubuntu 等）的 rootfs 解压仍然由 `common.sh` 的 `install_ubuntu` 完成：
     * 归档是 tar.xz，Kotlin 侧的 Alpine 安装器只认自有格式。
     *
     * 只在「非 Alpine 且 rootfs 尚未就绪」时执行一次，而且这条命令里**没有 proot**
     * ——它只是宿主侧的 tar 解压/落盘，不参与终端启动。
     */
    private fun runLegacyRootfsInstallIfNeeded(spec: LocalSessionSpec) {
        if (spec.isAlpine || spec.rootfs.isDirectory) return
        val commonSh = File(filesDir, "common.sh")
        if (!commonSh.isFile) {
            logTerminalInit("legacy rootfs install skipped: ${commonSh.absolutePath} is missing")
            return
        }
        val bash = File(binDir, "bash")
        if (!bash.isFile) {
            logTerminalInit("legacy rootfs install skipped: ${bash.absolutePath} is missing")
            return
        }
        logTerminalInit("installing rootfs for distro=${spec.runtime.label} into ${spec.rootfs.absolutePath}")
        val output =
            runCatching {
                val builder = ProcessBuilder(bash.absolutePath, "-c", "source \$HOME/common.sh && install_ubuntu")
                builder.directory(filesDir)
                builder.redirectErrorStream(true)
                builder.environment().apply {
                    clear()
                    putAll(buildEnvironment())
                }
                val process = builder.start()
                val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
                if (!finished) process.destroyForcibly()
                "exit=${if (finished) process.exitValue() else "<timeout>"}\n$text"
            }
        output
            .onFailure { logTerminalInit("legacy rootfs install failed: $it\n${it.stackTraceToString()}") }
            .onSuccess { logTerminalInit("legacy rootfs install output:\n${it.takeLast(4000)}") }
    }

    /**
     * 宿主侧准备 rootfs：目录、包源、提示符文件、Android gid、旧 profile 修复。
     *
     * 实现全部在 Rust（`guest.rs` / `repair.rs` / `prompt.rs`），这里只转发并记录动作。
     * 返回值是该发行版需要的提示符环境变量（alpine → `ENV`，其它 → `PROMPT_COMMAND`/`PS1`），
     * 由 native 决定，Kotlin 不再硬编码。
     */
    private fun prepareGuestRootfs(spec: LocalSessionSpec): Map<String, String> {
        if (!spec.rootfs.isDirectory) {
            logTerminalInit("rootfs missing for prep: ${spec.rootfs.absolutePath}")
            return emptyMap()
        }
        // 结构判据保留（不看 exec 位）：真正能不能进由 proot 实跑决定，失败时输出进 terminal-init.log。
        if (spec.isAlpine && !AlpineRuntime.isInstalled(spec.rootfs)) {
            logTerminalInit(
                "Alpine rootfs structure incomplete at ${spec.rootfs.absolutePath} " +
                    "(need bin/busybox, bin/sh, etc/); proot will report the real error"
            )
        }
        val binds = buildBinds()
        return runCatching {
            val prepared =
                GuestRootfsPrep.prepare(
                    rootfs = spec.rootfs,
                    directories = guestDirectories(binds),
                    files = guestFiles(spec),
                    groupIds = androidGroupIds(),
                    distro = if (spec.isAlpine) "alpine" else "debian",
                    repairLegacy = true,
                )
            if (prepared.actions.isNotEmpty()) logTerminalInit("guest rootfs prep: " + prepared.actions.joinToString("; "))
            prepared.environment
        }.onFailure { logTerminalInit("guest rootfs prep failed: $it\n${it.stackTraceToString()}") }
            .getOrDefault(emptyMap())
    }

    private fun guestDirectories(binds: List<PRootBindMount>): List<String> {
        val directories =
            mutableSetOf("root", "tmp", "proc", "sys", "dev/pts", "storage", "sdcard", "data/local/tmp", "data/data")
        binds.forEach { mount ->
            val source = File(mount.sourcePath)
            if (mount.targetPath.isNotEmpty() && source.isDirectory) directories += mount.targetPath
        }
        return directories.toList()
    }

    private fun guestFiles(spec: LocalSessionSpec): List<GuestRootfsPrep.GuestFile> {
        val writes = mutableListOf<GuestRootfsPrep.GuestFile>()
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val aptComment = "# From Operit Settings - ${aptSource.name}"

        if (spec.isAlpine) {
            writes +=
                GuestRootfsPrep.GuestFile(
                    "etc/apk/repositories",
                    PackageSourceSpec.AlpineRepositories.render(aptSource.url, "latest-stable", aptComment),
                )
            // /etc/operit/prompt.sh 由 native（prompt.rs）写出，Kotlin 不再生成。
        } else if (spec.runtime.aptSuite.isNotEmpty()) {
            // suite 来自发行版（noble / trixie）；写错 suite 会让 apt update 全 404。
            writes +=
                GuestRootfsPrep.GuestFile(
                    "etc/apt/sources.list",
                    PackageSourceSpec.AptSourcesList.render(aptSource.url, spec.runtime.aptSuite, aptComment),
                )
        }
        writes += GuestRootfsPrep.GuestFile("root/.config/pip/pip.conf", "[global]\nindex-url = ${pipSource.url}\n")
        writes += GuestRootfsPrep.GuestFile("root/.config/uv/uv.toml", "index-url = \"${pipSource.url}\"\n")
        writes += GuestRootfsPrep.GuestFile("root/.npmrc", "registry=${npmSource.url}\n")
        return writes
    }

    /** `/proc/self/status` 的 `Groups:` 行就是 Android 给本进程的补充组，等价于旧脚本的 `id -G`。 */
    private fun androidGroupIds(): List<Int> =
        runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Groups:") }
                ?.removePrefix("Groups:")
                ?.trim()
                ?.split(' ')
                ?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** 隐藏执行脚本里的目标 shell：Alpine 只有 ash，调用 bash 会直接失败。 */
    private fun hiddenExecShellCommand(spec: LocalSessionSpec): String =
        if (spec.isAlpine) spec.runtime.shellPath else "${spec.runtime.shellPath} --noprofile --norc"

    private fun logTerminalInit(message: String) {
        Log.d(TAG, message)
        runCatching {
            val logsDir = File(filesDir, "logs").apply { mkdirs() }
            val stamp =
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
            File(logsDir, "terminal-init.log").appendText("$stamp [$TAG] $message\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Cannot append terminal-init.log", it) }
    }

    private fun buildHiddenExecEnvelope(shellCommand: String, command: String, token: String): String {
        val normalizedCommand = command.replace("\r\n", "\n").replace("\r", "\n")
        val tokenSuffix = token.replace("-", "")
        val heredocMarker = "__OPERIT_HIDDEN_CMD_${tokenSuffix}__"
        return buildString {
            append("printf '%s\\n' '$BEGIN_MARKER_PREFIX$token'\n")
            append("__operit_hidden_script=\"\${TMPDIR:-/tmp}/operit_hidden_$tokenSuffix.sh\"\n")
            append("cat >\"\$__operit_hidden_script\" <<'$heredocMarker'\n")
            append(normalizedCommand)
            if (!normalizedCommand.endsWith("\n")) {
                append('\n')
            }
            append("$heredocMarker\n")
            append("setsid $shellCommand \"\$__operit_hidden_script\" </dev/null &\n")
            append("__operit_hidden_pid=\$!\n")
            append("printf '%s:%s\\n' '$PID_MARKER_PREFIX$token' \"\$__operit_hidden_pid\"\n")
            append("wait \"\$__operit_hidden_pid\"\n")
            append("__operit_hidden_rc=\$?\n")
            append("rm -f \"\$__operit_hidden_script\"\n")
            append("printf '%s:%s\\n' '$END_MARKER_PREFIX$token' \"\$__operit_hidden_rc\"\n")
        }
    }

    private fun logHiddenExecChunk(
        executorKey: String,
        token: String,
        chunk: String
    ) {
        chunk.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                line.chunked(3000).forEach { part ->
                    Log.d(TAG, "Hidden exec output [$executorKey][$token]: $part")
                }
            }
    }

    /**
     * 宿主侧环境快照（`TerminalProvider.getEnvironment()`）。
     *
     * proot 自己的启动环境由 `ProotCommand` 生成并显式传给 `execve`，
     * 不依赖这张表；这里保留给查询方，并显式保证 `PROOT_NO_SECCOMP` 不被带出。
     */
    private fun buildEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
        env["HOME"] = filesDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["TERMUX_PREFIX"] = usrDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
        env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        env["TMPDIR"] = File(filesDir, "tmp").absolutePath
        env["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env.remove(ProotCommand.PROOT_NO_SECCOMP)
        return env
    }
}

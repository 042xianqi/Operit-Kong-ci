package com.ai.assistance.operit.terminal

import java.io.FileOutputStream

import java.util.zip.ZipFile

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ai.assistance.operit.terminal.command.CommandCancellationOutcome
import com.ai.assistance.operit.terminal.command.CommandCancellationRecovery
import com.ai.assistance.operit.terminal.process.awaitTerminalExit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.ai.assistance.operit.terminal.maintenance.UbuntuRemovalJournal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import java.util.UUID
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import com.ai.assistance.operit.terminal.utils.SSHDServerManager
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.operit.terminal.provider.type.SSHTerminalProvider
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.TerminalProfile
import com.ai.assistance.operit.terminal.data.TerminalProfileSettings
import com.ai.assistance.operit.terminal.data.TerminalRuntime
import com.ai.assistance.operit.terminal.data.PackageSourceSpec
import com.ai.assistance.operit.terminal.utils.TerminalDistributions
import com.ai.assistance.operit.terminal.utils.TerminalRootfsInstaller
import com.ai.assistance.operit.terminal.utils.AlpineRuntime
import com.ai.assistance.operit.terminal.utils.TerminalProfileStore
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

class TerminalManager private constructor(
    private val context: Context
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val commandEventChannel = Channel<CommandExecutionEvent>(Channel.UNLIMITED)
    private data class PendingInput(
        val text: String,
        val commandId: String? = null,
        val recovery: CommandCancellationRecovery? = null,
        val recoveryProbe: Boolean = false,
        val terminalResponse: Boolean = false,
    )
    private val sessionInputs = ConcurrentHashMap<String, Channel<PendingInput>>()
    private val inputJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val sessionPtys = ConcurrentHashMap<String, Pty>()
    private val envInitMutex = Mutex()
    private val sessionStartMutex = Mutex()
    private var isEnvInitialized = false

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val closingSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionProcesses = ConcurrentHashMap<String, Process>()
    private val sessionStartups = ConcurrentHashMap<String, CompletableDeferred<Process?>>()
    private val processReapJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * 会话启动初期的原始输出（proot / 登录 shell 的 stdout+stderr 都从 PTY 出来）。
     *
     * 存在的意义：会话在「Fixing permissions」之后静默死亡时，宿主看不到任何东西，
     * 因为 `exec` 成功后失败发生在被替换掉的进程里。这里把前 8 KB 原样落进
     * `logs/terminal-init.log`，下一次失败就有真实命令行与真实报错可看。
     */
    private val startupTranscripts = ConcurrentHashMap<String, StringBuilder>()
    
    // SharedPreferences for reading settings
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    // 核心组件
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            // Serialize events before exposing them to collectors.
            if (!commandEventChannel.trySend(event).isSuccess) {
                Log.w(TAG, "Dropping terminal command event for session ${event.sessionId}")
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        },
        onShellPrompt = { sessionId -> enqueueCancellationProbe(sessionId) },
        onTerminalResponse = { sessionId, response -> enqueueTerminalResponse(sessionId, response) },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch {
                processNextQueuedCommand(sessionId)
            }
        }
    )
    private val sourceManager = SourceManager(context)
    private val sshConfigManager = SSHConfigManager(context)
    private val sshdServerManager = SSHDServerManager.getInstance(context)
    private val profileStore = TerminalProfileStore(context)

    /**
     * 终端实例注册表。首次访问时从磁盘读；读不到（首启/损坏）就用默认值。
     *
     * **默认实例是 Alpine**：它随 APK 内置，首启即可用，不需要先下载。
     * Ubuntu 等可下载发行版通过 [upsertProfile] 加入后即可切换。
     */
    @Volatile
    private var profileSettings: TerminalProfileSettings? = null
    private val profileMutex = Mutex()

    private val _bundledEnvState = MutableStateFlow<BundledEnvState>(BundledEnvState.Idle)

    /**
     * 内置 Alpine 的安装状态，供设置页与终端入口观察。
     *
     * 之所以要有它：解压是几十秒的异步任务，UI 必须能区分「还没开始」
     * 「正在解压」「已就绪」「失败（附原因）」，否则用户只能看到
     * `rootfs missing` 这种没有信息量的结果。
     */
    val bundledEnvState: StateFlow<BundledEnvState> = _bundledEnvState.asStateFlow()

    /** 最近一次内置 Alpine 安装失败的完整堆栈；成功时为 null。 */
    @Volatile
    var lastAlpineInstallError: String? = null
        private set

    // 单例的 TerminalProvider
    private var terminalProvider: TerminalProvider? = null
    private val providerMutex = Mutex()

    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state

    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    // ------------------------------------------------------------------ 终端实例（profile）

    /**
     * 读取当前注册表；必要时初始化。
     *
     * 首次启动（磁盘上没有文件）时写入默认注册表：**只有 Alpine，且是默认实例**。
     * 老的 shell 脚本装的 Ubuntu 不会自动出现在注册表里：设置页
     * （`TerminalDistributionSettingsScreen.adoptInstalledDistributions`）会把
     * `installed-rootfs/<dir>` 已就位但没有 profile 的实例补登记进来。
     */
    suspend fun loadProfileSettings(): TerminalProfileSettings = profileMutex.withLock {
        loadProfileSettingsLocked()
    }

    /**
     * [loadProfileSettings] 的无锁版本。
     *
     * 调用方**必须已持有** [profileMutex]，否则会有数据竞争。
     * 存在的理由是 Kotlin 的 [Mutex] 不可重入：如果持锁方法内部再调
     * [loadProfileSettings]，`withLock` 会永久阻塞（自死锁）。
     * 所以凡是已在 `profileMutex.withLock { }` 内部的路径，都必须走这个版本。
     */
    private suspend fun loadProfileSettingsLocked(): TerminalProfileSettings {
        profileSettings?.let { return it }
        val fromDisk = profileStore.load()
        val resolved =
            fromDisk ?: run {
                val defaults = defaultProfileSettings()
                runCatching { profileStore.save(defaults) }
                defaults
            }
        profileSettings = resolved
        return resolved
    }

    /**
     * 默认注册表：Alpine 为主实例。
     *
     * 与 [TerminalProfileStore.defaultSettings] 的差异：那个是「旧 Ubuntu 兼容」版本，
     * 这个才是 Kong 现在的默认——Alpine 内置、立即可用。
     */
    private fun defaultProfileSettings(): TerminalProfileSettings {
        val alpineRootfs = alpineRootfsDir()
        return TerminalProfileSettings(
            schemaVersion = TerminalProfileSettings.CURRENT_SCHEMA_VERSION,
            revision = 1,
            defaultProfileId = AlpineRuntime.PROFILE_ID,
            profiles =
                listOf(
                    TerminalProfile(
                        id = AlpineRuntime.PROFILE_ID,
                        name = AlpineRuntime.DISPLAY_NAME,
                        distributionId = AlpineRuntime.PROFILE_ID,
                        rootfsPath = alpineRootfs.absolutePath,
                        installed = AlpineRuntime.isInstalled(alpineRootfs),
                        createdAtMillis = 0L,
                    )
                ),
        )
    }

    /** 内置 Alpine 的安装位置，与 Ubuntu 等实例平级。 */
    private fun alpineRootfsDir(): File =
        File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/${AlpineRuntime.PROFILE_ID}")

    /** 当前活动实例。注册表损坏时回退到 Alpine。 */
    suspend fun activeProfile(): TerminalProfile {
        val settings = loadProfileSettings()
        return settings.defaultProfile()
            ?: settings.profiles.firstOrNull()
            ?: defaultProfileSettings().profiles.first()
    }

    /** 当前活动实例的 shell 层运行时。 */
    suspend fun activeRuntime(): TerminalRuntime {
        val profile = activeProfile()
        val runtime = TerminalRuntime.forDistribution(profile.distributionId)
        return runtime
    }

    /**
     * 切换默认实例。
     *
     * 切换会**重建 provider**：rootfs 变了，已连上的 provider 仍指向旧路径，
     * 继续复用会把命令送进错误的发行版。这里先断开再置空，下次
     * [getTerminalProvider] 会按新 rootfs 重建。
     */
    suspend fun switchActiveProfile(profileId: String): Result<Unit> = runCatching {
        val settings = profileMutex.withLock {
            val current = loadProfileSettingsLocked()
            val updated = requireNotNull(current.withDefault(profileId)) {
                "Unknown terminal profile: $profileId"
            }
            profileStore.save(updated)
            profileSettings = updated
            updated
        }
        Log.d(TAG, "Switched active terminal profile to ${settings.defaultProfileId}")
        resetProvider()
    }

    /**
     * 新增或更新一个实例（用于安装完可下载发行版后登记）。
     *
     * @param makeDefault 是否同时切为默认
     */
    suspend fun upsertProfile(profile: TerminalProfile, makeDefault: Boolean): Result<Unit> =
        runCatching {
            val settings = profileMutex.withLock {
                val current = loadProfileSettingsLocked()
                val profiles =
                    current.profiles.filterNot { it.id == profile.id } + profile
                val updated =
                    current.copy(
                        revision = current.revision + 1,
                        defaultProfileId =
                            if (makeDefault) profile.id else current.defaultProfileId,
                        profiles = profiles,
                    )
                profileStore.save(updated)
                profileSettings = updated
                updated
            }
            if (makeDefault || settings.defaultProfileId == profile.id) {
                resetProvider()
            }
        }

    /** 移除实例。不允许移除最后一个，也不允许移除当前默认实例。 */
    suspend fun removeProfile(profileId: String): Result<Unit> = runCatching {
        profileMutex.withLock {
            val current = loadProfileSettingsLocked()
            requireNotNull(current.withoutProfile(profileId)) {
                "Unknown terminal profile: $profileId"
            }.let { updated ->
                profileStore.save(updated)
                profileSettings = updated
            }
        }
    }

    /** 丢弃内存中的 provider，下次使用时按新 rootfs 重建。 */
    private fun resetProvider() {
        val previous = terminalProvider ?: return
        terminalProvider = null
        coroutineScope.launch {
            runCatching { previous.disconnect() }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        private val environmentMaintenanceMutex = Mutex()
        @Volatile
        private var environmentMaintenanceInProgress = false
        private val _environmentMaintenanceState = MutableStateFlow(false)
        val environmentMaintenanceState: StateFlow<Boolean> = _environmentMaintenanceState.asStateFlow()

        fun isEnvironmentRemovalPending(context: Context): Boolean =
            UbuntuRemovalJournal(context.filesDir).isPending()

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** 停止会话并重置初始化状态，宿主在此期间删除终端环境及安装资源。 */
        suspend fun withEnvironmentMaintenance(
            context: Context,
            resumeOnly: Boolean = false,
            operation: suspend () -> Unit,
        ) {
            environmentMaintenanceMutex.withLock {
                val journal = UbuntuRemovalJournal(context.filesDir)
                // 自动恢复在互斥锁内复查，避免已结束的恢复请求删除后来重新导入的环境。
                if (resumeOnly && !journal.isPending()) return@withLock
                val manager = synchronized(this) {
                    environmentMaintenanceInProgress = true
                    _environmentMaintenanceState.value = true
                    INSTANCE
                }
                try {
                    // 删除开始后完整执行，避免退出资源页面留下半删除的系统。
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        val remove: suspend () -> Unit = {
                            journal.begin()
                            operation()
                            // 清理失败时保留记录，下次启动继续；只有完整成功才清除。
                            journal.finish()
                        }
                        if (manager == null) {
                            remove()
                        } else {
                            manager.envInitMutex.withLock {
                                manager.sessionStartMutex.withLock {
                                    manager.providerMutex.withLock {
                                        manager.prepareForMaintenance()
                                    }
                                    manager.isEnvInitialized = false
                                    remove()
                                }
                            }
                        }
                    }
                } finally {
                    environmentMaintenanceInProgress = false
                    _environmentMaintenanceState.value = false
                }
            }
        }

        @Volatile
        private var ubuntuArchivePreparer: (suspend (Context, File) -> Unit)? = null

        /** 宿主提供并校验 Ubuntu 安装归档，注册过程不创建默认会话。 */
        fun setUbuntuArchivePreparer(preparer: suspend (Context, File) -> Unit) {
            ubuntuArchivePreparer = preparer
        }

        @Volatile
        private var localSessionInitializer: (Context) -> String = { ":" }

        /** 宿主在创建终端前注册本地会话初始化命令；命令失败时不发送就绪标记。 */
        fun setLocalSessionInitializer(initializer: (Context) -> String) {
            localSessionInitializer = initializer
        }

        /**
         * 供本地 provider 在**宿主侧**取用会话初始化脚本。
         *
         * 原来这段脚本由 `common.sh` 里的 `login_ubuntu` 以
         * `eval "$OPERIT_LOCAL_SESSION_INIT" || exit $?` 执行——Alpine 没有 bash 时必失败，
         * 而且失败会连同会话一起退出（静默死亡）。现在作为环境变量传给 proot 的 guest 命令，
         * 由 `LocalTerminalProvider` 决定失败时只记录、不中断。
         */
        internal fun localSessionInitFor(context: Context): String = localSessionInitializer(context)

        private const val TAG = "TerminalManager"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"

        /** 旧 Ubuntu 实例在注册表里的 id；用于把已有安装登记成可切换实例。 */
        private const val LEGACY_UBUNTU_PROFILE_ID = "ubuntu"

        /** 记录已安装内置 rootfs 的 sha256，用于「内容变了才重装」。 */
        private const val INSTALLED_ASSET_SHA_FILE = ".operit_rootfs_sha256"
        private const val MAX_HISTORY_ITEMS = 500
        /** 启动期原始输出最多记录多少字符（8 KB，足够看出 proot 的报错）。 */
        private const val STARTUP_TRANSCRIPT_LIMIT = 8_192
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
        private const val TERMINAL_ENTER = "\r"
        private const val COMMAND_COMPLETION_MARKER_PREFIX = "__OPERIT_COMMAND_DONE__:"
    }

    init {
        coroutineScope.launch {
            for (event in commandEventChannel) {
                _commandExecutionEvents.emit(event)
            }
        }
        // 在初始化时异步创建默认session
        coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                // 自动检测：如果有SSH配置则创建SSH会话，否则创建本地会话
                val sshConfig = sshConfigManager.getConfig()
                val isEnabled = sshConfigManager.isEnabled()
                if (sshConfig != null && isEnabled) {
                    Log.d(TAG, "Found SSH config, creating SSH session")
                    createNewSession("SSH")
                } else {
                    Log.d(TAG, "No SSH config, creating local session")
                    createNewSession("Local")
                }
                Log.d(TAG, "Default session created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成
     * 自动检测终端类型：如果配置了SSH则使用SSH，否则使用本地终端
     * 
     * @param title 会话标题
     */
    suspend fun createNewSession(
        title: String? = null,
        automation: Boolean = false,
    ): TerminalSessionData {
        // 首启解压 158 MB 在低端机要几十秒，必须放在下面 30 s 的会话就绪超时**之外**。
        // 幂等：已就绪时只做几个 stat。失败不中断流程，真正的错误由
        // initializeEnvironment() 之后的诊断兜底。
        ensureBundledEnvironmentReady()
        val terminalType = if (sshConfigManager.getConfig() != null && sshConfigManager.isEnabled())
            TerminalType.SSH else TerminalType.LOCAL
        val startup = CompletableDeferred<Process?>()
        val created = synchronized(Companion) {
            check(!environmentMaintenanceInProgress && !isEnvironmentRemovalPending(context)) {
                "Ubuntu removal is incomplete; finish cleanup in Offline resources"
            }
            sessionManager.createNewSession(title, terminalType, automation).also {
                sessionStartups[it.id] = startup
            }
        }
        initializeSession(created.id, startup)
        try {
            val ready = withTimeoutOrNull(30_000L) {
                terminalState.first { state ->
                    val current = state.sessions.find { it.id == created.id }
                    current == null || current.initState == SessionInitState.READY
                }
            }
            checkNotNull(ready) { "Session initialization timeout" }
            return checkNotNull(sessionManager.getSession(created.id)) {
                "Session closed during initialization"
            }
        } catch (cancelled: CancellationException) {
            closeSessionAndAwait(created.id, 3_000L)
            throw cancelled
        } catch (error: Exception) {
            closeSessionAndAwait(created.id, 3_000L)
            throw error
        }
    }

    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    /** 等待已发布或仍在启动的原进程退出，不把移出状态表当作进程终止证明。 */
    suspend fun closeSessionAndAwait(sessionId: String, timeoutMs: Long): Boolean {
        val startup = sessionStartups[sessionId]
        val published = sessionProcesses[sessionId]
        if (!sessionManager.closeSession(sessionId)) closeTerminalSession(sessionId)
        return withContext(Dispatchers.IO + NonCancellable) {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                val process = published ?: startup?.await()
                while (process?.isAlive == true) delay(25L)
                if (process != null) sessionProcesses.remove(sessionId, process)
                true
            } == true
        }
    }

    internal fun finishClosedSession(session: TerminalSessionData) {
        outputProcessor.finishClosedSession(session, context.getString(R.string.terminal_session_closed))
    }

    /** 排队任务只取消自己；运行任务先停止队列推进，再发中断和限时清理。 */
    suspend fun cancelCommand(
        sessionId: String,
        commandId: String,
        settleMs: Long = 3_000L,
    ): CommandCancellationOutcome = withContext(Dispatchers.IO + NonCancellable) {
        val original = sessionManager.getSession(sessionId)
            ?: return@withContext CommandCancellationOutcome.ALREADY_FINISHED
        val queued = synchronized(original.commandLifecycle) {
            val current = sessionManager.getSession(sessionId)
            current?.commandQueue?.removeAll { it.id == commandId } == true
        }
        if (queued) {
            commandEventChannel.trySend(CommandExecutionEvent(
                commandId, sessionId, "", true, terminationReason = "cancelled",
            ))
            return@withContext CommandCancellationOutcome.REMOVED_FROM_QUEUE
        }
        original.commandLifecycle.cancellationMutex.withLock {
            val running = synchronized(original.commandLifecycle) {
                val current = sessionManager.getSession(sessionId)
                val command = current?.currentExecutingCommand
                if (current == null || current.commandLifecycle.closed ||
                    command == null || command.id != commandId || !command.isExecuting) false
                else {
                    current.commandLifecycle.cancellingCommandId = commandId
                    val recovery = CommandCancellationRecovery(commandId)
                    current.commandLifecycle.cancellationRecovery = recovery
                    enqueueInput(current, "\u0003", commandId, recovery)
                    true
                }
            }
            if (!running) return@withLock CommandCancellationOutcome.ALREADY_FINISHED
            val settled = withTimeoutOrNull(settleMs.coerceAtLeast(1L)) {
                while (true) {
                    val done = synchronized(original.commandLifecycle) {
                        val current = sessionManager.getSession(sessionId)?.currentExecutingCommand
                        current == null || current.id != commandId || !current.isExecuting
                    }
                    if (done) break
                    delay(25L)
                }
                true
            } == true
            // 限时结束与完成事件可能同时到达，退役前在同一监视器内复查。
            val finished = synchronized(original.commandLifecycle) {
                val current = sessionManager.getSession(sessionId)?.currentExecutingCommand
                current == null || current.id != commandId || !current.isExecuting
            }
            if (settled || finished) {
                synchronized(original.commandLifecycle) {
                    original.commandLifecycle.cancellingCommandId = null
                    original.commandLifecycle.cancellationRecovery = null
                }
                processNextQueuedCommand(sessionId)
                if (original.commandLifecycle.closed) {
                    if (closeSessionAndAwait(sessionId, 2_000L)) CommandCancellationOutcome.SESSION_CLOSED
                    else CommandCancellationOutcome.CLEANUP_INCOMPLETE
                } else CommandCancellationOutcome.INTERRUPTED
            } else {
                val pty = sessionManager.getSession(sessionId)?.pty
                val cleaned = try {
                    pty?.terminateSession(2_000L) == true
                } catch (error: Exception) {
                    Log.w(TAG, "Session process cleanup failed: $sessionId", error)
                    false
                }
                val closed = closeSessionAndAwait(sessionId, 2_000L)
                if (cleaned && closed) CommandCancellationOutcome.SESSION_CLOSED
                else CommandCancellationOutcome.CLEANUP_INCOMPLETE
            }
        }
    }

    /**
     * 会话关闭后的运行态清理（非持久化状态）。
     */
    fun onSessionClosed(sessionId: String) {
        outputProcessor.clearSessionState(sessionId)
    }
    
    /**
     * 保存会话的滚动位置
     */
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        sessionManager.saveScrollOffset(sessionId, scrollOffset)
    }
    
    /**
     * 获取会话的滚动位置
     */
    fun getScrollOffset(sessionId: String): Float {
        return sessionManager.getScrollOffset(sessionId)
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    private fun writeInputToKernel(session: TerminalSessionData, input: String, source: String) {
        val writer = checkNotNull(session.sessionWriter) { "Terminal writer is unavailable" }
        writer.write(input)
        writer.flush()
        Log.d(
            TAG,
            "Sent terminal input to kernel [source=$source, sessionId=${session.id}]: '${escapeInputForLog(input)}'"
        )
    }

    private fun escapeInputForLog(input: String): String {
        return buildString(input.length) {
            input.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.isISOControl()) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    /**
     * 发送命令
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val actualId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getCurrentSession() ?: return actualId
        if (session.isInteractiveMode || session.initState != SessionInitState.READY) {
            sendInput(command + TERMINAL_ENTER, session.id)
            return actualId
        }
        return sendCommandToSession(session.id, command, actualId)
    }

    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        val actualId = commandId ?: UUID.randomUUID().toString()
        val original = sessionManager.getSession(sessionId)
        if (original == null) {
            emitCommandFailureEvent(actualId, sessionId, "Terminal session does not exist: $sessionId")
            return actualId
        }
        synchronized(original.commandLifecycle) {
            val current = sessionManager.getSession(sessionId)
            if (current == null || current.commandLifecycle.closed) {
                emitCommandFailureEvent(actualId, sessionId, "Terminal session is closed")
            } else if (current.initState != SessionInitState.READY) {
                emitCommandFailureEvent(actualId, sessionId, "Terminal session is not ready: ${current.initState}")
            } else if (current.currentExecutingCommand?.isExecuting == true ||
                current.commandLifecycle.cancellingCommandId != null) {
                current.commandQueue.add(QueuedCommand(actualId, command))
            } else {
                executeCommandInternal(command, current, actualId)
            }
        }
        return actualId
    }

    private fun emitCommandFailureEvent(
        commandId: String,
        sessionId: String,
        message: String
    ) {
        if (!commandEventChannel.trySend(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = message,
                    isCompleted = true,
                    exitCode = -1,
                    terminationReason = "dispatch_failed",
                )
            ).isSuccess
        ) {
            Log.w(TAG, "Dropping terminal command failure event for session $sessionId")
        }
    }

    /**
     * 处理队列中的下一个命令
     */
    private suspend fun processNextQueuedCommand(sessionId: String) {
        val original = sessionManager.getSession(sessionId) ?: return
        synchronized(original.commandLifecycle) {
            val current = sessionManager.getSession(sessionId) ?: return
            if (current.commandLifecycle.closed || current.commandLifecycle.cancellingCommandId != null ||
                current.currentExecutingCommand?.isExecuting == true) return
            if (current.commandQueue.isNotEmpty()) {
                val next = current.commandQueue.removeAt(0)
                executeCommandInternal(next.command, current, next.id)
            }
        }
    }

    /** 命令提交、完成和取消共用生命周期监视器，避免取消误伤下一条命令。 */
    private fun executeCommandInternal(command: String, session: TerminalSessionData, commandId: String) {
        handleRegularCommand(command, session, commandId)
        session.commandLifecycle.latestScreen = null
        if (!enqueueInput(session, buildCommandInput(command, commandId), commandId)) {
            sessionManager.closeSession(session.id)
        }
    }

    /** 每个会话独立串行写入；阻塞的 PTY 不占生命周期锁，也不阻塞其他会话。 */
    private fun startInputWorker(sessionId: String) {
        val inputs = Channel<PendingInput>(Channel.UNLIMITED)
        sessionInputs[sessionId] = inputs
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (input in inputs) {
                    val session = sessionManager.getSession(sessionId) ?: break
                    val accepted = synchronized(session.commandLifecycle) {
                        if (session.commandLifecycle.closed || (input.commandId != null &&
                                session.currentExecutingCommand?.id != input.commandId)) {
                            false
                        } else if (input.recovery != null) {
                            if (session.commandLifecycle.cancellationRecovery !== input.recovery) false
                            else {
                                // 写操作仍在锁外；先标记发送阶段，避免漏掉紧随中断到来的提示符。
                                if (input.recoveryProbe) input.recovery.onProbeDispatched()
                                else input.recovery.onInterruptDispatched()
                                true
                            }
                        } else true
                    }
                    if (!accepted) continue
                    try {
                        writeInputToKernel(session, input.text, "session-input")
                        if (!input.terminalResponse && session.isWaitingForInteractiveInput) {
                            sessionManager.updateSession(sessionId) { it.copy(isWaitingForInteractiveInput = false) }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Terminal input failed: $sessionId", error)
                        sessionManager.closeSession(sessionId)
                        break
                    }
                }
            } finally {
                inputs.cancel()
                sessionInputs.remove(sessionId, inputs)
            }
        }
        inputJobs[sessionId] = job
        job.invokeOnCompletion { inputJobs.remove(sessionId, job) }
        job.start()
    }

    private fun enqueueInput(
        session: TerminalSessionData,
        text: String,
        commandId: String? = null,
        recovery: CommandCancellationRecovery? = null,
        recoveryProbe: Boolean = false,
        terminalResponse: Boolean = false,
    ): Boolean = sessionInputs[session.id]?.trySend(
        PendingInput(text, commandId, recovery, recoveryProbe, terminalResponse),
    )?.isSuccess == true

    /** Protocol replies use the session writer without changing the pending user prompt. */
    private fun enqueueTerminalResponse(sessionId: String, response: String) {
        val session = sessionManager.getSession(sessionId) ?: return
        if (!enqueueInput(session, response, terminalResponse = true)) {
            Log.w(TAG, "Terminal response queue is closed for session $sessionId")
        }
    }

    /** 提示符只触发验证，不直接结束命令；令牌回复才证明 shell 已重新处理输入。 */
    private fun enqueueCancellationProbe(sessionId: String) {
        val session = sessionManager.getSession(sessionId) ?: return
        synchronized(session.commandLifecycle) {
            val recovery = session.commandLifecycle.cancellationRecovery ?: return
            if (session.commandLifecycle.closed || session.terminalType != TerminalType.LOCAL ||
                session.currentExecutingCommand?.id != recovery.commandId ||
                session.currentExecutingCommand?.isExecuting != true ||
                !recovery.queueProbeAfterPrompt()) return
            if (!enqueueInput(session, recovery.probeInput, recovery.commandId, recovery, recoveryProbe = true)) {
                sessionManager.closeSession(sessionId)
            }
        }
    }

    private fun buildCommandInput(command: String, commandId: String): String {
        val normalizedCommand = command.trimEnd('\r', '\n')
        return buildString {
            append(normalizedCommand)
            append(TERMINAL_ENTER)
            append("printf '\\n%s:%s\\n' '")
            append(COMMAND_COMPLETION_MARKER_PREFIX)
            append(commandId)
            append("' " )
            append('$').append('?')
            append(TERMINAL_ENTER)
        }
    }

    /**
     * 发送输入
     */
    fun sendInput(input: String, sessionId: String? = sessionManager.getCurrentSession()?.id) {
        val session = sessionId?.let { sessionManager.getSession(it) } ?: return
        // 虚拟按键同样遵守全屏程序启用的应用光标键模式，已编码的修饰序列保持原样。
        val text = if (session.ansiParser.isApplicationCursorKeysEnabled() &&
            input.length == 3 && input.startsWith("\u001b[") && input.last() in "ABCDHF"
        ) "\u001bO${input.last()}" else input
        if (!enqueueInput(session, text)) {
            Log.w(TAG, "Terminal input queue is closed for session ${session.id}")
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal(sessionId: String? = sessionManager.getCurrentSession()?.id) {
        val session = sessionId?.let { sessionManager.getSession(it) } ?: return
        synchronized(session.commandLifecycle) {
            enqueueInput(session, "\u0003", session.currentExecutingCommand?.id)
        }
    }

    private fun initializeSession(sessionId: String, startup: CompletableDeferred<Process?>) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                check(initializeEnvironment()) { "Terminal environment initialization failed" }
                sessionStartMutex.withLock {
                    if (environmentMaintenanceInProgress || isEnvironmentRemovalPending(context)) {
                        error("Terminal environment is unavailable")
                    }
                    val original = sessionManager.getSession(sessionId) ?: return@withLock
                    val provider = getTerminalProvider()
                    val result = if (original.automation && provider is LocalTerminalProvider)
                        provider.startAutomationSession(sessionId) else provider.startSession(sessionId)
                    val (terminalSession, pty) = result.getOrThrow()
                    sessionPtys[sessionId] = pty
                    sessionProcesses[sessionId] = terminalSession.process
                    activeSessions[sessionId] = terminalSession
                    startup.complete(terminalSession.process)
                    if (sessionManager.getSession(sessionId) == null || original.commandLifecycle.closed) {
                        closeTerminalSession(sessionId)
                        return@withLock
                    }
                    val writer = terminalSession.stdin.writer()
                    val reader = coroutineScope.launch(start = CoroutineStart.LAZY) {
                        var stopped = false
                        var failure: Exception? = null
                        try {
                            terminalSession.stdout.reader(Charsets.UTF_8).use { input ->
                                val buffer = CharArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) {
                                        val chunk = String(buffer, 0, count)
                                        recordStartupOutput(sessionId, chunk)
                                        outputProcessor.processOutput(sessionId, chunk, sessionManager)
                                    }
                                }
                                stopped = true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error
                        } finally {
                            if (!closingSessions.contains(sessionId) && !original.commandLifecycle.closed &&
                                (stopped || failure != null || !terminalSession.process.isAlive)) {
                                withContext(NonCancellable) {
                                    withTimeoutOrNull(500L) {
                                        while (terminalSession.process.isAlive) delay(25L)
                                    }
                                    failure?.let { Log.w(TAG, "Terminal reader stopped: $sessionId", it) }
                                    handleTerminalSessionExit(sessionId, terminalSession)
                                }
                            }
                        }
                    }
                    val exit = coroutineScope.launch(start = CoroutineStart.LAZY) {
                        awaitTerminalExit({ terminalSession.process.isAlive }, reader)
                        withContext(NonCancellable) {
                            handleTerminalSessionExit(sessionId, terminalSession)
                            reader.cancel()
                        }
                    }
                    reader.invokeOnCompletion { closingSessions.remove(sessionId) }
                    sessionManager.updateSession(sessionId) {
                        it.copy(terminalSession = terminalSession, pty = pty, sessionWriter = writer,
                            readJob = reader, exitJob = exit)
                    }
                    if (sessionManager.getSession(sessionId) == null || original.commandLifecycle.closed) {
                        reader.cancel()
                        exit.cancel()
                        closeTerminalSession(sessionId)
                    } else {
                        startInputWorker(sessionId)
                        beginStartupTranscript(sessionId)
                        reader.start()
                        exit.start()
                    }
                }
            } catch (cancelled: CancellationException) {
                sessionManager.closeSession(sessionId)
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Error starting session: $sessionId", error)
                sessionManager.closeSession(sessionId)
                closeTerminalSession(sessionId)
            } finally {
                startup.complete(sessionProcesses[sessionId])
                sessionStartups.remove(sessionId, startup)
            }
        }
    }

    private fun handleTerminalSessionExit(sessionId: String, terminalSession: TerminalSession) {
        flushStartupTranscript(sessionId)
        if (sessionManager.getSession(sessionId) == null) return
        val code = if (terminalSession.process.isAlive) -1 else try {
            terminalSession.process.waitFor()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to observe terminal exit status: $sessionId", error)
            -1
        }
        outputProcessor.handleSessionExit(sessionId,
            if (code < 0) context.getString(R.string.terminal_session_closed)
            else context.getString(R.string.terminal_exited_with_code, code), sessionManager, code)
        sessionManager.closeSession(sessionId)
    }

    /**
     * 获取或创建单例的终端提供者
     */
    private suspend fun getTerminalProvider(): TerminalProvider {
        providerMutex.withLock {
            check(!environmentMaintenanceInProgress && !isEnvironmentRemovalPending(context)) {
                "Ubuntu removal is incomplete; finish cleanup in Offline resources"
            }
            if (terminalProvider == null) {
                val sshConfig = sshConfigManager.getConfig()
                val provider = if (sshConfig != null && sshConfigManager.isEnabled()) {
                    Log.d(TAG, "Creating singleton SSH terminal provider")
                    SSHTerminalProvider(context, sshConfig, this)
                } else {
                    Log.d(TAG, "Creating singleton local terminal provider")
                    LocalTerminalProvider(context)
                }
                provider.connect().getOrThrow()
                terminalProvider = provider
            }
            return terminalProvider!!
        }
    }

    suspend fun initializeEnvironment(): Boolean = envInitMutex.withLock {
        if (environmentMaintenanceInProgress || isEnvironmentRemovalPending(context)) return@withLock false
        withContext(Dispatchers.IO) {
            try {
                if (!isEnvInitialized) {
                    Log.d(TAG, "Starting environment initialization...")
                    createDirectories()
                    linkNativeLibs()
                    createBusyboxSymlinks()
                    extractAssets()
                    // 解析当前活动实例并缓存：generateStartScript 是同步函数，
                    // 不能在里面直接读挂起函数的 profile。
                    scriptProfileSnapshot = activeProfile()
                }
                writeStartScriptAtomically()
                isEnvInitialized = true
                Log.d(TAG, "Environment initialization completed successfully.")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Environment initialization failed", e)
                // 原实现只返回 false，调用方拿到的是一句无信息的
                // `Terminal environment initialization failed`。把原因记下来供诊断。
                lastEnvironmentInitError = e
                false
            }
        }
    }

    /**
     * 最近一次环境初始化失败的原因；成功时为 null。
     *
     * 存在的意义：`initializeEnvironment()` 的返回类型是 `Boolean`，
     * 有 3 个调用点（`Terminal.kt:67`、本文件 `:684` 与 `:1721`），
     * 改签名会波及 UI 层。用这个字段补上诊断信息，不破坏既有 API。
     */
    @Volatile
    var lastEnvironmentInitError: Throwable? = null
        private set

    /**
     * 启动时预热：确保内置 Alpine 已解压。可重复调用，内部幂等。
     *
     * 与 [initializeEnvironment] 的分工：这里**只**准备 rootfs 与宿主机侧的
     * bin 链接，不生成启动脚本（脚本要按当前活动实例生成，属于会话职责）。
     * 之所以单独开一个入口：应用 onCreate 时还没有任何会话，
     * 而用户打开终端时不应该先看到「rootfs missing」再等几十秒。
     *
     * 失败不抛出，返回 false；原因见 [lastAlpineInstallError]、
     * [bundledEnvState] 与 `filesDir/logs/terminal-init.log`。
     */
    suspend fun ensureBundledEnvironmentReady(): Boolean {
        if (environmentMaintenanceInProgress || isEnvironmentRemovalPending(context)) {
            val message = "Environment maintenance in progress; skipping bundled Alpine preparation"
            terminalInitLog(message)
            _bundledEnvState.value = BundledEnvState.Failed(message)
            return false
        }
        return envInitMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // 宿主机侧的 bin 链接很便宜（几个 symlink），即使 rootfs 已就绪也要保证它们在，
                    // 否则 `$BIN/busybox` 缺失同样会让启动脚本失败。
                    createDirectories()
                    linkNativeLibs()
                    createBusyboxSymlinks()
                    val target = alpineRootfsDir()
                    if (isBundledAlpineUpToDate(target)) {
                        // 已就绪：直接返回，不重新解压 158 MB。仍更新状态流，让 UI 的
                        // `bundledEnvState` 从 Idle 迁移到 Ready。
                        terminalInitLog("Bundled Alpine already ready at ${target.absolutePath}")
                        _bundledEnvState.value = BundledEnvState.Ready(target.absolutePath)
                        return@withContext true
                    }
                    terminalInitLog("Preparing bundled Alpine environment")
                    installBundledAlpine().isSuccess
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastEnvironmentInitError = e
                    _bundledEnvState.value = BundledEnvState.Failed(e.toString())
                    terminalInitLog("Bundled environment preparation failed: $e\n${e.stackTraceToString()}")
                    false
                }
            }
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        // `useLegacyPackaging = false` 时 AGP 不再把 .so 解压到 nativeLibraryDir，
        // 该目录会存在但为空（或某些设备上直接不存在）。终端必须能 execve 真实文件，
        // 所以这里先确保有一份可执行的实体库，再走原有的符号链接逻辑。
        val resolvedLibDir = resolveTerminalBinaryDir()
        if (resolvedLibDir == null) {
            Log.e(TAG, "No executable terminal binaries available (nativeLibDir=$nativeLibDir)")
            return
        }

        val nativeLibDirFile = File(resolvedLibDir)

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(resolvedLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")

        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()

            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)

            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)

            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")

            // 只验证链接可解析；不要用 canExecute() 判定，原因与 AlpineRuntime.isInstalled 相同：
            // access(X_OK) 会被 SELinux/挂载策略影响，不能代表设备能否执行该 inode。
            // 这里若因假阴性 return，会连带跳过 proot/loader/bash 的链接，直接让终端不可用。
            if (busybox.exists()) {
                Log.d(TAG, "Verification: busybox link exists at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link does not resolve after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        try {
            Files.deleteIfExists(File(binDir, "libtalloc.so.2").toPath())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove stale libtalloc link", e)
        }

        // `libsudo.so` is not shipped as a real Android ELF in this module. Keep
        // the optional command out of the link set until a native implementation
        // is provided, so a placeholder file cannot be packaged or linked.
        val libraries = mapOf(
            "liboperit_proot.so" to "proot",
            "liboperit_loader.so" to "loader",
            "libbash.so" to "bash"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(resolvedLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()

                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)

                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)

                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")

                // 只验证链接可解析；exec 位由 inode 自身的 mode 决定，不能用 access(X_OK) 判定。
                if (linkFile.exists()) {
                    Log.d(TAG, "Verification: $linkName link exists at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link does not resolve after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }

    /** 终端需要 execve 的 native 组件；顺序不影响结果。 */
    private val terminalBinaryNames = listOf(
        "libbusybox.so",
        "libbash.so",
        "liboperit_proot.so",
        "liboperit_loader.so",
    )

    /**
     * 返回一个**包含可执行实体 `.so`** 的目录，供 `linkNativeLibs` 建符号链接。
     *
     * 背景：`useLegacyPackaging = false`（Android 15+ 的 16 KB page size 要求）下，
     * AGP 不再把 `.so` 解压到 `applicationInfo.nativeLibraryDir`，该目录存在但为空。
     * 终端必须能 `execve` 真实文件，因此这里把必需组件从 APK 抽出到私有目录。
     *
     * 优先用系统已解压的目录（老包/其他设备），否则从 APK 抽取，全部失败返回 null。
     */
    private fun resolveTerminalBinaryDir(): String? {
        val systemDir = File(nativeLibDir)
        val systemReady = terminalBinaryNames.all { File(systemDir, it).isFile }
        if (systemReady) {
            Log.d(TAG, "Terminal binaries present in nativeLibraryDir: $nativeLibDir")
            return nativeLibDir
        }

        val targetDir = File(filesDir, "usr/libexec")
        val apkVersion = apkVersionTag()
        val stamp = File(targetDir, ".version")

        val upToDate =
            targetDir.isDirectory &&
                terminalBinaryNames.all { File(targetDir, it).isFile } &&
                stamp.isFile &&
                stamp.readText().trim() == apkVersion
        if (upToDate) {
            Log.d(TAG, "Terminal binaries already extracted at ${targetDir.absolutePath}")
            return targetDir.absolutePath
        }

        return try {
            extractTerminalBinariesFromApk(targetDir)
            stamp.writeText(apkVersion, Charsets.UTF_8)
            Log.d(TAG, "Extracted terminal binaries to ${targetDir.absolutePath}")
            targetDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract terminal binaries from APK", e)
            null
        }
    }

    /** 用 APK 路径 + lastUpdateTime 做版本标记，重装后自动重新抽取。 */
    private fun apkVersionTag(): String {
        val info = context.applicationInfo
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.sourceDir}:${pkgInfo.lastUpdateTime}"
    }

    private fun extractTerminalBinariesFromApk(targetDir: File) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("cannot create ${targetDir.absolutePath}")
        }
        val apk = File(context.applicationInfo.sourceDir)
        ZipFile(apk).use { zip ->
            for (name in terminalBinaryNames) {
                val entry = zip.getEntry("lib/arm64-v8a/$name")
                    ?: throw IOException("APK missing lib/arm64-v8a/$name")
                val out = File(targetDir, name)
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                if (!out.setExecutable(true, false)) {
                    Log.w(TAG, "setExecutable failed for ${out.absolutePath}")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private suspend fun extractAssets() {
        // 失败不能冒泡：作者的意图是「Alpine 装不上不该连带 Ubuntu 一起失效」。
        // 但也不能静默——原因已经写进 lastAlpineInstallError / bundledEnvState /
        // filesDir/logs/terminal-init.log（见 installBundledAlpine）。
        installBundledAlpine().onFailure { error ->
            Log.e(TAG, "Bundled Alpine unavailable; continuing environment initialization", error)
        }
        ensureLegacyUbuntuIfPresent()

        // 每次更新小型脚本；大型安装归档由宿主校验后提供。
        val script = context.assets.open("setup_fake_sysdata.sh").bufferedReader(Charsets.UTF_8).use { it.readText() }
        File(filesDir, "setup_fake_sysdata.sh").writeText(
            script.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n"),
            Charsets.UTF_8,
        )
    }

    /**
     * 诊断（**不是判据**）：记录本进程对 `bin/busybox` 的 access(2) 结果与 errno、
     * 文件 mode、SELinux 域/开关、`/data` 挂载参数。
     *
     * 上一轮的矛盾（Kotlin 认为可执行、ash 的 `[ -x ]` 认为不可执行）之所以查不下去，
     * 是因为两套布尔结果背后没有任何 errno/策略上下文。这里把 EACCES、EPERM、ENOENT
     * 分开记录；下一次真机日志就能定位到「权限位 / SELinux / 挂载策略」中的哪一个。
     *
     * 结论不参与 `isInstalled`、不阻塞安装、不影响终端启动。
     */
    private fun logRootfsExecDiagnostics(rootfs: File) {
        val busybox = File(rootfs, "bin/busybox")
        val access = runCatching {
            Os.access(busybox.absolutePath, OsConstants.X_OK)
            "granted"
        }.getOrElse { error ->
            if (error is ErrnoException) {
                "denied errno=${error.errno} (${Os.strerror(error.errno)})"
            } else {
                "error=${error.message}"
            }
        }
        val modeBits = runCatching { Os.stat(busybox.absolutePath).st_mode }
            .getOrNull()
            ?.let { Integer.toOctalString(it and 0x1FF) }
            ?: "<stat failed>"
        val selinux = runCatching { File("/proc/self/attr/current").readText().trim() }
            .getOrNull() ?: "<unreadable>"
        val enforce = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }
            .getOrNull() ?: "<unreadable>"
        val dataMount = runCatching {
            File("/proc/mounts").useLines { lines ->
                lines.firstOrNull { it.split(' ').getOrNull(1) == "/data" }
            }
        }.getOrNull() ?: "<no /data mount line>"
        terminalInitLog(
            "rootfs exec diagnostics: path=${busybox.absolutePath} " +
                "file=${busybox.isFile} mode=$modeBits accessX=$access " +
                "selinux=$selinux enforce=$enforce dataMount=$dataMount"
        )
    }

    /**
     * 把 APK 内置的 Alpine rootfs 装到 `installed-rootfs/alpine`。
     *
     * 这是**默认**终端环境，所以放在初始化路径上同步完成。
     * 已装且内容一致时直接跳过（比对 sha256），不会每次启动都解压 170 MB。
     *
     * 与旧实现的区别：失败不再被 `Log.e` 吞掉。异常写进
     * [lastAlpineInstallError] / `filesDir/logs/terminal-init.log` / [bundledEnvState]，
     * 并以 `Result.failure` 返回，让调用方能决定是否继续。
     * [extractAssets] 仍然选择「不中断」——Alpine 装不上不该连带 Ubuntu 一起失效。
     */
    private fun installBundledAlpine(): Result<Unit> = try {
        val target = alpineRootfsDir()
        val sha = runCatching { readAssetText(AlpineRuntime.ASSET_SHA_PATH) }
            .getOrNull()
            ?.let { AlpineRuntime.parseSha(it) }

        val markerFile = File(target, INSTALLED_ASSET_SHA_FILE)
        val installedSha = runCatching { markerFile.readText().trim() }.getOrNull()
        val isInstalled = AlpineRuntime.isInstalled(target)
        terminalInitLog(
            "Bundled Alpine probe: target=${target.absolutePath} expectedSha=$sha " +
                "markerFile=${markerFile.absolutePath} markerExists=${markerFile.isFile} " +
                "markerContent=${installedSha ?: "<none>"} isInstalled=$isInstalled"
        )
        // sha 文件缺失（例如本地调试构建）时退化为「存在即可用」。
        val alreadyInstalled = isInstalled && (sha == null || installedSha == sha)

        // 只在目录已存在时记录：首次安装前后都没有可诊断对象。
        if (target.isDirectory) logRootfsExecDiagnostics(target)

        if (alreadyInstalled) {
            terminalInitLog(
                "Bundled Alpine rootfs already installed at ${target.absolutePath} " +
                    "(expectedSha=$sha recordedSha=$installedSha)"
            )
            _bundledEnvState.value = BundledEnvState.Ready(target.absolutePath)
            Result.success(Unit)
        } else {
            val available = runCatching { context.assets.open(AlpineRuntime.ASSET_PATH).close() }.isSuccess
            terminalInitLog("Bundled Alpine asset ${AlpineRuntime.ASSET_PATH} openable=$available")
            if (!available) {
                error("Bundled Alpine asset missing: ${AlpineRuntime.ASSET_PATH}")
            }

            _bundledEnvState.value = BundledEnvState.Preparing
            terminalInitLog("Installing bundled Alpine rootfs into ${target.absolutePath} (expectedSha=$sha)")
            val result = TerminalRootfsInstaller.installFromAsset(
                assetOpener = { context.assets.open(AlpineRuntime.ASSET_PATH) },
                instancesRoot = target.parentFile ?: filesDir,
                instanceId = AlpineRuntime.PROFILE_ID,
                displayName = AlpineRuntime.DISPLAY_NAME,
                expectedSha256 = sha,
                onProgress = { progress ->
                    terminalInitLog(
                        "Alpine install: state=${progress.state} " +
                            "downloaded=${progress.downloadedBytes} total=${progress.totalBytes}"
                    )
                },
            ).getOrThrow()

            terminalInitLog(
                "Alpine rootfs ready at ${result.profile.rootfsPath} " +
                    "(alreadyInstalled=${result.alreadyInstalled})"
            )
            _bundledEnvState.value = BundledEnvState.Ready(result.profile.rootfsPath)
            Result.success(Unit)
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        val detail = error.toString() + "\n" + error.stackTraceToString()
        lastAlpineInstallError = detail
        _bundledEnvState.value = BundledEnvState.Failed(error.toString())
        terminalInitLog("FAILED to install bundled Alpine rootfs: $detail")
        Result.failure(error)
    }

    /**
     * 内置 Alpine 是否已是最新内容。
     *
     * 判据必须同时含 sha 标记：APK 升级换了 rootfs 时，目录虽然还在，
     * 但内容已经过期，必须重装。
     */
    private fun isBundledAlpineUpToDate(target: File = alpineRootfsDir()): Boolean {
        if (!AlpineRuntime.isInstalled(target)) return false
        val expected = runCatching { readAssetText(AlpineRuntime.ASSET_SHA_PATH) }
            .getOrNull()
            ?.let { AlpineRuntime.parseSha(it) }
            ?: return true
        val recorded = runCatching {
            File(target, INSTALLED_ASSET_SHA_FILE).readText().trim()
        }.getOrNull()
        return recorded == expected
    }

    /**
     * 当前活动终端实例的 rootfs 是否已就位。**同步**，可在点击回调/Composable 里直接调用。
     *
     * 为什么要有它：`OfflineResources.ubuntuInstalled()` 只看 Ubuntu 目录，
     * 而默认实例是内置 Alpine；两者各判各的，于是「Alpine 已装好」在 UI 上仍显示未安装。
     * 这里按注册表判定，成为终端可用性的**单一**判据。
     *
     * 读的是 `TerminalProfileStore` 而不是 `profileSettings` 缓存：调用方可能是
     * 全新进程里的 UI 代码，缓存里什么都没有。
     */
    fun isActiveRootfsInstalled(context: Context): Boolean {
        val settings = runCatching { profileStore.load() }.getOrNull()
        val profile = settings?.defaultProfile() ?: settings?.profiles?.firstOrNull()
        val rootfs = profile?.rootfsPath?.let(::File) ?: File(
            context.filesDir,
            "usr/var/lib/proot-distro/installed-rootfs/${AlpineRuntime.PROFILE_ID}",
        )
        return if (profile == null || profile.distributionId == AlpineRuntime.PROFILE_ID) {
            AlpineRuntime.isInstalled(rootfs)
        } else {
            // 可下载发行版（Ubuntu 等）没有 busybox，判据与 archive 安装器的 validateArm64 一致。
            File(rootfs, "bin/bash").isFile && File(rootfs, "etc").isDirectory
        }
    }

    /**
     * 触发内置 Alpine 预热但**不等待**结果。
     *
     * UI 用这个而不是直接 await [ensureBundledEnvironmentReady]：解压 158 MB 可能超过
     * 界面的「准备中」超时，若把解压挂在可取消的等待上，超时会连同解压一起取消，
     * 只能从头再来。状态通过 [bundledEnvState] 反馈。
     */
    fun prepareBundledEnvironmentAsync() {
        if (_bundledEnvState.value is BundledEnvState.Preparing) return
        coroutineScope.launch { ensureBundledEnvironmentReady() }
    }

    /**
     * 启动时预热内置 Alpine：`Log` + `filesDir/logs/terminal-init.log` 双写。
     *
     * 用户看到的唯一运行时证据就是这两个地方；不写文件的话，
     * 真机上只剩一句「rootfs missing」，无法判断是解压失败还是没触发。
     */
    private fun terminalInitLog(message: String) {
        Log.d(TAG, message)
        runCatching {
            val logsDir = File(filesDir, "logs").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            File(logsDir, "terminal-init.log").appendText("$stamp [$TAG] $message\n", Charsets.UTF_8)
        }.onFailure { Log.e(TAG, "Cannot append terminal-init.log", it) }
    }

    private fun beginStartupTranscript(sessionId: String) {
        startupTranscripts[sessionId] = StringBuilder()
    }

    private fun recordStartupOutput(sessionId: String, chunk: String) {
        val transcript = startupTranscripts[sessionId] ?: return
        if (transcript.length >= STARTUP_TRANSCRIPT_LIMIT) return
        transcript.append(chunk, 0, minOf(chunk.length, STARTUP_TRANSCRIPT_LIMIT - transcript.length))
        if (transcript.length >= STARTUP_TRANSCRIPT_LIMIT) flushStartupTranscript(sessionId)
    }

    /** 把启动期原始输出写进 `logs/terminal-init.log`；失败诊断只靠这一份证据。 */
    private fun flushStartupTranscript(sessionId: String) {
        val transcript = startupTranscripts.remove(sessionId) ?: return
        if (transcript.isEmpty()) return
        terminalInitLog("session $sessionId startup output (pty raw, proot/shell stderr+stdout):\n$transcript")
    }

    /**
     * 兼容既有 Ubuntu：如果用户此前已经在用 Kong 的 Ubuntu，把它登记进注册表，
     * 但**不切为默认**——默认仍然是 Alpine。
     *
     * 不在这里准备 Ubuntu 归档：那需要几十 MB 下载，
     * 用户没选 Ubuntu 时不该付出这个代价。归档在真正切换到该实例时才准备。
     */
    private suspend fun ensureLegacyUbuntuIfPresent() {
        val legacyRootfs = File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        val installed = File(legacyRootfs, ".operit_installed_ok").isFile ||
            File(legacyRootfs, "etc/issue.net").isFile
        if (!installed) return

        val settings = loadProfileSettings()
        if (settings.profiles.any { it.id == LEGACY_UBUNTU_PROFILE_ID }) return

        upsertProfile(
            profile =
                TerminalProfile(
                    id = LEGACY_UBUNTU_PROFILE_ID,
                    name = "Ubuntu",
                    distributionId = TerminalDistributions.UBUNTU_24_04.id,
                    rootfsPath = legacyRootfs.absolutePath,
                    installed = true,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            makeDefault = false,
        ).onSuccess { Log.d(TAG, "Registered existing Ubuntu install as switchable profile") }
            .onFailure { Log.w(TAG, "Cannot register existing Ubuntu profile", it) }
    }

    private fun readAssetText(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * 生成启动脚本时用的活动实例快照。
     *
     * 为什么用缓存而不是在 [generateStartScript] 里直接读 profile：
     * `generateStartScript()` → `writeStartScriptAtomically()` 是**同步**函数
     * （用 `Os.rename` 做原子写），而 profile 读取是挂起函数。
     * 与其把整条链路改成挂起，不如在初始化时解析一次写进这里——
     * 脚本本来也只在 [initializeEnvironment] 里生成。
     */
    @Volatile
    private var scriptProfileSnapshot: TerminalProfile? = null

    /** 当前活动实例的 rootfs 路径（脚本里 `UBUNTU_PATH` 的取值）。 */
    private fun activeRootfsPathForScript(): String =
        scriptProfileSnapshot?.rootfsPath
            ?: alpineRootfsDir().absolutePath

    /** 当前活动实例的 shell 层运行时（决定登录命令、包源写法）。 */
    private fun activeRuntimeForScript(): TerminalRuntime =
        TerminalRuntime.forDistribution(scriptProfileSnapshot?.distributionId)

    /**
     * 单引号包裹一个字符串，供 shell 直接使用。
     *
     * 之所以需要：发行版名字、shell 路径这些值最终会写进 `export X=...`。
     * 不转义的话，名字里一旦出现空格或 `$` 就会破坏整份脚本。
     * 单引号内除了 `'` 本身没有特殊字符，所以把 `'` 拆成 `'"'"'` 即可。
     */
    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'" + '"' + "'" + '"' + "'") + "'"

    private fun generateStartScript(): String {
        // 变量名保持 UBUNTU_PATH 不变：脚本里有 40+ 处引用它，
        // 而它现在表示「当前活动实例的 rootfs」，语义已经从「Ubuntu」泛化为「当前发行版」。
        // 改名只会引入遗漏风险，不带来收益。
        val ubuntuName = UBUNTU_FILENAME.replace(Regex("-pd.*"), "")
        val activeRootfs = activeRootfsPathForScript()
        val runtime = activeRuntimeForScript()
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        // 指向「当前活动实例」的 rootfs，由 profile 决定（默认 Alpine）。
        val ubuntuPath = activeRootfs
        val operitPackage = context.packageName
        val operitDataDir = context.applicationInfo.dataDir
        val currentEmulatedStoragePath = PRootMountMapping.currentEmulatedStoragePath()
        val currentUserDataRootPath = PRootMountMapping.currentUserDataRootPath()
        val operitUserDataMountPath = "$currentUserDataRootPath/$operitPackage"
        val operitLegacyDataMountPath = "/data/data/$operitPackage"

        // 获取当前选择的源
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        # proot 自身的映射依赖：缺 PROOT_LOADER 时 proot 无法映射 guest ELF，
        # 表现是 exec 之后没有任何输出、会话直接关闭。宿主 Kotlin 侧
        # （ProotCommand）对本地终端同样显式设置这两个变量，这里是 SSH/chroot 路径的同一份事实。
        export PROOT_LOADER="${'$'}BIN/loader"
        export PROOT_TMP_DIR="${'$'}TMPDIR"
        # Operit2 用 Command::env_remove 剔除它；这里等价地对子进程 unset。
        unset PROOT_NO_SECCOMP
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export USE_CHROOT=${if (prefs.getBoolean("chroot_enabled", false)) "1" else "0"}
        export DISTRO_LABEL=${shQuote(runtime.label)}
        export DISTRO_SHELL=${shQuote(runtime.shellPath)}
        export DISTRO_IS_ALPINE=${if (runtime.packageSource == PackageSourceSpec.AlpineRepositories) "1" else "0"}
        # 登录命令：Alpine 是 `/bin/sh -l`（ash 没有 --rcfile），
        # Ubuntu 系保持原有的 `/bin/bash --rcfile ... -i`。
        export DISTRO_LOGIN_CMD=${shQuote(runtime.buildLoginCommand())}
        export OPERIT_UID=$(id -u)
        export OPERIT_GID=$(id -g)
        export OPERIT_GROUPS=$(id -G | tr ' ' ',')
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        rootfs_diag_lines(){
          # 只读诊断：收集「为什么这个 rootfs 不可用」的原始事实。
          # 不改任何文件；任何一条命令失败都只产生一行文本，不会中断终端。
          diag_busybox="${'$'}UBUNTU_PATH/bin/busybox"
          diag_shell="${'$'}UBUNTU_PATH/bin/sh"
          echo "diag: uid=${'$'}(id -u 2>/dev/null):${'$'}(id -g 2>/dev/null) user=${'$'}(id -un 2>/dev/null)"
          echo "diag: selinux=${'$'}(cat /proc/self/attr/current 2>/dev/null)"
          echo "diag: enforce=${'$'}(cat /sys/fs/selinux/enforce 2>/dev/null)"
          echo "diag: rootfs=${'$'}(ls -ld "${'$'}UBUNTU_PATH" 2>&1)"
          echo "diag: bin=${'$'}(ls -ld "${'$'}UBUNTU_PATH/bin" 2>&1)"
          echo "diag: busybox=${'$'}(ls -l "${'$'}diag_busybox" 2>&1)"
          echo "diag: busybox_ctx=${'$'}(ls -lZ "${'$'}diag_busybox" 2>&1)"
          echo "diag: shell=${'$'}(ls -l "${'$'}diag_shell" 2>&1)"
          echo "diag: etc=${'$'}(ls -ld "${'$'}UBUNTU_PATH/etc" 2>&1)"
          echo "diag: mode=${'$'}(command -v stat >/dev/null 2>&1 && stat -c 'a=%a u=%u g=%g A=%A' "${'$'}diag_busybox" 2>&1 || echo '<stat unavailable>')"
          echo "diag: tests f=${'$'}([ -f "${'$'}diag_busybox" ] && echo y || echo n) r=${'$'}([ -r "${'$'}diag_busybox" ] && echo y || echo n) x=${'$'}([ -x "${'$'}diag_busybox" ] && echo y || echo n)"
          echo "diag: data_mount=${'$'}(grep ' /data ' /proc/mounts 2>/dev/null | head -1)"
          echo "diag: staging=${'$'}(ls -ld "${'$'}{UBUNTU_PATH}.install.tmp" 2>&1)"
          echo "diag: lock=${'$'}(ls -ld "${'$'}{UBUNTU_PATH}.install.lock" 2>&1)"
          echo "diag: parent=${'$'}(ls -ld "${'$'}{UBUNTU_PATH%/*}" 2>&1)"
        }
        rootfs_diag_log(){
          mkdir -p "${'$'}HOME/logs" 2>/dev/null
          {
            echo "--- ${'$'}(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null) distro=${'$'}DISTRO_LABEL path=${'$'}UBUNTU_PATH ---"
            rootfs_diag_lines
          } >> "${'$'}HOME/logs/rootfs-diag.log" 2>&1
        }
        print_rootfs_diag(){
          rootfs_diag_lines | while IFS= read -r diag_line; do
            progress_echo "${'$'}diag_line"
          done
        }
        write_default_dns(){
          target_file="${'$'}1"
          if [ -z "${'$'}target_file" ]; then
            return 1
          fi
          cat > "${'$'}target_file" <<'EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 223.5.5.5
nameserver 223.6.6.6
nameserver 119.29.29.29
nameserver 180.76.76.76
EOF
        }
        can_access_bind_source(){
          bind_source="${'$'}1"
          if [ -z "${'$'}bind_source" ]; then
            return 1
          fi
          if [ ! -e "${'$'}bind_source" ] && [ ! -L "${'$'}bind_source" ]; then
            return 1
          fi
          "${'$'}BIN/busybox" ls -Ld "${'$'}bind_source" >/dev/null 2>&1
        }
        append_proot_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi
          if [ -z "${'$'}bind_target" ] || [ "${'$'}bind_source" = "${'$'}bind_target" ]; then
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source"
          else
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source:${'$'}bind_target"
          fi
        }
        run_proot_binary(){
          LD_LIBRARY_PATH= "${'$'}BIN/proot" "${'$'}@"
        }
        exec_proot_binary(){
          LD_LIBRARY_PATH= exec "${'$'}BIN/proot" "${'$'}@"
        }
        # 旧路径（SSH / chroot）专用的启动前核对：只列事实，不再实跑 proot 探针。
        #
        # 为什么删掉探针：它和真正的启动各自维护一份 argv（`run_proot_probe` vs
        # `exec_proot_binary`），两份漂移后会出现「探针绿、启动后静默死亡」。
        # local 终端的 proot 现在由 Kotlin 侧 ProotCommand 构造并直接 execve，
        # 这里只剩 SSH / chroot 这一条可选路径，参数与 ProotCommand 保持一致。
        proot_launch_argv(){
          printf '%s' "-0 --link2symlink -r ${'$'}UBUNTU_PATH -b /proc -b /dev -b /sys -b /dev/pts -b /sdcard -b /storage -b /data/local/tmp:/data/local/tmp ${'$'}PROOT_BIND_ARGS -w /root"
        }
        print_proot_launch_facts(){
          echo "distro=${'$'}DISTRO_LABEL path=${'$'}UBUNTU_PATH"
          echo "proot=${'$'}BIN/proot loader=${'$'}{PROOT_LOADER:-<unset>} tmp=${'$'}{PROOT_TMP_DIR:-<unset>}"
          echo "proot_no_seccomp=${'$'}{PROOT_NO_SECCOMP:-<unset>}"
          echo "proot_argv=$(proot_launch_argv)"
        }
        resolve_proot_binary(){
          PROOT_BIND_ARGS=""
          if [ ! -e "${'$'}BIN/proot" ]; then
            echo "proot binary missing: ${'$'}BIN/proot"
            print_proot_launch_facts
            return 1
          fi
          if [ ! -e "${'$'}PROOT_LOADER" ]; then
            echo "proot loader missing: ${'$'}{PROOT_LOADER:-<unset>}"
            print_proot_launch_facts
            return 1
          fi
          return 0
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          # 内置 Alpine 的 rootfs 由 Kotlin 侧（TerminalRootfsInstaller.installFromAsset）
          # 在 initializeEnvironment 里装好，这里不需要、也不能再跑一遍
          # Ubuntu 的 tar.xz 解压流程——那会去找一个根本不存在的归档。
          if [ "${'$'}DISTRO_IS_ALPINE" = "1" ]; then
            # 可用性判据：proot 读取 rootfs 需要的实体是否存在。
            #
            # 不用 [ -x ] / Java canExecute()：
            # - 宿主执行的是 ${'$'}BIN/proot（/data/app/.../lib 下，可执行），
            #   rootfs 里的文件从不被宿主 execve，proot 自己读取 ELF 后映射执行；
            # - Android 10+ 对 targetSdk>=29 禁止应用 execve 私有目录文件（W^X），
            #   exec 位在内核策略下可能返回假，却不影响 proot。
            # 真正「能否进入终端」的判据是 proot 实跑（本地终端由宿主 ProotCommand
            # 直接 exec；这条脚本路径只服务 SSH / chroot）。
            if [ ! -f "${'$'}UBUNTU_PATH/bin/busybox" ] ||
               [ ! -e "${'$'}UBUNTU_PATH/bin/sh" ] ||
               [ ! -d "${'$'}UBUNTU_PATH/etc" ]; then
              progress_echo "${'$'}DISTRO_LABEL rootfs incomplete at ${'$'}UBUNTU_PATH"
              # 首屏诊断：直接出现在用户截图里，是安装/权限失败时唯一的运行时证据。
              print_rootfs_diag
              rootfs_diag_log
              return 1
            fi
            # 结构完整。exec 位只记录，不参与判定。
            rootfs_diag_log
            return 0
          fi

          OK_FILE="${'$'}UBUNTU_PATH/.operit_installed_ok"
          LOCK_DIR="${'$'}UBUNTU_PATH.install.lock"
          LOCK_PID_FILE="${'$'}LOCK_DIR/pid"
          TMP_DIR="${'$'}UBUNTU_PATH.install.tmp"

          UBUNTU_PARENT="${'$'}{UBUNTU_PATH%/*}"
          mkdir -p "${'$'}UBUNTU_PARENT" 2>/dev/null

          attempt=0
          while true; do
            if mkdir "${'$'}LOCK_DIR" 2>/dev/null; then
              echo "${'$'}${'$'}" > "${'$'}LOCK_PID_FILE" 2>/dev/null || true
              break
            fi

            if [ -f "${'$'}LOCK_PID_FILE" ]; then
              lock_pid=${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)
              if [ -z "${'$'}lock_pid" ]; then
                if [ "${'$'}attempt" -gt 2 ]; then
                  rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                  continue
                fi
              elif ! kill -0 "${'$'}lock_pid" 2>/dev/null; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            else
              if [ "${'$'}attempt" -gt 2 ]; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            fi

            attempt=${'$'}((attempt + 1))
            if [ "${'$'}attempt" -gt 120 ]; then
              progress_echo "Ubuntu install lock timeout"
              return 1
            fi
            sleep 1
          done

          cleanup_install(){
            rm -rf "${'$'}TMP_DIR" 2>/dev/null
            rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          }
          trap 'cleanup_install' EXIT INT TERM

          if [ -f "${'$'}OK_FILE" ]; then
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          else
            if [ -f "${'$'}UBUNTU_PATH/etc/issue.net" ]; then
              echo "ok" > "${'$'}OK_FILE" 2>/dev/null || true
              VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
              progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
            else
              progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
              if [ ! -f "${'$'}HOME/${'$'}UBUNTU" ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -rf "${'$'}TMP_DIR" 2>/dev/null
              mkdir -p "${'$'}TMP_DIR" 2>/dev/null
              progress_echo "Extracting Ubuntu rootfs..."
              busybox tar xf "${'$'}HOME/${'$'}UBUNTU" -C "${'$'}TMP_DIR"/ >/dev/null 2>&1
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              echo "Extraction complete"
              if [ -d "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" ]; then
                mv "${'$'}TMP_DIR/${'$'}UBUNTU_NAME"/* "${'$'}TMP_DIR"/ 2>/dev/null
                rm -rf "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" 2>/dev/null
              fi

              mkdir -p "${'$'}TMP_DIR/root" 2>/dev/null
              echo 'export ANDROID_DATA=/home/' >> "${'$'}TMP_DIR/root/.bashrc"
              mkdir -p "${'$'}TMP_DIR/etc" 2>/dev/null
              write_default_dns "${'$'}TMP_DIR/etc/resolv.conf"
              echo "ok" > "${'$'}TMP_DIR/.operit_installed_ok" 2>/dev/null || true

              rm -rf "${'$'}UBUNTU_PATH" 2>/dev/null
              mv "${'$'}TMP_DIR" "${'$'}UBUNTU_PATH" 2>/dev/null
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -f "${'$'}HOME/${'$'}UBUNTU" 2>/dev/null
            fi
          fi

          mkdir -p ${'$'}UBUNTU_PATH/etc 2>/dev/null
          write_default_dns "${'$'}UBUNTU_PATH/etc/resolv.conf"

          rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          trap - EXIT INT TERM
        }
        """.trimIndent()

        val configureSources = """
        configure_sources(){
          if [ "${'$'}DISTRO_IS_ALPINE" = "1" ]; then
            # Alpine 用 /etc/apk/repositories：一行一个仓库 URL，没有 suite 概念。
            mkdir -p "${'$'}UBUNTU_PATH/etc/apk" 2>/dev/null
            cat <<'EOF' > "${'$'}UBUNTU_PATH/etc/apk/repositories"
        # From Operit Settings - ${aptSource.name}
        ${aptSource.url}/latest-stable/main
        ${aptSource.url}/latest-stable/community
        EOF
          else
            # 配置APT源
            cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From Operit Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF
          fi

          # 配置Pip/Uv源
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/pip 2>/dev/null
          echo '[global]' > ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          echo 'index-url = ${pipSource.url}' >> ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/uv 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > ${'$'}UBUNTU_PATH/root/.config/uv/uv.toml
          
          # 配置NPM源
          mkdir -p ${'$'}UBUNTU_PATH/root 2>/dev/null
          echo 'registry=${npmSource.url}' > ${'$'}UBUNTU_PATH/root/.npmrc
        }
        """.trimIndent()

        val fixPermissions = """
        fix_permissions(){
          echo "Fixing permissions..."
          # Fix "cannot find name for group ID" warnings
          # Append Android groups to Ubuntu /etc/group
          current_groups=$(id -G)
          for gid in ${'$'}current_groups; do
            if ! grep -q ":${'$'}gid:" ${'$'}UBUNTU_PATH/etc/group; then
              echo "android_group_${'$'}gid:x:${'$'}gid:" >> ${'$'}UBUNTU_PATH/etc/group
            fi
          done
          echo "Permissions fixed."
        }
        """.trimIndent()

        // 读取共享tmp设置
        val sharedTmpEnabled = prefs.getBoolean("shared_tmp_enabled", true)
        val prootBindSetup = PRootMountMapping.buildRuntimeBindMounts(
            homeDir = homeDir,
            appDataDir = operitDataDir,
            packageName = operitPackage,
            chrootEnabled = false
        ).toMutableList().apply {
            if (sharedTmpEnabled) {
                add(4, PRootBindMount(tmpDir, "/dev/shm"))
            }
        }.joinToString(separator = "\n") { mount ->
            "          append_proot_bind_arg \"${mount.sourcePath}\" \"${mount.targetPath}\""
        }
        
        // 初始化脚本通过独立环境变量传入，避免嵌套 shell 展开路径、引号和美元符号。
        val localSessionInit = localSessionInitializer(context)
        val quotedLocalSessionInit = "'" + localSessionInit.replace("'", "'\"'\"'") + "'"
        val loginUbuntu = """
        login_ubuntu(){
          COMMAND_TO_EXEC="$1"
          OPERIT_LOCAL_SESSION_INIT=$quotedLocalSessionInit
          AUTOMATION_ENV=""
          if [ "${'$'}OPERIT_AUTOMATION" = "1" ]; then
            AUTOMATION_ENV="PAGER=cat GIT_PAGER=cat GIT_TERMINAL_PROMPT=0"
          fi
          if [ -z "${'$'}COMMAND_TO_EXEC" ]; then
            # Alpine 的 shell 是 busybox ash，不接受 bash 的 --rcfile；
            # DISTRO_LOGIN_CMD 已在 Kotlin 侧按发行版拼好。
            COMMAND_TO_EXEC="${'$'}DISTRO_LOGIN_CMD"
          fi

          # Setup fake sysdata
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            export INSTALLED_ROOTFS_DIR=$(dirname "${'$'}UBUNTU_PATH")
            export distro_name=$(basename "${'$'}UBUNTU_PATH")
            
            if [ -f "${'$'}HOME/setup_fake_sysdata.sh" ]; then
                source "${'$'}HOME/setup_fake_sysdata.sh"
                setup_fake_sysdata
            fi
          fi

          # 使用 proot 直接进入解压的 Ubuntu 根文件系统。
          # - 清理并设置 PATH，避免继承宿主 PATH 造成命令找不到或混用 busybox。
          # - 绑定常见伪文件系统、外部存储与 Operit 应用沙箱，保障交互和软件包管理工作正常。
          # 在 proot 环境中创建必要的目录
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitUserDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitLegacyDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$homeDir" 2>/dev/null

          if [ "${'$'}USE_CHROOT" = "1" ]; then
            CMD_FILE="${'$'}TMPDIR/command_to_exec"
            printf "%s" "${'$'}COMMAND_TO_EXEC" > "${'$'}CMD_FILE" 2>/dev/null || true
            INIT_FILE="${'$'}TMPDIR/local_session_init"
            printf "%s" "${'$'}OPERIT_LOCAL_SESSION_INIT" > "${'$'}INIT_FILE" || return 1
            CHROOT_WRAPPER="${'$'}TMPDIR/operit_chroot_wrapper.sh"
            cat > "${'$'}CHROOT_WRAPPER" <<'EOF'
        BIN="$1"
        UBUNTU_PATH="$2"
        CMD_FILE="$3"
        HOME_DIR="$4"
        OPERIT_UID="$5"
        OPERIT_GID="$6"
        OPERIT_GROUPS="$7"
        INIT_FILE="$8"
        AUTOMATION_ENV=""
        if [ "$9" = "1" ]; then AUTOMATION_ENV="PAGER=cat GIT_PAGER=cat GIT_TERMINAL_PROMPT=0"; fi
        mount_bind(){
          source_path="${'$'}1"
          target_path="${'$'}2"
          if ! "${'$'}BIN/busybox" mount --bind "${'$'}source_path" "${'$'}target_path"; then
            echo "chroot bind mount failed: ${'$'}source_path -> ${'$'}target_path" >&2
            return 1
          fi
          if ! "${'$'}BIN/busybox" mountpoint -q "${'$'}target_path"; then
            echo "chroot bind mount verification failed: ${'$'}target_path" >&2
            return 1
          fi
          echo "chroot bind mount active: ${'$'}target_path"
        }
        cleanup_mounts(){
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$currentUserDataRootPath" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/data/data" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/data/local/tmp" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sdcard" 2>/dev/null || true
        }
        cleanup_mounts

        "${'$'}BIN/busybox" mkdir -p "${'$'}UBUNTU_PATH/proc" "${'$'}UBUNTU_PATH/sys" "${'$'}UBUNTU_PATH/dev" "${'$'}UBUNTU_PATH/dev/pts" "${'$'}UBUNTU_PATH/sdcard" "${'$'}UBUNTU_PATH$currentUserDataRootPath" "${'$'}UBUNTU_PATH/data/data" "${'$'}UBUNTU_PATH/data/local/tmp" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null
        "${'$'}BIN/busybox" mount -t proc proc "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
        mount_bind /dev "${'$'}UBUNTU_PATH/dev"
        mount_bind /sys "${'$'}UBUNTU_PATH/sys"
        mount_bind /dev/pts "${'$'}UBUNTU_PATH/dev/pts"
        mount_bind "$currentEmulatedStoragePath" "${'$'}UBUNTU_PATH/sdcard"
        mount_bind "$currentUserDataRootPath" "${'$'}UBUNTU_PATH$currentUserDataRootPath"
        mount_bind /data/data "${'$'}UBUNTU_PATH/data/data"
        mount_bind /data/local/tmp "${'$'}UBUNTU_PATH/data/local/tmp"
        mount_bind "${'$'}HOME_DIR" "${'$'}UBUNTU_PATH${'$'}HOME_DIR"
        COMMAND_TO_EXEC="$(cat "${'$'}CMD_FILE" 2>/dev/null)"
        OPERIT_LOCAL_SESSION_INIT="$(cat "${'$'}INIT_FILE")" || exit 1
        "${'$'}BIN/busybox" chroot "${'$'}UBUNTU_PATH" /usr/bin/env -i ${'$'}AUTOMATION_ENV HOME=/root TERM=xterm-256color LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "COMMAND_TO_EXEC=${'$'}COMMAND_TO_EXEC" "OPERIT_LOCAL_SESSION_INIT=${'$'}OPERIT_LOCAL_SESSION_INIT" "OPERIT_UID=${'$'}OPERIT_UID" "OPERIT_GID=${'$'}OPERIT_GID" "OPERIT_GROUPS=${'$'}OPERIT_GROUPS" "${'$'}DISTRO_SHELL" -lc 'eval "${'$'}OPERIT_LOCAL_SESSION_INIT" || exit ${'$'}?; echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; umask 0002; if [ -n "${'$'}OPERIT_GID" ]; then chown 0:"${'$'}OPERIT_GID" /root 2>/dev/null || true; chmod 2775 /root 2>/dev/null || true; fi; eval "${'$'}COMMAND_TO_EXEC"'
        ret=${'$'}?
        if [ -n "${'$'}OPERIT_UID" ] && [ -n "${'$'}OPERIT_GID" ]; then
          for apt_path in /var/lib/apt/lists /var/cache/apt; do
            if [ -e "${'$'}UBUNTU_PATH${'$'}apt_path" ] && ! "${'$'}BIN/busybox" chown -R "${'$'}OPERIT_UID:${'$'}OPERIT_GID" "${'$'}UBUNTU_PATH${'$'}apt_path" 2>/dev/null; then
              echo "Warning: failed to restore proot ownership for ${'$'}apt_path" >&2
            fi
          done
        fi
        cleanup_mounts
        exit ${'$'}ret
        EOF
            chmod 700 "${'$'}CHROOT_WRAPPER" 2>/dev/null || true
            exec su -c "sh \"${'$'}CHROOT_WRAPPER\" \"${'$'}BIN\" \"${'$'}UBUNTU_PATH\" \"${'$'}CMD_FILE\" \"${homeDir}\" \"${'$'}OPERIT_UID\" \"${'$'}OPERIT_GID\" \"${'$'}OPERIT_GROUPS\" \"${'$'}INIT_FILE\" \"${'$'}OPERIT_AUTOMATION\""
          fi
          if ! resolve_proot_binary; then
            return 1
          fi
          $prootBindSetup
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            if [ ! -e /proc/stat ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.stat" "/proc/stat"; fi
            if [ ! -e /proc/loadavg ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.loadavg" "/proc/loadavg"; fi
            if [ ! -e /proc/uptime ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.uptime" "/proc/uptime"; fi
            if [ ! -e /proc/version ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.version" "/proc/version"; fi
            if [ ! -e /proc/vmstat ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.vmstat" "/proc/vmstat"; fi
            if [ ! -e /proc/sys/kernel/cap_last_cap ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_entry_cap_last_cap" "/proc/sys/kernel/cap_last_cap"; fi
            if [ ! -e /proc/sys/fs/inotify/max_user_watches ]; then append_proot_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_inotify_max_user_watches" "/proc/sys/fs/inotify/max_user_watches"; fi
          fi
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS
          else
            set --
          fi
          # 固定 argv：与宿主 ProotCommand 同一份参数（-0 / --link2symlink / -r / -b / -w），
          # 不再有「link2symlink 探测成功才加」的第二份分支。
          exec_proot_binary \
            -0 \
            --link2symlink \
            -r "${'$'}UBUNTU_PATH" \
            -b /proc \
            -b /dev \
            -b /sys \
            -b /dev/pts \
            -b /sdcard \
            -b /storage \
            -b /data/local/tmp:/data/local/tmp \
            "${'$'}@" \
            -w /root \
            /usr/bin/env -i \
              ${'$'}AUTOMATION_ENV \
              HOME=/root \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
              OPERIT_LOCAL_SESSION_INIT="${'$'}OPERIT_LOCAL_SESSION_INIT" \
              "${'$'}DISTRO_SHELL" -lc 'eval "${'$'}OPERIT_LOCAL_SESSION_INIT" || exit ${'$'}?; echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
        }
        """.trimIndent()

        val sshShell = """
        ssh_shell(){
          set -x
          install_ubuntu
          configure_sources
          fix_permissions
          sleep 1
          bump_progress
          
          # 先进入当前发行版环境，然后连接SSH
          # 当SSH退出时，用户会回到本地 shell
          login_ubuntu 'echo "Connecting to SSH..."; '"${'$'}SSH_COMMAND"'; echo "SSH connection closed. You are now in local '"${'$'}DISTRO_LABEL"' terminal."; '"${'$'}DISTRO_LOGIN_CMD"
        }
        """.trimIndent()

        return """
        $common
        $installUbuntu
        $configureSources
        $fixPermissions
        $loginUbuntu
        $sshShell
        clear_lines
        start_shell(){
          install_ubuntu
          configure_sources
          fix_permissions
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    fun closeTerminalSession(sessionId: String) {
        closingSessions.add(sessionId)
        sessionManager.getSession(sessionId)?.exitJob?.cancel()
        sessionInputs.remove(sessionId)?.cancel()
        inputJobs.remove(sessionId)?.cancel()
        sessionProcesses[sessionId]?.let { destroyAndReapSessionProcess(sessionId, it) }
        coroutineScope.launch {
            runCatching { terminalProvider?.closeSession(sessionId) }
                .onFailure { Log.w(TAG, "Error closing session via provider: $sessionId", it) }
        }
        activeSessions.remove(sessionId)
    }

    private fun writeStartScriptAtomically() {
        val contents = generateStartScript().replace("\r\n", "\n").replace("\r", "\n")
        val staged = File.createTempFile("common.sh.", ".pending", filesDir)
        try {
            staged.outputStream().use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(staged.absolutePath, File(filesDir, "common.sh").absolutePath)
        } finally {
            staged.delete()
        }
    }

    private fun destroyAndReapSessionProcess(sessionId: String, process: Process) {
        lateinit var job: kotlinx.coroutines.Job
        job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(NonCancellable) {
                    val pty = sessionPtys.remove(sessionId)
                    if (pty != null) {
                        if (pty.pid > 0 && !pty.terminateSession(2_000L)) {
                            Log.w(TAG, "Terminal descendants were not confirmed stopped: $sessionId")
                        }
                        pty.destroy()
                    } else {
                        process.destroy()
                    }
                    val exited = withTimeoutOrNull(2_000L) {
                        while (process.isAlive) delay(25L)
                        true
                    } == true
                    if (exited) sessionProcesses.remove(sessionId, process)
                    else Log.w(TAG, "Terminal process did not stop: $sessionId")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Terminal resource cleanup failed: $sessionId", error)
            } finally {
                processReapJobs.remove(sessionId, job)
            }
        }
        if (processReapJobs.putIfAbsent(sessionId, job) == null) job.start() else job.cancel()
    }

    internal fun closeSessionWriter(session: TerminalSessionData) {
        coroutineScope.launch(Dispatchers.IO) {
            processReapJobs[session.id]?.join()
            runCatching { session.sessionWriter?.close() }
                .onFailure { Log.w(TAG, "Failed to close terminal writer: ${session.id}", it) }
        }
    }

    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem

        // 发出命令开始执行事件
        commandEventChannel.trySend(CommandExecutionEvent(
            commandId = newCommandItem.id, sessionId = session.id, outputChunk = "", isCompleted = false,
        ))
    }

    fun prepareForMaintenance() {
        // 释放 provider 连接
        kotlinx.coroutines.runBlocking {
            terminalProvider?.disconnect()
            Log.d(TAG, "Disconnected terminal provider")
            
            // 停止SSHD服务器
            sshdServerManager.stopServer()
            Log.d(TAG, "Stopped SSHD server")
        }
        terminalProvider = null

        // 删除 Ubuntu 前必须等待本地 PRoot/终端进程退出，避免清理时仍有文件被重新创建。
        val processes = activeSessions.values.map { it.process }
        activeSessions.keys.toList().forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
        processes.forEach { process ->
            if (process.isAlive) {
                runCatching {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Terminal process did not exit before maintenance cleanup")
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Failed waiting for terminal process during maintenance", error)
                }
            }
        }
        sessionManager.cleanup()
        // 环境删除前等待异步退役结束，不能只以状态表已清空判断资源已释放。
        val cleanupComplete = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(5_000L) {
                processReapJobs.values.toList().forEach { it.join() }
                true
            } == true
        }
        check(cleanupComplete && sessionProcesses.values.none { it.isAlive }) {
            "Terminal cleanup is incomplete; environment maintenance cannot continue"
        }
        Log.d(TAG, "Prepared terminal manager for maintenance.")
    }

    fun cleanup() {
        prepareForMaintenance()
        sessionInputs.values.forEach { it.cancel() }
        inputJobs.values.forEach { it.cancel() }
        commandEventChannel.close()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        val initialized = initializeEnvironment()
        if (!initialized) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.SHELL_START_FAILED,
                error = "Terminal environment initialization failed"
            )
        }

        return getTerminalProvider().executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
    }

    /**
     * 获取文件系统提供者
     * 
     * 根据配置返回对应的提供者（本地或SSH）
     * 如果配置了SSH且已连接，返回SSH的文件系统提供者（共享SFTP连接）
     * 否则返回本地文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider = kotlinx.coroutines.runBlocking {
        getTerminalProvider().getFileSystemProvider()
    }
    
    /**
     * 获取SSHD服务器管理器
     * 
     * 用于管理本地SSHD服务器（反向SSH隧道场景）
     */
    fun getSSHDServerManager(): SSHDServerManager = sshdServerManager
}

/**
 * 内置 Alpine rootfs 的安装状态。
 *
 * 状态迁移：`Idle -> Preparing -> Ready | Failed`。可重复进入 `Preparing`
 * （APK 升级换了 rootfs 内容时会重装），`Failed` 携带的是
 * `Throwable.toString()` 与堆栈，不是一句「未知错误」。
 */
sealed interface BundledEnvState {
    data object Idle : BundledEnvState
    data object Preparing : BundledEnvState
    data class Ready(val rootfsPath: String) : BundledEnvState
    data class Failed(val message: String) : BundledEnvState
}

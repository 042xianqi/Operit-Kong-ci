package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.data.TerminalProfile
import com.ai.assistance.operit.terminal.runtime.NativeTerminal
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 把 APK 内置的 rootfs 归档安装成一个可用的终端实例。
 *
 * **归档处理全部在 Rust 侧**（`runtime/crates/operit-terminal/src/install.rs`）：
 * gzip/xz/tar 解码、`..`/绝对路径拒绝、符号链接词法解析、ARM64 ELF 校验、
 * staging 目录 + 原子 rename、`.operit_installed_ok` / `.operit_rootfs_sha256` 标记、
 * `etc/resolv.conf` 兜底，都由 `NativeTerminal.installRootfs` 一次完成。
 * 这里只负责**把字节搞到磁盘上**（native 需要一条真实路径来流式做 SHA-256），
 * 然后转发 JSON。Kotlin 侧不再有任何 tar 解析、路径清洗或权限位代码。
 *
 * 幂等性仍然靠内容比对而非标记文件的「存在」：`.operit_rootfs_sha256` 里记录的是
 * 本次所装归档的 sha256，内容变了（APK 升级换了 rootfs）就重装。
 * `TerminalManager` 早期用 `.operit_installed_ok || etc/issue.net` 判断，
 * 那个 `|| etc/issue.net` 兜底会把「半解压但有 issue.net」误判成已安装。
 */
object TerminalRootfsInstaller {

    /** 记录已安装归档 sha256 的文件名（Rust `install.rs` 写同名同格式）。 */
    private const val INSTALLED_SHA_FILENAME = ".operit_rootfs_sha256"

    /** spool 报告进度的步长：4 MiB，避免一个 170 MB 归档刷爆日志。 */
    private const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024

    data class Progress(val state: String, val downloadedBytes: Long, val totalBytes: Long)

    /** [installFromAsset] 的结果：区分「新装」与「已是最新」。 */
    data class TerminalAssetInstallResult(
        val profile: TerminalProfile,
        val alreadyInstalled: Boolean,
    )

    /**
     * 从 APK assets 安装内置 rootfs（Alpine）。
     *
     * 流程只有三步：
     * 1. 若目标目录已就位且 sha 标记与 [expectedSha256] 一致，直接复用（不重解压 170 MB）；
     * 2. 把 [assetOpener] 的字节流 spool 到 rootfs 旁边的临时文件（native 要一条真实路径）；
     * 3. 调 [NativeTerminal.installRootfs]，成功后由 native 原子发布 rootfs。
     *
     * @param assetOpener 打开 asset 的字节流（传入而不是直接依赖 `Context`，
     *   这样 `modules/terminal` 不必依赖 Android Context，单测也能喂 `FileInputStream`）
     * @param instancesRoot 所有实例的父目录（`.../installed-rootfs`）
     * @param instanceId 新实例 id；同时作为目录名
     * @param displayName 展示名
     * @param expectedSha256 内置归档的 sha256；为 null 表示不校验（仅用于本地调试）
     * @param onProgress 进度回调；state ∈ extracting
     * @param isCancelled 取消检查，每 64 KiB 调用一次
     * @return 安装完成的实例描述；若已是最新版本则 `alreadyInstalled = true`
     */
    fun installFromAsset(
        assetOpener: () -> InputStream,
        instancesRoot: File,
        instanceId: String,
        displayName: String,
        expectedSha256: String?,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<TerminalAssetInstallResult> = runCatching {
        require(instanceId.isNotEmpty() && instanceId.all { it.isLetterOrDigit() || it == '_' }) {
            "Invalid terminal instance identifier: $instanceId"
        }
        require(displayName.isNotBlank() && !displayName.contains('\u0000')) {
            "Terminal instance name must not be blank or contain NUL"
        }

        instancesRoot.mkdirs()
        val root = instancesRoot.canonicalFile
        val target = File(root, instanceId)

        if (isUpToDate(target, expectedSha256)) {
            return@runCatching TerminalAssetInstallResult(
                profile = profileOf(instanceId = instanceId, displayName = displayName, target = target, createdAtMillis = target.lastModified()),
                alreadyInstalled = true,
            )
        }

        onProgress(Progress("extracting", 0L, 0L))
        val spool = File(root, ".$instanceId-rootfs.tar.gz.bin")
        // 上次异常退出留下的半截归档不可信，重来。
        if (spool.exists()) spool.delete()

        try {
            val spooledBytes = spoolAsset(assetOpener, spool, onProgress, isCancelled)
            checkCancelled(isCancelled)
            onProgress(Progress("extracting", spooledBytes, spooledBytes))

            installViaNative(
                archive = spool,
                rootfs = target,
                instanceId = instanceId,
                displayName = displayName.trim(),
                expectedSha256 = expectedSha256,
            )

            TerminalAssetInstallResult(
                profile = profileOf(instanceId = instanceId, displayName = displayName, target = target, createdAtMillis = System.currentTimeMillis()),
                alreadyInstalled = false,
            )
        } finally {
            // 归档已经解完（或失败），不留 170 MB 缓存。
            spool.delete()
        }
    }

    // ---------------------------------------------------------------- 与 native 的边界

    /**
     * 从任意归档流安装一个**可下载发行版**（Ubuntu / Debian / Fedora）。
     *
     * 与 [installFromAsset] 的差别只有三点，其余（spool → native → 原子发布）完全共用：
     * - `alpine = false`：这些发行版没有 `bin/busybox`，Rust 侧只校验 `bin/sh` 与 `etc/`；
     * - 允许连字符 id（`debian-13`），与 `install.rs::validate_spec` 的字符集一致；
     * - rootfs 目录名与 `distributionId` 分开：Ubuntu 的目录名沿用历史的 `ubuntu`。
     */
    fun installFromArchive(
        archiveOpener: () -> InputStream,
        instancesRoot: File,
        distributionId: String,
        rootfsDirectoryName: String,
        displayName: String,
        expectedSha256: String,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<TerminalAssetInstallResult> = runCatching {
        require(
            rootfsDirectoryName.isNotEmpty() &&
                rootfsDirectoryName.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) { "Invalid terminal instance identifier: $rootfsDirectoryName" }
        require(displayName.isNotBlank() && !displayName.contains('\u0000')) {
            "Terminal instance name must not be blank or contain NUL"
        }

        instancesRoot.mkdirs()
        val root = instancesRoot.canonicalFile
        val target = File(root, rootfsDirectoryName)

        if (isUpToDate(target, expectedSha256)) {
            return@runCatching TerminalAssetInstallResult(
                profile =
                    profileOf(
                        instanceId = rootfsDirectoryName,
                        distributionId = distributionId,
                        displayName = displayName,
                        target = target,
                        createdAtMillis = target.lastModified(),
                    ),
                alreadyInstalled = true,
            )
        }

        onProgress(Progress("extracting", 0L, 0L))
        val spool = File(root, ".$rootfsDirectoryName-rootfs.tar.gz.bin")
        if (spool.exists()) spool.delete()

        try {
            val spooledBytes = spoolAsset(archiveOpener, spool, onProgress, isCancelled)
            checkCancelled(isCancelled)
            onProgress(Progress("extracting", spooledBytes, spooledBytes))

            installViaNative(
                archive = spool,
                rootfs = target,
                instanceId = rootfsDirectoryName,
                displayName = displayName.trim(),
                expectedSha256 = expectedSha256,
                alpine = false,
            )

            TerminalAssetInstallResult(
                profile =
                    profileOf(
                        instanceId = rootfsDirectoryName,
                        distributionId = distributionId,
                        displayName = displayName,
                        target = target,
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                alreadyInstalled = false,
            )
        } finally {
            spool.delete()
        }
    }

    // ---------------------------------------------------------------- 与 native 的边界

    private fun installViaNative(
        archive: File,
        rootfs: File,
        instanceId: String,
        displayName: String,
        expectedSha256: String?,
        alpine: Boolean = true,
    ) {
        val request = buildJsonObject {
            put("archivePath", archive.absolutePath)
            put("rootfs", rootfs.absolutePath)
            put("instanceId", instanceId)
            put("displayName", displayName)
            if (expectedSha256 != null) put("expectedSha256", expectedSha256)
            // alpine=true 时 native 额外要求 `bin/busybox`（内置 Alpine 的判据）。
            put("alpine", alpine)
        }.toString()

        val response = NativeTerminal.installRootfs(request)
        val payload = runCatching { Json.parseToJsonElement(response).jsonObject }
            .getOrElse { throw IOException("Rootfs install returned malformed JSON: $response") }

        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) throw IOException("Rootfs install failed: $error")

        val installed = payload["installed"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!installed) throw IOException("Rootfs install reported no publication: $response")
    }

    /**
     * 把 asset 流写到 [target]，边写边报进度。
     *
     * `FileDescriptor.sync()` 不是可选的：Rust 侧立刻按路径读回这段字节，
     * 页缓存里的内容对同一进程可见，但崩溃/被杀的窗口里会留下看起来完整却截断的文件——
     * native 的 sha 校验能兜住（sha 不匹配 → 不发布），这里 sync 只是少一次无谓重装。
     */
    private fun spoolAsset(
        assetOpener: () -> InputStream,
        target: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        target.parentFile?.mkdirs()
        var written = 0L
        var lastReport = 0L
        assetOpener().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    written += count
                    if (written - lastReport >= PROGRESS_STEP_BYTES) {
                        lastReport = written
                        onProgress(Progress("extracting", written, 0L))
                    }
                }
                output.flush()
                output.fd.sync()
            }
        }
        return written
    }

    // ---------------------------------------------------------------- 纯 Kotlin 判据

    /** 目标目录已就位，且（若给了期望 sha）标记内容与之一致。 */
    private fun isUpToDate(target: File, expectedSha256: String?): Boolean {
        if (!target.isDirectory) return false
        val expected = expectedSha256 ?: return false
        val marker = File(target, INSTALLED_SHA_FILENAME)
        if (!marker.isFile) return false
        val recorded = runCatching { marker.readText().trim() }.getOrNull()
        return recorded.equals(expected, ignoreCase = true)
    }

    private fun profileOf(
        instanceId: String,
        distributionId: String = instanceId,
        displayName: String,
        target: File,
        createdAtMillis: Long,
    ): TerminalProfile = TerminalProfile(
        id = instanceId,
        name = displayName.trim(),
        distributionId = distributionId,
        rootfsPath = target.absolutePath,
        installed = true,
        createdAtMillis = createdAtMillis,
    )

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw IOException("Terminal installation cancelled")
    }
}

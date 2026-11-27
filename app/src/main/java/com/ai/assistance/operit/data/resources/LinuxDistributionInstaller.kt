package com.ai.assistance.operit.data.resources

import android.content.Context
import com.ai.assistance.operit.data.resources.models.ResourceDownloadPhase
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.TerminalDistribution
import com.ai.assistance.operit.terminal.data.TerminalProfile
import com.ai.assistance.operit.terminal.utils.TerminalRootfsInstaller
import com.ai.assistance.operit.util.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 下载并安装一个可下载发行版（Ubuntu / Debian / Fedora）。
 *
 * 归档地址、sha256、字节数全部来自 [TerminalDistribution]，即 Operit2
 * `TerminalRootfs.rs::distributions()` 的同一份数据——这里**不新增任何 URL**。
 *
 * 解压、模式位、符号链接、staging + 原子发布仍然由 Rust（`install.rs`）负责；
 * 本对象只做三件事：把字节下到磁盘、校验 sha256、登记 [TerminalProfile]。
 */
object LinuxDistributionInstaller {

    private const val TAG = "LinuxDistributionInstaller"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /** 所有终端实例的父目录；与 `TerminalManager.alpineRootfsDir()` 保持一致。 */
    fun instancesRoot(context: Context): File =
        File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs")

    data class Progress(
        val phase: ResourceDownloadPhase,
        val doneBytes: Long,
        val totalBytes: Long,
    )

    /** 目标 rootfs 是否已经装好（判据在 Rust：`bin/sh` 可解析 + `etc/`）。 */
    fun isInstalled(context: Context, distribution: TerminalDistribution): Boolean =
        com.ai.assistance.operit.terminal.runtime.NativeTerminal.isRootfsInstalled(
            File(instancesRoot(context), distribution.rootfsDirectoryName).absolutePath
        )

    /**
     * 下载 + 安装 + 登记实例。
     *
     * 幂等：目标目录的 `.operit_rootfs_sha256` 与清单一致时直接复用，不重下 60 MB。
     */
    suspend fun install(
        context: Context,
        distribution: TerminalDistribution,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): TerminalProfile = withContext(Dispatchers.IO) {
        AppLogger.i(
            TAG,
            "install start: id=${distribution.id} dir=${distribution.rootfsDirectoryName} " +
                "bytes=${distribution.downloadBytes} url=${distribution.url}"
        )
        val archive = File(context.cacheDir, "distributions/${distribution.id}.tar.xz")
        try {
            if (!archiveIsVerified(archive, distribution)) {
                download(distribution, archive, onProgress, isCancelled)
            }
            val result =
                TerminalRootfsInstaller.installFromArchive(
                    archiveOpener = { FileInputStream(archive) },
                    instancesRoot = instancesRoot(context),
                    distributionId = distribution.id,
                    rootfsDirectoryName = distribution.rootfsDirectoryName,
                    displayName = distribution.name,
                    expectedSha256 = distribution.sha256,
                    onProgress = { phase ->
                        onProgress(Progress(ResourceDownloadPhase.INSTALLING, phase.downloadedBytes, distribution.downloadBytes))
                    },
                    isCancelled = isCancelled,
                ).getOrThrow()
            val manager = TerminalManager.getInstance(context)
            manager.upsertProfile(result.profile, makeDefault = false).getOrThrow()
            AppLogger.i(
                TAG,
                "install done: id=${distribution.id} rootfs=${result.profile.rootfsPath} " +
                    "alreadyInstalled=${result.alreadyInstalled}"
            )
            result.profile
        } finally {
            // 安装完（或失败）都不留 60 MB 缓存；下次失败重下比留脏缓存更可控。
            archive.delete()
        }
    }

    private fun archiveIsVerified(archive: File, distribution: TerminalDistribution): Boolean {
        if (archive.length() != distribution.downloadBytes) return false
        return sha256(archive).equals(distribution.sha256, ignoreCase = true)
    }

    private suspend fun download(
        distribution: TerminalDistribution,
        destination: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) = coroutineScope {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        part.delete()
        onProgress(Progress(ResourceDownloadPhase.CONNECTING, 0L, distribution.downloadBytes))
        val request =
            Request.Builder()
                .url(distribution.url)
                .header("User-Agent", "Operit Android distribution installer")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "Distribution download failed: HTTP ${response.code} from ${distribution.url}"
                )
            }
            val body = response.body ?: throw IOException("Distribution download has no body")
            val total = body.contentLength().takeIf { it > 0 } ?: distribution.downloadBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastReport = 0L
            FileOutputStream(part).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled() || !currentCoroutineContext().isActive) {
                            part.delete()
                            throw IOException("Distribution download cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        if (written - lastReport >= 256 * 1024) {
                            lastReport = written
                            onProgress(Progress(ResourceDownloadPhase.DOWNLOADING, written, total))
                        }
                    }
                }
                output.flush()
                output.fd.sync()
            }
            onProgress(Progress(ResourceDownloadPhase.DOWNLOADING, written, total))
            if (written != distribution.downloadBytes) {
                part.delete()
                throw IOException(
                    "Distribution archive size mismatch: expected=${distribution.downloadBytes} actual=$written"
                )
            }
            val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (!actual.equals(distribution.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException(
                    "Distribution archive SHA-256 mismatch: expected=${distribution.sha256} actual=$actual"
                )
            }
            if (!part.renameTo(destination)) {
                destination.delete()
                if (!part.renameTo(destination)) {
                    throw IOException("Cannot publish distribution archive: ${destination.absolutePath}")
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

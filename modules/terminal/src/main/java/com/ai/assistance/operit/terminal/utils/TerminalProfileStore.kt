package com.ai.assistance.operit.terminal.utils

import android.content.Context
import com.ai.assistance.operit.terminal.data.TerminalProfile
import com.ai.assistance.operit.terminal.data.TerminalProfileSettings
import java.io.File
import kotlinx.serialization.json.Json

/**
 * 终端实例注册表的持久化。
 *
 * 对标 Operit2 `TerminalProfiles.rs:42-61` 的 `fromBytes` / `toBytes`：
 * Rust 用 `serde_json`，这里用 **kotlinx.serialization**
 * （`modules/terminal/build.gradle.kts` 已 `implementation(libs.kotlinx.serialization)`，
 * 且应用了 `kotlin.serialization` 插件，不需要新增依赖）。
 *
 * 设计要点：
 * - **原子写**：先写 `.tmp` 再 rename，避免进程被杀时留下半份 JSON。
 * - **损坏不静默丢弃**：解析失败时把原文件重命名为 `.corrupt-<时间戳>`，
 *   然后返回 null。用户数据不会被覆盖掉，事后还能人工抢救。
 * - 与 Rust 的 `validate()` 对应：写入前一定校验，读取后也校验一次。
 */
class TerminalProfileStore(file: File) {

    constructor(context: Context) : this(File(context.filesDir, "terminal-profiles.json"))

    private val file = file
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** 读取注册表；文件不存在或已损坏返回 null（表示「首次启动」）。 */
    fun load(): TerminalProfileSettings? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val parsed = runCatching {
            json.decodeFromString(TerminalProfileSettings.serializer(), text)
        }.getOrNull()
        if (parsed == null || parsed.validate() != null) {
            // 保留原始内容，便于事后人工修复；不静默覆盖用户数据。
            runCatching {
                file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }
            return null
        }
        return parsed
    }

    /** 原子写入。校验失败直接抛错，绝不落盘半合法数据。 */
    fun save(settings: TerminalProfileSettings) {
        val error = settings.validate()
        require(error == null) { "Refusing to persist invalid terminal profile settings: $error" }

        val payload = json.encodeToString(TerminalProfileSettings.serializer(), settings)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // rename 失败（跨挂载点等极端情况）时退化为覆盖写。
            file.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /**
     * 首次安装时的默认注册表：只有一个 `ubuntu` 实例，指向 Kong 现有路径。
     *
     * 这与 Kong 当前行为完全一致（`TerminalManager.kt:965` 的
     * `usr/var/lib/proot-distro/installed-rootfs/ubuntu`），
     * 所以启用 profile 系统**不会改变现有用户的任何体验**。
     */
    fun defaultSettings(filesDir: File): TerminalProfileSettings {
        val legacyRootfs = File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        val installed = File(legacyRootfs, ".operit_installed_ok").isFile ||
            File(legacyRootfs, "etc/issue.net").isFile
        return TerminalProfileSettings(
            schemaVersion = TerminalProfileSettings.CURRENT_SCHEMA_VERSION,
            revision = 1,
            defaultProfileId = LEGACY_PROFILE_ID,
            profiles = listOf(
                TerminalProfile(
                    id = LEGACY_PROFILE_ID,
                    name = "Ubuntu",
                    distributionId = TerminalDistributions.UBUNTU_24_04.id,
                    rootfsPath = legacyRootfs.absolutePath,
                    installed = installed,
                    createdAtMillis = 0L,
                )
            ),
        )
    }

    companion object {
        /** 与 Kong 现有硬编码目录名一致，保证平滑迁移。 */
        const val LEGACY_PROFILE_ID = "ubuntu"
    }
}
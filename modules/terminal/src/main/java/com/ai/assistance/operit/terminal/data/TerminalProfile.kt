package com.ai.assistance.operit.terminal.data

import kotlinx.serialization.Serializable

/**
 * 一个设备本地的 Linux 环境实例（用户可创建多个、可切换）。
 *
 * 对标 Operit2 `core/crates/foundation/host-contract/src/TerminalProfiles.rs:8-18`
 * 的 `TerminalProfile`，但做了两点 Kotlin 化调整：
 *
 * 1. 用 `distributionId` 替代 Rust 的 `terminal` + `terminalType` 两个裸字符串。
 * 2. 把「rootfs 目录」显式建模成字段，而不是塞进 `options` map。
 *    Rust 侧用 `BTreeMap<String, String>` 是为了跨 FFI 序列化；Kong 同进程，不需要。
 */
@Serializable
data class TerminalProfile(
    /** 唯一标识，只允许 ASCII 字母数字与 `-` `_`。同时作为 rootfs 目录名。 */
    val id: String,
    /** 展示名，非空且不含 NUL。 */
    val name: String,
    /** 该实例对应的发行版 id（对应 [TerminalDistribution.id]）；自定义 rootfs 时为 null。 */
    val distributionId: String?,
    /** 实例的 rootfs 绝对路径。 */
    val rootfsPath: String,
    /** 是否已完成安装（rootfs 里有 `bin/bash` 与 `etc`）。 */
    val installed: Boolean,
    /** 创建时间，用于稳定排序。 */
    val createdAtMillis: Long,
) {
    /** 是否满足持久化前的合法性约束。 */
    fun isValid(): Boolean = isValidProfileId(id) && name.isNotBlank() && !name.contains('\u0000')
}

/**
 * 一个**可下载**的 Linux 发行版。
 *
 * 对标 Operit2 `TerminalProfiles.rs:143-152` 的 `TerminalDistribution`。
 * 字段与 Rust 侧一一对应，URL 与 sha256 直接沿用，便于交叉核对。
 */
data class TerminalDistribution(
    val id: String,
    /** 展示名。 */
    val name: String,
    /** 目标架构；当前只支持 aarch64。 */
    val architecture: String,
    val url: String,
    /** 归档的 SHA-256（小写十六进制）。 */
    val sha256: String,
    /** 归档字节数，用于下载进度与完整性判断。 */
    val downloadBytes: Long,
    /** 已知问题提示；为空表示无。 */
    val notice: String = "",
    /**
     * `installed-rootfs/` 下的目录名。
     *
     * 不直接用 [id]：Ubuntu 的历史目录是 `ubuntu`（`TerminalProfileStore` 的迁移逻辑、
     * `PathMapper` 的既有路径都指向它），换成 `ubuntu-24_04` 会让老用户的环境「消失」。
     */
    val rootfsDirectoryName: String = id,
) {
    /** 该发行版在当前设备上是否可用（架构匹配）。 */
    fun isSupportedOnCurrentDevice(): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return architecture == "aarch64" && abi in AARCH64_ABIS
    }

    private companion object {
        val AARCH64_ABIS = setOf("arm64-v8a")
    }
}

/**
 * 全部已注册实例 + 显式选定的默认实例。
 *
 * 对标 Operit2 `TerminalProfiles.rs:33-40` 的 `TerminalProfileSettings`。
 *
 * [revision] 每次修改递增，用于让 UI 侧判断「是否需要重新读取」；
 * [schemaVersion] 用于将来迁移，当前只接受 0 与 1。
 */
@Serializable
data class TerminalProfileSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 0,
    val defaultProfileId: String,
    val profiles: List<TerminalProfile> = emptyList(),
) {
    /** 当前默认实例；注册表为空或 id 失效时返回 null。 */
    fun defaultProfile(): TerminalProfile? = profiles.firstOrNull { it.id == defaultProfileId }

    /**
     * 把默认实例切到 [profileId]。
     *
     * 纯函数（不改磁盘），便于单测；落盘由调用方负责。
     * @return id 不存在时返回 null
     */
    fun withDefault(profileId: String): TerminalProfileSettings? {
        if (profiles.none { it.id == profileId }) return null
        return copy(revision = revision + 1, defaultProfileId = profileId)
    }

    /**
     * 移除 [profileId]。
     *
     * 纯函数。约束与 `removeProfile` 的契约一致：
     * 不能移除最后一个实例，也不能移除当前默认实例。
     * @return id 不存在时返回 null
     */
    fun withoutProfile(profileId: String): TerminalProfileSettings? {
        if (profiles.none { it.id == profileId }) return null
        require(profiles.size > 1) { "Cannot remove the last terminal profile" }
        require(defaultProfileId != profileId) {
            "Cannot remove the active terminal profile; switch first"
        }
        return copy(
            revision = revision + 1,
            profiles = profiles.filterNot { it.id == profileId },
        )
    }

    /**
     * 校验整份注册表。
     *
     * 逐条对应 `TerminalProfiles.rs:63-95` 的 `validate()`：
     * - schemaVersion 不能超过当前版本
     * - id 必须唯一、非空、只含 ASCII 字母数字与 `-` `_`
     * - name 非空、不含 NUL
     * - defaultProfileId 必须指向已存在的实例
     *
     * @return 错误说明；合法时返回 null
     */
    fun validate(): String? {
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            return "Unsupported terminal profile schema version: $schemaVersion"
        }
        if (profiles.isEmpty()) {
            return "Terminal profile registry must contain at least one profile"
        }
        val seen = mutableSetOf<String>()
        for (profile in profiles) {
            if (!isValidProfileId(profile.id) || !seen.add(profile.id)) {
                return "Terminal profile IDs must be unique ASCII identifiers: ${profile.id}"
            }
            if (profile.name.isBlank() || profile.name.contains('\u0000')) {
                return "Terminal profile name must not be blank or contain NUL"
            }
            if (profile.rootfsPath.isBlank() || profile.rootfsPath.contains('\u0000')) {
                return "Terminal profile rootfs path must not be blank or contain NUL: ${profile.id}"
            }
        }
        if (profiles.none { it.id == defaultProfileId }) {
            return "Default terminal profile must reference an existing profile"
        }
        return null
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * 实例 id 合法性：非空、只允许 `[A-Za-z0-9_-]`。
 *
 * 与 `TerminalRootfs.rs:104-107` 的 instanceId 校验一致（Rust 侧另允许下划线但禁 `-`，
 * 这里统一放行两者，因为 Kong 的 id 由本地生成，不来自归档路径）。
 * 之所以严格限制字符集：id 会被直接拼进文件系统路径。
 */
internal fun isValidProfileId(id: String): Boolean {
    if (id.isEmpty()) return false
    return id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
}
package com.ai.assistance.operit.terminal.data

import com.ai.assistance.operit.terminal.runtime.NativeTerminal
import java.io.File

/**
 * rootfs 内需要由**宿主**准备的文件与目录。
 *
 * 实现整体在 Rust：`guest.rs`（目录、文件、gid）与 `repair.rs`（旧 profile 修复），
 * 提示符策略在 `prompt.rs`。Kotlin 侧只是 JNI 转发，不保留任何文件逻辑，
 * 也不再自己拼接 `PROMPT_COMMAND` / `ENV` 内容——那些值由 native 返回。
 */
object GuestRootfsPrep {

    /** 提供给 `ENV` 的 ash 提示符文件（rootfs 内绝对路径，`prompt.rs` 写出）。 */
    const val ASH_PROMPT_PATH = "/etc/operit/prompt.sh"

    /** 一次待写入的 rootfs 内文件。 */
    data class GuestFile(val relativePath: String, val contents: String)

    /** native 准备结果：动作日志 + 该发行版需要的提示符环境变量。 */
    data class Prepared(
        val actions: List<String>,
        val environment: Map<String, String>,
    )

    /**
     * 准备 rootfs 的宿主侧目录与文件。幂等性与判定都在 Rust（`cargo test` 覆盖）。
     *
     * @param directories rootfs 内的路径（如 `/dev/pts`），缺失即创建。
     * @param files 需要写入的文件（包源等）。
     * @param groupIds 需要出现在 `/etc/group` 里的 Android gid。
     * @param distro `"alpine"` / `"debian"`，决定提示符环境与 profile 修复方式。
     * @param repairLegacy 是否清理旧版本写进 `/etc/profile` 的标记块。
     */
    fun prepare(
        rootfs: File,
        directories: Collection<String>,
        files: Collection<GuestFile>,
        groupIds: Collection<Int>,
        distro: String,
        repairLegacy: Boolean = true,
    ): Prepared {
        val prepared =
            NativeTerminal.prepare(
                NativeTerminal.RootfsPrepSpec(
                    rootfs = rootfs,
                    directories = directories,
                    files = files.map { NativeTerminal.GuestFileWrite(it.relativePath, it.contents) },
                    groupIds = groupIds,
                    distro = distro,
                    repairLegacy = repairLegacy,
                )
            )
        return Prepared(actions = prepared.actions, environment = prepared.environment)
    }
}

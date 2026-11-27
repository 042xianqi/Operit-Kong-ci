package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.runtime.NativeTerminal
import java.io.File

/**
 * 内置 Alpine 托管运行时的常量与资产路径。
 *
 * 为什么 Alpine 走 assets 而不是下载：Alpine 是**默认**环境，
 * 用户第一次进终端就应该能用，不能先等几十 MB 下载。
 * 而 Ubuntu/Debian/Fedora 是可选的「可下载发行版」，
 * 见 [TerminalDistributions]——两条路互不相干。
 */
object AlpineRuntime {

    /** 实例 id，同时是 `installed-rootfs` 下的目录名。 */
    const val PROFILE_ID = "alpine"

    /** 展示名。 */
    const val DISPLAY_NAME = "Alpine"

    /**
     * APK assets 里的归档路径。
     *
     * 目录里带 ABI 是因为将来若支持多 ABI，可以直接并列放
     * `arm64-v8a/` 与 `x86_64/`；当前 `modules/terminal/build.gradle.kts`
     * 的 `abiFilters` 只有 `arm64-v8a`，所以写死即可。
     */
    const val ASSET_PATH = "alpine/arm64-v8a/rootfs.tar.gz.bin"

    /** `AssetManager.open()` 用的路径（不带 `assets/` 前缀）。 */
    const val ASSET_SHA_PATH = "alpine/arm64-v8a/rootfs.tar.gz.bin.sha256"

    /** 归档内的 shell。Alpine 默认没有 bash，用它做存在性校验。 */
    const val PRIMARY_SHELL = "bin/sh"

    /**
     * 这个 rootfs 是否已经就位。
     *
     * 判据本身住在 Rust：`runtime/crates/operit-terminal/src/install.rs` 的
     * `is_installed`——`bin/busybox` 必须是普通文件、`bin/sh` 必须能解析、
     * `etc` 必须是目录。这里只是 JNI 转发，Kotlin 不再有第二份判据。
     * 半解压的目录会缺其中之一，从而被正确判为未安装。
     *
     * **判据里没有 exec 位（`canExecute()` / `[ -x ]`）**：
     * - 终端运行时从不 `execve()` rootfs 里的文件。宿主侧执行的是
     *   `/data/app/.../lib.../liboperit_proot.so`（proot 本体），proot 自己读取 guest ELF
     *   并在进程内映射执行 guest 命令；
     * - Android 10+ 对 targetSdk>=29 的应用禁止 `execve()` 应用私有目录中的文件
     *   （W^X，SELinux `execute_no_trans`）。所以应用私有目录里的 `-x` / `canExecute()`
     *   本来就不是「终端能不能跑」的可靠信号，它只反映权限位与内核策略，不反映 proot 的能力；
     * - 以它作为判据会把「文件齐全、proot 可用」误判成「未安装」，并把安装流程卡成
     *   永远无法发布的状态。
     *
     * 真正的「能否进入终端」判据是 proot 实跑：本地终端由宿主 `ProotCommand`
     * 拼出 argv 后直接 `execve`（不经 shell），失败时 proot 自己的输出即诊断。
     */
    fun isInstalled(rootfsDir: File): Boolean = NativeTerminal.isRootfsInstalled(rootfsDir.absolutePath)

    /** 从 sha 文件内容里取出 hex（容忍行尾空白与注释）。 */
    fun parseSha(text: String): String? {
        val hex = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.substringBefore(' ')
            ?.trim()
            ?: return null
        if (hex.length != 64) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return hex.lowercase()
    }
}
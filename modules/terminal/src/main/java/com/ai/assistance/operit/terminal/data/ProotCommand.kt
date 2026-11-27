package com.ai.assistance.operit.terminal.data

import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import java.io.File

/**
 * 一次 proot 启动的完整输入。**纯数据 + 纯函数**，不依赖 Android，可在 JVM 单测里断言。
 *
 * 为什么要有这一层：Kong 原来把 proot 的每条参数写在 2000 行 `common.sh` 里，
 * 探针用的 argv 与真正启动用的 argv 是两份不同的文本，出问题时只能靠 shell 猜测。
 * 现在 argv 与环境只由 `liboperit_terminal.so`（`runtime/crates/operit-terminal/src/proot.rs`）
 * 生成，Kotlin 只负责把路径和值填进 [ProotSpec]——探针（如果有）与启动没有第二份实现。
 *
 * 参考实现（逐项对照）：Operit2 `hosts/android/src/runtime_common.rs:49`
 * `buildAndroidProotCommandWithRootfs`——`std::process::Command` 逐条 arg，环境用
 * `Command::env/env_remove` 设置，全程没有 shell。
 */
data class ProotSpec(
    /** 宿主侧的 proot 可执行文件（`$BIN/proot` → `liboperit_proot.so`）。 */
    val proot: File,
    /** 宿主侧的 loader（`$BIN/loader` → `liboperit_loader.so`），必须通过 `PROOT_LOADER` 传入。 */
    val loader: File,
    /** `PROOT_TMP_DIR`：proot 自己写临时文件的目录，必须在可写位置。 */
    val tmpDir: File,
    /** 要进入的 rootfs 根目录。 */
    val rootfs: File,
    /** proot **进程**的工作目录（宿主路径）。Operit2 用 runtimeDir；Kong 用 filesDir。 */
    val processWorkingDirectory: File,
    /** 额外的 bind 挂载（Kong 自己的 app 数据目录软映射等）。 */
    val binds: List<PRootBindMount> = emptyList(),
    /** 传给 rootfs 内程序的 argv，例如 `["/bin/sh", "-i"]`。 */
    val guestCommand: List<String>,
    /** 覆盖/追加的 guest 环境变量（PROMPT_COMMAND、ENV、OPERIT_* 等）。 */
    val guestEnvironment: Map<String, String> = emptyMap(),
    /** guest 的起始目录，对应 proot 的 `-w`。 */
    val guestWorkingDirectory: String = ProotCommand.DEFAULT_GUEST_WORKING_DIR,
)

/**
 * proot 的 argv 与环境：由 `liboperit_terminal.so` 生成后原样交给 `execve` / `ProcessBuilder`。
 *
 * 不变量（由 Rust 侧保证，见 `runtime/crates/operit-terminal/src/proot.rs`）：
 * 1. `PROOT_LOADER` / `PROOT_TMP_DIR` 必须存在。缺 PROOT_LOADER 时 proot 无法映射 guest ELF，
 *    表现就是「exec 之后什么都没输出，会话关闭」。
 * 2. `PROOT_NO_SECCOMP` 必须**不在**环境里（Operit2 用 `env_remove`）。
 * 3. `LD_LIBRARY_PATH` 必须是空串，避免宿主路径污染 guest 程序。
 * 4. rootfs 内的 shell 是**直接**被 proot 执行的（`--login -i` / `-i`），
 *    不存在「shell 脚本里再拼一次 proot 参数」的第二份事实。
 */
data class ProotCommand(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
    /** 被显式剔除的环境变量（目前只有 `PROOT_NO_SECCOMP`），写进诊断。 */
    val removedEnvironmentKeys: Set<String>,
) {
    val executable: String get() = argv.first()

    /** 可复制的 argv 单行形式，用于 `terminal-init.log` 与首屏诊断。 */
    fun describeArgv(): String = argv.joinToString(" ") { quote(it) }

    /** 可复制的环境变量块（按键排序，`KEY='value'`）。 */
    fun describeEnvironment(): String =
        environment.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=${quote(value)}" }

    private fun quote(value: String): String =
        if (value.isEmpty()) "''" else "'" + value.replace("'", "'\"'\"'") + "'"

    fun logLines(): String = buildString {
        append("proot argv: ").append(describeArgv()).append('\n')
        append("proot cwd: ").append(workingDirectory.absolutePath).append('\n')
        append("proot env:\n").append(describeEnvironment()).append('\n')
        append("proot env removed: ").append(removedEnvironmentKeys.sorted().joinToString(",").ifEmpty { "<none>" })
    }

    companion object {
        /**
         * 仅用于宿主侧环境快照（`getEnvironment()`）；proot 自身的 envp 由 Rust 生成，
         * 且同样不会带上这个键。
         */
        const val PROOT_NO_SECCOMP = "PROOT_NO_SECCOMP"

        /** Operit2 的 guest 起始目录。 */
        const val DEFAULT_GUEST_WORKING_DIR = "/root"
    }
}

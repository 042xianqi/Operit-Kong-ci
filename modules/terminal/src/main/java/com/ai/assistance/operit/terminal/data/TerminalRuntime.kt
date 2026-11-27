package com.ai.assistance.operit.terminal.data

/**
 * 一个终端实例在 **shell 层面的**运行时差异。
 *
 * 为什么需要这一层：`TerminalManager.generateStartScript()` 生成的脚本有 400+ 行，
 * 其中 90% 与发行版无关（proot 参数探测、bind mount、伪文件系统、路径映射）。
 * 只有少数几处随发行版变化。把它们抽成数据，比复制整份脚本安全得多——
 * 复制出来的两份脚本会各自漂移，最后没人知道哪份是对的。
 *
 * 对照来源：
 * - Alpine：无 bash（只有 busybox ash），包管理器是 apk，配置文件在 `/etc/apk/`
 * - Ubuntu/Debian/Fedora：有 bash，apt/dnf，配置文件在 `/etc/apt/` 或 `/etc/dnf/`
 */
data class TerminalRuntime(
    /** 登录 shell 的绝对路径（rootfs 内）。 */
    val shellPath: String,
    /** 传给登录 shell 的参数，按顺序。 */
    val shellArgs: List<String>,
    /** 包管理器的源配置目标。 */
    val packageSource: PackageSourceSpec?,
    /** 进度提示里用的发行版名字。 */
    val label: String,
    /**
     * APT 的 suite 代号（`noble` / `trixie`）；为空表示该发行版不吃 `/etc/apt/sources.list`
     * （Fedora 用 dnf）。写错 suite 会让 `apt update` 直接 404。
     */
    val aptSuite: String = "noble",
) {
    /**
     * 一条可直接放进 `env -i ... /bin/xxx` 的 shell 调用串（旧脚本路径专用）。
     *
     * 不再带 rc 文件：应用提示符由宿主环境变量注入（ash 用 `ENV`，bash 用
     * `PROMPT_COMMAND`），本地终端也不需要经过这份脚本。
     */
    /** 当前实例用哪个包管理器。源配置与包管理器必须一致，否则装不上。 */
    val packageTool: PackageTool get() = PackageTool.of(packageSource)

    fun buildLoginCommand(): String =
        (listOf(shellPath) + shellArgs).joinToString(" ")

    companion object {

        /**
         * Alpine 的运行时。
         *
         * 关键差异：
         * - shell 是 `/bin/sh`（busybox ash），**不能**传 `--rcfile`——ash 没有这个选项，
         *   传了会被当成脚本名而报错。
         * - 本地终端的交互 shell 由宿主 `ProotCommand` 直接 exec 为 `sh -i`；
         *   提示符钩子来自 `ENV=/etc/operit/prompt.sh`（只设置 PS1），**不改写** `/etc/profile`。
         *   绝不能把 rc 文件放在位置参数上：那会让 ash 执行完脚本就退出。
         * - 包源是 `/etc/apk/repositories`，一行一个仓库 URL。
         * - [shellArgs] 仅供旧脚本路径（SSH / chroot）的 `DISTRO_LOGIN_CMD` 使用。
         */
        val ALPINE =
            TerminalRuntime(
                shellPath = "/bin/sh",
                // 旧脚本路径：`-l` 读 /etc/profile；`-i` 保证是交互式，否则没有提示符。
                shellArgs = listOf("-l", "-i"),
                packageSource = PackageSourceSpec.AlpineRepositories,
                label = "Alpine",
            )

        /**
         * Ubuntu / Debian / Fedora 的运行时。
         *
         * 本地终端由宿主 `ProotCommand` 直接 exec 为 `bash --login -i`，
         * 提示符钩子来自导出的 `PROMPT_COMMAND`（与 Operit2 `terminal.rs:1649` 同机制）。
         * [shellArgs] 只服务旧脚本路径（SSH / chroot）的 `DISTRO_LOGIN_CMD`。
         */
        val DEBIAN_LIKE =
            TerminalRuntime(
                shellPath = "/bin/bash",
                shellArgs = listOf("-l", "-i"),
                packageSource = PackageSourceSpec.AptSourcesList,
                label = "Ubuntu",
            )

        /**
         * 按发行版 id 选择运行时。
         *
         * 判定依据是**发行版 id**，不是实例 id——用户可能把 Ubuntu 实例改名成任意名字，
         * 也可能同时存在两个 Alpine 实例。
         */
        fun forDistribution(distributionId: String?): TerminalRuntime =
            when {
                distributionId == null -> DEBIAN_LIKE
                distributionId.startsWith("alpine") -> ALPINE
                distributionId.startsWith("debian") -> DEBIAN_LIKE.copy(label = "Debian", aptSuite = "trixie")
                distributionId.startsWith("fedora") ->
                    DEBIAN_LIKE.copy(label = "Fedora", aptSuite = "")
                else -> DEBIAN_LIKE
            }
    }
}

/** 包管理器源配置的写法。 */
sealed interface PackageSourceSpec {
    /** Debian 系：`deb <url> <suite> main restricted universe multiverse` 每行一条。 */
    data object AptSourcesList : PackageSourceSpec

    /** Alpine：`<url>/<branch>/main` 这种仓库地址，每行一条。 */
    data object AlpineRepositories : PackageSourceSpec

    /**
     * 生成源配置内容。
     *
     * @param baseUrl 用户在设置里选的镜像基址
     * @param suite 发行版代号（`noble` / `trixie` / `latest-stable` 等）
     * @param comment 头部注释
     */
    fun render(baseUrl: String, suite: String, comment: String): String =
        when (this) {
            AptSourcesList ->
                buildString {
                    appendLine(comment)
                    appendLine("deb $baseUrl $suite main restricted universe multiverse")
                    appendLine("deb $baseUrl $suite-updates main restricted universe multiverse")
                    append("deb $baseUrl $suite-backports main restricted universe multiverse\n")
                }
            AlpineRepositories ->
                buildString {
                    appendLine(comment)
                    appendLine("$baseUrl/$suite/main")
                    append("$baseUrl/$suite/community\n")
                }
        }
}

/**
 * 包管理器在这一层能做的事。
 *
 * SetupScreen 原本把 apt-get / dpkg -s 写死在 UI 里，而默认实例是 apk。
 * 用户在 Alpine 实例上点任何一项都会 apt-get: not found，页面看起来是坏的。
 * 把差异收到这里：UI 只问怎么装、怎么查，不再自己拼发行版命令。
 */
enum class PackageTool(
    /** 包管理器可执行文件名。 */
    val executable: String,
    /** 安装命令前缀（不含包名）。 */
    val installPrefix: List<String>,
    /** 判断包是否已装；exit code 0 表示已装。 */
    val queryCommand: (String) -> List<String>,
) {
    APK(
        executable = "apk",
        installPrefix = listOf("apk", "add", "--no-cache"),
        // apk info -e 只查已装数据库，不联网，适合逐项探测。
        queryCommand = { pkg -> listOf("apk", "info", "-e", pkg) },
    ),
    APT(
        executable = "apt-get",
        installPrefix = listOf("apt-get", "install", "-y"),
        queryCommand = { pkg -> listOf("dpkg", "-s", pkg) },
    ),
    DNF(
        executable = "dnf",
        installPrefix = listOf("dnf", "install", "-y"),
        queryCommand = { pkg -> listOf("rpm", "-q", pkg) },
    ),
    ;

    /** 一次装多个包。 */
    fun installCommand(packages: List<String>): List<String> =
        if (packages.isEmpty()) emptyList() else installPrefix + packages

    companion object {
        /** 由源配置反推包管理器：两者必须同一套。 */
        fun of(source: PackageSourceSpec?): PackageTool = when (source) {
            PackageSourceSpec.AlpineRepositories -> APK
            PackageSourceSpec.AptSourcesList -> APT
            null -> APT
        }
    }
}

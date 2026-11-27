package com.ai.assistance.operit.terminal.runtime

import com.ai.assistance.operit.terminal.data.ProotCommand
import com.ai.assistance.operit.terminal.data.ProotSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * `liboperit_terminal.so` 的 JNI 入口。
 *
 * **Kotlin 侧不再拼 argv / envp**：这里只把路径与值（[ProotSpec]）编码成 JSON，
 * Rust 侧的 `proot::build` 是唯一实现（`runtime/crates/operit-terminal/src/proot.rs`）。
 * 返回的 argv、environment、cwd 直接交给 `Pty.start` / `ProcessBuilder`。
 *
 * 符号名与 Rust 侧 `jni.rs` 一一对应：
 * `Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_buildProotCommand`。
 */
object NativeTerminal {

    init {
        System.loadLibrary("operit_terminal")
    }

    /** Rust 版本串（`CARGO_PKG_VERSION+build`），首次调用会再校验一次动态库可解析。 */
    @JvmStatic
    external fun nativeVersion(): String

    /** 输入 [ProotWire] 编码的 spec JSON，输出命令 JSON 或 `{"error": "..."}`。 */
    @JvmStatic
    external fun buildProotCommand(specJson: String): String

    /** rootfs 内目录/文件/gid/提示符准备（`guest.rs` + `repair.rs`），JSON in / JSON out。 */
    @JvmStatic
    external fun prepareLocalRootfs(specJson: String): String

    /** 归档 → rootfs 安装（`install.rs`），JSON in / JSON out。 */
    @JvmStatic
    external fun installRootfs(specJson: String): String

    /** 内容判据（`bin/busybox` 文件、`bin/sh` 可解析、`etc` 目录），不看 exec 位。 */
    @JvmStatic
    external fun rootfsInstalled(rootfsPath: String): Boolean

    val version: String by lazy { nativeVersion() }

    /** 宿主 proot 启动参数：唯一来源是 Rust。 */
    fun build(spec: ProotSpec): ProotCommand =
        ProotWire.decode(buildProotCommand(ProotWire.encode(spec)))

    /** 宿主侧 rootfs 准备：目录、文件、Android gid、提示符策略全部由 Rust 决定。 */
    fun prepare(spec: RootfsPrepSpec): PreparedRootfs =
        RootfsWire.decodePrepared(prepareLocalRootfs(RootfsWire.encodePrepare(spec)))

    /** rootfs 是否已具备可启动结构；判据在 `install.rs`，Kotlin 不做任何文件探测。 */
    fun isRootfsInstalled(rootfsPath: String): Boolean = rootfsInstalled(rootfsPath)

    /** 一次 rootfs 准备请求；字段名即 `guest.rs` 的 camelCase 线格式。 */
    data class RootfsPrepSpec(
        val rootfs: File,
        val directories: Collection<String>,
        val files: List<GuestFileWrite>,
        val groupIds: Collection<Int>,
        val distro: String,
        val repairLegacy: Boolean = true,
    )

    /** 待写入 rootfs 的文件（路径相对 rootfs 根）。 */
    data class GuestFileWrite(val relativePath: String, val contents: String)

    /** 准备结果：人类可读动作 + 该发行版需要的提示符环境变量。 */
    data class PreparedRootfs(
        val actions: List<String>,
        val environment: Map<String, String>,
    )
}

/** [NativeTerminal.RootfsPrepSpec] / [NativeTerminal.PreparedRootfs] 的线格式映射。 */
private object RootfsWire {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    internal data class FileWire(val relativePath: String, val contents: String)

    @Serializable
    internal data class PrepareWire(
        val rootfs: String,
        val directories: List<String>,
        val files: List<FileWire>,
        val groupIds: List<Int>,
        val distro: String,
        val repairLegacy: Boolean,
    )

    @Serializable
    internal data class PreparedWire(
        val actions: List<String> = emptyList(),
        val environment: Map<String, String> = emptyMap(),
    )

    fun encodePrepare(spec: NativeTerminal.RootfsPrepSpec): String =
        json.encodeToString(
            PrepareWire.serializer(),
            PrepareWire(
                rootfs = spec.rootfs.absolutePath,
                directories = spec.directories.toList(),
                files = spec.files.map { FileWire(it.relativePath, it.contents) },
                groupIds = spec.groupIds.toList(),
                distro = spec.distro,
                repairLegacy = spec.repairLegacy,
            ),
        )

    fun decodePrepared(response: String): NativeTerminal.PreparedRootfs {
        val root = json.parseToJsonElement(response).jsonObject
        root["error"]?.let { error ->
            throw IllegalStateException("native rootfs prep failed: ${error.jsonPrimitive.content}")
        }
        val wire = json.decodeFromJsonElement(PreparedWire.serializer(), root)
        return NativeTerminal.PreparedRootfs(
            actions = wire.actions,
            environment = LinkedHashMap(wire.environment),
        )
    }
}

/**
 * `ProotSpec` / `ProotCommand` 与 Rust serde 结构的线格式映射。
 *
 * 字段名必须与 `spec.rs` / `jni.rs` 的 `rename_all = "camelCase"` 完全一致；
 * `ProotWireTest` 用字面量 JSON 把这条契约钉住（Rust 测试看不到 Kotlin 的字段名）。
 */
internal object ProotWire {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(spec: ProotSpec): String = json.encodeToString(SpecWire.serializer(), SpecWire.from(spec))

    fun decode(response: String): ProotCommand {
        val root = json.parseToJsonElement(response).jsonObject
        root["error"]?.let { error ->
            throw IllegalStateException("native proot build failed: ${error.jsonPrimitive.content}")
        }
        val wire = json.decodeFromJsonElement(CommandWire.serializer(), root)
        val environment = LinkedHashMap<String, String>(wire.environment.size)
        wire.environment.forEach { pair ->
            require(pair.size == 2) { "malformed environment entry: $pair" }
            environment[pair[0]] = pair[1]
        }
        return ProotCommand(
            argv = wire.argv,
            environment = environment,
            workingDirectory = File(wire.workingDirectory),
            removedEnvironmentKeys = wire.removedEnvironmentKeys.toSet(),
        )
    }

    @Serializable
    internal data class BindWire(val source: String, val target: String = "")

    @Serializable
    internal data class SpecWire(
        val proot: String,
        val loader: String,
        val tmpDir: String,
        val rootfs: String,
        val processWorkingDirectory: String,
        val binds: List<BindWire>,
        val guestCommand: List<String>,
        val guestEnvironment: Map<String, String>,
        val guestWorkingDirectory: String,
    ) {
        companion object {
            fun from(spec: ProotSpec) = SpecWire(
                proot = spec.proot.absolutePath,
                loader = spec.loader.absolutePath,
                tmpDir = spec.tmpDir.absolutePath,
                rootfs = spec.rootfs.absolutePath,
                processWorkingDirectory = spec.processWorkingDirectory.absolutePath,
                binds = spec.binds.map { BindWire(it.sourcePath, it.targetPath) },
                guestCommand = spec.guestCommand,
                guestEnvironment = spec.guestEnvironment,
                guestWorkingDirectory = spec.guestWorkingDirectory,
            )
        }
    }

    /** Rust `jni.rs` 的 `CommandJson`：environment 是 `[key, value]` 数组，保序。 */
    @Serializable
    internal data class CommandWire(
        val argv: List<String>,
        val environment: List<List<String>>,
        val workingDirectory: String,
        val removedEnvironmentKeys: List<String>,
        val version: String = "",
    )
}

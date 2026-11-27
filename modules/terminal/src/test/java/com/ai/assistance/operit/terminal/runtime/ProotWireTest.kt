package com.ai.assistance.operit.terminal.runtime

import com.ai.assistance.operit.terminal.data.ProotSpec
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin ⇄ Rust 线格式契约。
 *
 * argv / 环境的**内容**由 `cargo test` 断言（`crates/operit-terminal/src/proot.rs`）；
 * 这里只钉住 Rust serde 看不到的那一半：Kotlin 发出的字段名与 Rust 解析的
 * `rename_all = "camelCase"` 一致，并且 Rust 返回的 `[key, value]` 数组能还原成 Map。
 */
class ProotWireTest {

    private val filesDir = File("/data/user/0/com.example/files")
    private val rootfs = File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/alpine")

    private fun spec() =
        ProotSpec(
            proot = File(filesDir, "usr/bin/proot"),
            loader = File(filesDir, "usr/bin/loader"),
            tmpDir = File(filesDir, "tmp"),
            rootfs = rootfs,
            processWorkingDirectory = filesDir,
            binds = listOf(PRootBindMount(filesDir.absolutePath), PRootBindMount("/tmp", "/dev/shm")),
            guestCommand = listOf("/bin/sh", "-i"),
            guestEnvironment = mapOf("ENV" to "/etc/operit/prompt.sh"),
        )

    @Test
    fun `spec encodes the exact serde field names`() {
        val encoded = Json.parseToJsonElement(ProotWire.encode(spec())).jsonObject

        assertEquals(
            setOf(
                "proot",
                "loader",
                "tmpDir",
                "rootfs",
                "processWorkingDirectory",
                "binds",
                "guestCommand",
                "guestEnvironment",
                "guestWorkingDirectory",
            ),
            encoded.keys,
        )
        assertEquals("/root", encoded["guestWorkingDirectory"]?.jsonPrimitive?.content)
        // `PRootBindMount` 的单参数形式默认 `target == source`，所以同路径 bind 的 target
        // 就是 source 本身。Rust `spec.rs` 只把**空串**额外解释成「与 source 相同」
        // （`Bind::target()`），非空 target 必须是绝对路径。因此合法编码只有两种：
        // 空串或与 source 完全相同；出现第三个路径就说明映射错了。
        val firstBind = encoded["binds"]!!.jsonObjectList().first()
        assertEquals(setOf("source", "target"), firstBind.keys)
        val source = firstBind["source"]?.jsonPrimitive?.content
        val target = firstBind["target"]?.jsonPrimitive?.content
        assertTrue(
            "same-path bind target must be the empty string or the source itself, got '$target'",
            target.isNullOrEmpty() || target == source,
        )
    }

    @Test
    fun `command json decodes argv environment and cwd`() {
        // 字面量取自 Rust `CommandJson` 的序列化结果（environment 是 [[k, v], ...]）。
        val response =
            """
            {"argv":["/bin/proot","-0","--link2symlink","-r","/rootfs","-w","/root","/bin/sh","-i"],
             "environment":[["PROOT_TMP_DIR","/tmp"],["PROOT_LOADER","/loader"],["LD_LIBRARY_PATH",""]],
             "workingDirectory":"/data/files",
             "removedEnvironmentKeys":["PROOT_NO_SECCOMP"],
             "version":"0.1.0+android26-arm64"}
            """.trimIndent()

        val command = ProotWire.decode(response)

        assertEquals(9, command.argv.size)
        assertEquals("/bin/proot", command.executable)
        // 断言原始串而不是 absolutePath：absolutePath 会把 POSIX 路径投射到宿主的盘符
        // （Windows 上变成 `C:\data\files`），那样测的是宿主文件系统而不是线格式。
        // `java.io.File` 在 Windows 上构造时会把 `/` 归一成 `\`，所以只把分隔符折算回来。
        assertEquals("/data/files", command.workingDirectory.path.replace('\\', '/'))
        assertEquals(File("/data/files"), command.workingDirectory)
        // 顺序必须保持：execve 的 envp 虽然键值无序，但日志 diff 依赖它稳定。
        assertEquals(listOf("PROOT_TMP_DIR", "PROOT_LOADER", "LD_LIBRARY_PATH"), command.environment.keys.toList())
        assertEquals("/loader", command.environment["PROOT_LOADER"])
        assertTrue(command.removedEnvironmentKeys.contains("PROOT_NO_SECCOMP"))
    }

    @Test
    fun `error payload becomes an exception`() {
        val failure =
            runCatching { ProotWire.decode("""{"error":"binds[0].source must not contain ':'"}""") }

        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("must not contain"))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectList() =
        (this as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
}

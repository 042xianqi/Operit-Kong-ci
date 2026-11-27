package com.ai.assistance.operit.terminal.runtime

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin ⇄ JNI 声明侧契约。
 *
 * 单元测试跑在宿主 JVM 上，而仓库里只有一份 arm64-v8a 的 `liboperit_terminal.so`，
 * `System.loadLibrary` 必然失败，所以这里不触碰任何 native 实现。
 *
 * 但 JNI 符号名完全由 Kotlin 声明推导：`Java_<类名>_<方法名>`，`$` 变 `_00024`，
 * `@JvmStatic` 决定方法落在哪个类上、是否为静态。也就是说签名一漂移，Rust 侧的
 * `jni.rs` / `jni_pty.rs` 就再也找不到入口，而编译器不会报错——这类缺陷只有反射
 * 测试能拦住。因此本测试钉住三件事：
 *
 * 1. `NativeTerminal` 的 5 个静态 native 入口（含 3 个新增的 install / guest 入口）；
 * 2. `Pty` 伴生对象的 4 个 instance native 成员与实例方法 `setPtyWindowSize`；
 * 3. 由上述声明反推出的 JNI 符号名与 CONTRACT.md 逐字一致。
 *
 * 全部通过 `Class.forName(name, initialize = false, …)` 取类，绝不触发
 * `init { System.loadLibrary(...) }`。
 */
class NativeSurfaceTest {

    private val loader: ClassLoader? = NativeSurfaceTest::class.java.classLoader

    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    private fun intType(): Class<*> = Int::class.javaPrimitiveType!!

    private fun stringType(): Class<*> = String::class.java

    private fun stringArrayType(): Class<*> = arrayOf<String>().javaClass

    private fun declared(owner: Class<*>, name: String): Method =
        owner.declaredMethods.firstOrNull { it.name == name }
            ?: throw AssertionError(
                "${owner.name} must declare $name; declared=${owner.declaredMethods.map { it.name }.sorted()}",
            )

    private fun assertNativeSignature(
        owner: Class<*>,
        name: String,
        parameters: List<Class<*>>,
        returnType: Class<*>,
    ): Method {
        val method = declared(owner, name)
        assertEquals("$name parameter types", parameters, method.parameterTypes.toList())
        assertEquals("$name return type", returnType, method.returnType)
        assertTrue("$name must stay a JNI native method", Modifier.isNative(method.modifiers))
        return method
    }

    private fun nativeNames(owner: Class<*>): List<String> =
        owner.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { it.name }.sorted()

    // ------------------------------------------------------------------ NativeTerminal

    @Test
    fun `NativeTerminal declares exactly the pinned static native entry points`() {
        val nativeTerminal = type("com.ai.assistance.operit.terminal.runtime.NativeTerminal")
        val pinned: Map<String, Pair<List<Class<*>>, Class<*>>> =
            linkedMapOf(
                "nativeVersion" to (emptyList<Class<*>>() to stringType()),
                "buildProotCommand" to (listOf(stringType()) to stringType()),
                "prepareLocalRootfs" to (listOf(stringType()) to stringType()),
                "installRootfs" to (listOf(stringType()) to stringType()),
                // jstring in → jboolean out，Kotlin 侧是非空 Boolean，JVM 上就是 primitive Z。
                "rootfsInstalled" to (listOf(stringType()) to java.lang.Boolean.TYPE),
            )

        for ((name, signature) in pinned) {
            val method = assertNativeSignature(nativeTerminal, name, signature.first, signature.second)
            assertTrue(
                "$name must be @JvmStatic so the symbol lives on NativeTerminal, not on a JClass thiz",
                Modifier.isStatic(method.modifiers),
            )
        }

        assertEquals(
            "NativeTerminal must not grow unpinned native entry points",
            pinned.keys.sorted(),
            nativeNames(nativeTerminal),
        )
    }

    // ------------------------------------------------------------------ Pty

    @Test
    fun `Pty companion keeps the four pinned instance native members`() {
        val companion = type("com.ai.assistance.operit.terminal.Pty\$Companion")
        val pinned: Map<String, Pair<List<Class<*>>, Class<*>>> =
            linkedMapOf(
                "createSubprocess" to (
                    listOf(stringArrayType(), stringArrayType(), stringType()) to IntArray::class.java
                    ),
                "waitFor" to (listOf(intType()) to intType()),
                "getTerminalFlags" to (listOf(intType()) to intType()),
                "getAvailableBytes" to (listOf(intType()) to intType()),
            )

        for ((name, signature) in pinned) {
            val method = assertNativeSignature(companion, name, signature.first, signature.second)
            assertFalse(
                "$name must stay an instance member of the companion: @JvmStatic would move the JNI " +
                    "symbol off Java_..._Pty_00024Companion_$name onto Java_..._Pty_$name",
                Modifier.isStatic(method.modifiers),
            )
        }

        assertEquals(
            "Pty companion must not grow unpinned native members",
            pinned.keys.sorted(),
            nativeNames(companion),
        )
    }

    @Test
    fun `Pty declares the instance window size entry point without a companion`() {
        val pty = type("com.ai.assistance.operit.terminal.Pty")
        val method = assertNativeSignature(
            pty,
            "setPtyWindowSize",
            listOf(intType(), intType(), intType()),
            intType(),
        )
        assertFalse(
            "setPtyWindowSize must stay an instance method: its symbol is Java_..._Pty_setPtyWindowSize",
            Modifier.isStatic(method.modifiers),
        )
    }

    // ------------------------------------------------------------------ JNI 符号名

    /** JNI 短名 mangling：`.`→`_`、`_`→`_1`、`$`→`_00024`，其余非字母数字按 hex 展开。 */
    private fun jniSymbol(owner: Class<*>, method: String): String = buildString {
        append("Java_")
        for (c in owner.name) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> append(c)
                c == '.' -> append('_')
                c == '_' -> append("_1")
                c == '$' -> append("_00024")
                else -> append('_').append(Integer.toHexString(c.code))
            }
        }
        append('_').append(method)
    }

    @Test
    fun `derived JNI symbol names match the pinned rust exports verbatim`() {
        val nativeTerminal = type("com.ai.assistance.operit.terminal.runtime.NativeTerminal")
        val pty = type("com.ai.assistance.operit.terminal.Pty")
        val companion = type("com.ai.assistance.operit.terminal.Pty\$Companion")

        assertEquals(
            "Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_nativeVersion",
            jniSymbol(nativeTerminal, "nativeVersion"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_buildProotCommand",
            jniSymbol(nativeTerminal, "buildProotCommand"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_prepareLocalRootfs",
            jniSymbol(nativeTerminal, "prepareLocalRootfs"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_installRootfs",
            jniSymbol(nativeTerminal, "installRootfs"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_rootfsInstalled",
            jniSymbol(nativeTerminal, "rootfsInstalled"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess",
            jniSymbol(companion, "createSubprocess"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitFor",
            jniSymbol(companion, "waitFor"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_Pty_00024Companion_getTerminalFlags",
            jniSymbol(companion, "getTerminalFlags"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_Pty_00024Companion_getAvailableBytes",
            jniSymbol(companion, "getAvailableBytes"),
        )
        assertEquals(
            "Java_com_ai_assistance_operit_terminal_Pty_setPtyWindowSize",
            jniSymbol(pty, "setPtyWindowSize"),
        )
    }
}

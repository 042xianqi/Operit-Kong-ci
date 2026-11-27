package com.ai.assistance.operit.core.tools.system.terminal

/**
 * Rust 加速的终端输出压缩层（`tools/native_output_compress`）。
 *
 * 为什么下沉：`TerminalOutputCompressor.compress()` 在每次工具输出超过 2000 字符时都会执行，
 * 内部约 20 个正则要处理长输出（几十万行 Gradle/Maven 日志）。Kotlin 侧每条正则都要分配
 * `java.util.regex.Matcher`，GC 压力与回溯开销明显。Rust 侧改为字节级手写扫描。
 *
 * 失败（库未加载 / 协议异常）时返回 null，调用方回退到 Kotlin 实现，行为不变。
 */
internal object NativeOutputCompress {

    private val loaded: Boolean = try {
        System.loadLibrary("operit_output_compress")
        android.util.Log.i("NativeOutputCompress", "loaded liboperit_output_compress.so")
        true
    } catch (error: Throwable) {
        android.util.Log.w("NativeOutputCompress", "load failed, falling back to Kotlin", error)
        false
    }

    /**
     * 返回 `omittedLines\u001fstrategy\u001foutput`；不压缩时返回 null。
     */
    @JvmStatic
    private external fun compressJni(command: String, output: String): String?

    /** 字段分隔符，与 Rust 侧一致。 */
    private const val SEP = '\u001f'

    internal fun available(): Boolean = loaded

    internal fun compress(command: String, output: String): TerminalOutputCompressor.Summary? {
        if (!loaded || command.isEmpty() || output.isEmpty()) return null
        val raw = try {
            compressJni(command, output)
        } catch (error: Throwable) {
            null
        } ?: return null
        val first = raw.indexOf(SEP)
        if (first <= 0) return null
        val second = raw.indexOf(SEP, first + 1)
        if (second <= first + 1) return null
        val omitted = raw.substring(0, first).toIntOrNull() ?: return null
        val strategy = raw.substring(first + 1, second)
        val body = raw.substring(second + 1)
        return TerminalOutputCompressor.Summary(body, omitted, strategy)
    }
}

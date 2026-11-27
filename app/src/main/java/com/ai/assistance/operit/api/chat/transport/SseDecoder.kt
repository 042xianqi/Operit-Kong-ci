package com.ai.assistance.operit.api.chat.transport

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * SSE 帧解码器：把任意切分的 HTTP 字节流拼装成完整的 SSE event。
 *
 * 逐行对着 Operit2 `core/crates/provider/services/src/chat/llmprovider/StreamingResponseLines.rs`
 * 的 `SseDecoder` 移植（第 5-71 行）。关键差异与注意点：
 *
 * - `pending` 必须是**字节**缓冲，不能是 StringBuilder。UTF-8 多字节序列被 chunk 切断是常态
 *   （一个汉字 3 字节、一个 emoji 4 字节），解码必须延后到「完整一行已经收齐」之后。
 * - 非法 UTF-8 直接抛错，而不是替换成 U+FFFD。Rust 侧注释写明理由：
 *   "Rejects invalid UTF-8 without inserting replacement characters into tool parameters"
 *   —— 走 `InputStreamReader` 会把坏字节静默变成 U+FFFD 并进入工具参数。
 * - `finish()` 对「事件没收完就 EOF」报错，绝不把截断的 JSON 当完整事件交给 provider 解析。
 */
internal class SseDecoder {

    /** 尚未组成完整一行的字节。跨 push 调用保留。 */
    private val pending = ByteArrayOutputStream()

    /** 当前事件累积的 `data:` 行内容（SSE 允许一个事件跨多条 data 行）。 */
    private val data = mutableListOf<String>()

    /** 当 chunk 边界恰好落在 `\r` 与 `\n` 之间时置位，下一次 push 需跳过前导 `\n`。 */
    private var skipLf = false

    /** 是否已处理过第一行；BOM 只在第一行剥离。 */
    private var started = false

    /**
     * 喂入一段字节，返回本次能组装的**完整**事件列表（元素为该事件所有 data 行的 `\n` 拼接）。
     *
     * @throws CharacterCodingException 该行不是合法 UTF-8
     */
    @Throws(CharacterCodingException::class)
    fun push(bytes: ByteArray): List<String> {
        var input = bytes
        if (skipLf && input.isNotEmpty()) {
            skipLf = false
            if (input[0] == LF) input = input.copyOfRange(1, input.size)
        }
        pending.write(input)
        val buffer = pending.toByteArray()

        val events = mutableListOf<String>()
        var consumed = 0
        while (true) {
            val offset = indexOfLineBreak(buffer, consumed) ?: break
            val end = offset
            var line = decodeStrict(buffer, consumed, end - consumed)

            if (!started) {
                started = true
                if (line.startsWith(BOM)) line = line.substring(BOM.length)
            }

            consumed = end + 1
            if (buffer[end] == CR) {
                if (consumed < buffer.size && buffer[consumed] == LF) {
                    consumed += 1
                } else if (consumed == buffer.size) {
                    // chunk 正好断在 \r 之后：无法判断下一段是否以 \n 开头，交给下一次 push。
                    skipLf = true
                }
            }

            if (line.isEmpty()) {
                // 空行 = 事件边界。
                val joined = data.joinToString("\n")
                data.clear()
                if (joined.isNotBlank()) events.add(joined)
            } else {
                val separator = line.indexOf(':')
                val field = if (separator < 0) line else line.substring(0, separator)
                val value = if (separator < 0) "" else line.substring(separator + 1)
                if (field == "data") {
                    data.add(if (value.startsWith(" ")) value.substring(1) else value)
                }
                // 其它字段（event/id/retry）与注释（无冒号的行）一律忽略。
            }
        }

        if (consumed > 0) {
            pending.reset()
            if (consumed < buffer.size) pending.write(buffer, consumed, buffer.size - consumed)
        }
        return events
    }

    /**
     * 流结束时调用。残留半行或残留 data 都算传输被截断。
     *
     * @throws CharacterCodingException 残留字节不是合法 UTF-8
     * @throws IllegalStateException 存在未收完的事件
     */
    @Throws(CharacterCodingException::class)
    fun finish() {
        val leftover = pending.toByteArray()
        // 先校验编码：一个被切断的多字节序列应当报「不是合法 UTF-8」，而不是「事件未完成」。
        decodeStrict(leftover, 0, leftover.size)
        if (leftover.isNotEmpty() || data.isNotEmpty()) {
            throw IllegalStateException("response ended during an SSE event")
        }
    }

    /** 返回 [from, to) 区间内第一个 `\r` 或 `\n` 的下标。 */
    private fun indexOfLineBreak(buffer: ByteArray, from: Int): Int? {
        for (index in from until buffer.size) {
            val byte = buffer[index]
            if (byte == CR || byte == LF) return index
        }
        return null
    }

    /**
     * 严格解码：遇到非法或截断的多字节序列抛异常，不做替换。
     *
     * 不能用 `String(bytes, Charsets.UTF_8)` —— 它会静默插入 U+FFFD。
     */
    private fun decodeStrict(buffer: ByteArray, offset: Int, length: Int): String {
        if (length == 0) return ""
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars: CharBuffer = decoder.decode(ByteBuffer.wrap(buffer, offset, length))
        return chars.toString()
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
        const val BOM = "\uFEFF"
    }
}

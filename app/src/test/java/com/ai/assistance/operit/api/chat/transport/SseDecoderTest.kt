package com.ai.assistance.operit.api.chat.transport

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对着 Operit2 `StreamingResponseLines.rs:73-121` 的四个 test 移植。
 *
 * 其中 [everySplitPreservesCompleteEvents] 是核心回归网：它在**每一个字节位置**切分同一个
 * wire 报文，逐一断言结果不变。SSE + UTF-8 的边界 bug 只能靠这种穷举稳定复现。
 */
class SseDecoderTest {

    /**
     * 对三种换行（`\n` / `\r\n` / `\r`）分别在每个字节位置切分，
     * 断言事件序列、多行 data 拼接、中文与 emoji 都完好。
     */
    @Test
    fun everySplitPreservesCompleteEvents() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val wire = "\uFEFF: heartbeat$newline$newline" +
                "event: message$newline" +
                "data: {\"text\":$newline" +
                "data: \"你好😀\"}$newline$newline"
            val bytes = wire.toByteArray(Charsets.UTF_8)

            for (split in 0..bytes.size) {
                val decoder = SseDecoder()
                val events = mutableListOf<String>()
                events += decoder.push(bytes.copyOfRange(0, split))
                events += decoder.push(bytes.copyOfRange(split, bytes.size))
                decoder.finish()

                assertEquals(
                    "换行=${newline.replace("\r", "\\r").replace("\n", "\\n")} 切分点=$split",
                    listOf("{\"text\":\n\"你好😀\"}"),
                    events,
                )
            }
        }
    }

    /** 空 data、纯注释、非 data 字段都不能变成「空事件」。 */
    @Test
    fun ignoresEmptyEventsAndComments() {
        val decoder = SseDecoder()
        assertTrue(decoder.push(": ping\n\ndata:\n\nevent: ping\nid: 1\n\n".toByteArray()).isEmpty())
        assertEquals(listOf("[DONE]"), decoder.push("data: [DONE]\n\n".toByteArray()))
    }

    /** EOF 时未收完的事件必须报错，不能当作完整 JSON 交给 provider。 */
    @Test
    fun rejectsUnfinishedEventAtEof() {
        for (wire in listOf("data: {}", "data: {}\n", "data: {\n")) {
            val decoder = SseDecoder()
            assertTrue("wire=$wire", decoder.push(wire.toByteArray()).isEmpty())
            assertThrows("wire=$wire", IllegalStateException::class.java) { decoder.finish() }
        }
    }

    /** 非法或截断的 UTF-8 必须报错，不能插入替换字符污染工具参数。 */
    @Test
    fun rejectsInvalidAndTruncatedUtf8() {
        assertThrows(CharacterCodingException::class.java) {
            SseDecoder().push(byteArrayOf('d'.code.toByte(), 0xFF.toByte(), '\n'.code.toByte()))
        }

        // 合法但被截断的 3 字节序列（"中" 的前两字节，后面没有换行）
        val truncated = SseDecoder()
        assertTrue(truncated.push(byteArrayOf('d'.code.toByte(), 0xE4.toByte(), 0xB8.toByte())).isEmpty())
        assertThrows(CharacterCodingException::class.java) { truncated.finish() }
    }

    /** 一个事件跨多条 data 行时用 `\n` 拼接；`data:` 后单个空格被剥掉，其余空格保留。 */
    @Test
    fun joinsMultilineDataWithoutCollapsingInnerSpaces() {
        val decoder = SseDecoder()
        val events = decoder.push("data: a  b\ndata:c\n\n".toByteArray())
        assertEquals(listOf("a  b\nc"), events)
    }

    /** chunk 边界落在 `\r` 与 `\n` 之间时不能产生额外的空事件。 */
    @Test
    fun handlesSplitBetweenCarriageReturnAndLineFeed() {
        val decoder = SseDecoder()
        assertEquals(emptyList<String>(), decoder.push("data: x\r".toByteArray()))
        assertEquals(listOf("x"), decoder.push("\n\r\n".toByteArray()))
        decoder.finish()
    }
}
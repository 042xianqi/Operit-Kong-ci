package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

/**
 * 思考面板折叠预览的增量文本缓冲。
 *
 * 逐行移植自 Operit2 `apps/flutter/app/lib/ui/features/chat/components/part/ThinkingPreviewBuffer.dart`。
 * 职责：把流式到达的思考原文，实时清洗成「适合单行预览」的短文本。
 *
 * 三层处理流水线（对应 Dart 的三个类）：
 *
 * ```
 * 原文 append
 *   → CodeUnitRing   环形缓冲，只保留最后 1000 个 UTF-16 码元（O(1) 淘汰）
 *   → PreviewText    空白折叠 + 尾部 trim（不因尾随空格淘汰可见文本）
 *   → LineCleaner    去掉行首 markdown 标记（# * > - . 数字）
 *   → TagCleaner     去掉 <...> 标签；未闭合标签按原文显示
 * ```
 *
 * 与 Dart 侧一致的关键设计：
 * - **append-only**：只处理新增部分；发现不是前缀增量（快照被回滚/替换）就整体重建。
 * - **码元级**：全程在 UTF-16 码元上操作，与 Dart 的 `codeUnitAt` 一致。
 * - **[textOffset]**：被淘汰的前缀长度，供滚动组件做位置修正。
 *
 * 为什么不用正则：流式场景下每秒可能来十几个 chunk，每次全文跑正则会产生大量临时字符串。
 * 这里全是 O(1) 追加 + 环形淘汰。
 */
internal class ThinkingPreviewBuffer {

    private var source = ""
    private var preview = ""
    private var tags = TagCleaner()
    private var fenceStart: TagCleaner? = null
    private var insideFence = false
    private var ticks = 0

    /** 被保留窗口在清洗后文本中的起始位置；回滚时也会跟着回退。 */
    val textOffset: Int get() = tags.textOffset

    /** 当前预览文本。 */
    val text: String get() = preview

    /**
     * 喂入一份**全量**思考原文快照，返回清洗后的预览文本。
     *
     * 之所以收全量而不是增量：上游快照可能被 rollback 替换，
     * 只给增量无法判断是否连续。这里用「是否以旧原文为前缀」来判断。
     */
    fun update(text: String): String {
        if (text == source) return preview

        var start = source.length
        if (!text.startsWith(source)) {
            // 快照被替换或回滚：状态全部作废，从头重放。
            tags = TagCleaner()
            fenceStart = null
            insideFence = false
            ticks = 0
            start = 0
        }
        var index = start
        while (index < text.length) {
            append(text[index].code)
            index++
        }
        source = text
        preview = tags.text
        return preview
    }

    /** 重置全部状态（切换到另一条消息时调用）。 */
    fun reset() {
        source = ""
        preview = ""
        tags = TagCleaner()
        fenceStart = null
        insideFence = false
        ticks = 0
    }

    private fun append(unit: Int) {
        if (unit != BACKTICK) {
            ticks = 0
            if (!insideFence) fenceStart = null
            tags.append(unit)
            return
        }

        if (!insideFence && ticks == 0) fenceStart = tags.copy()
        ticks++
        if (ticks == 3) {
            ticks = 0
            if (insideFence) {
                // 代码块闭合：回到开围栏之前的状态，用占位符替换整块代码。
                tags = fenceStart!!
                fenceStart = null
                insideFence = false
                for (placeholderUnit in CODE_PLACEHOLDER) tags.append(placeholderUnit.code)
                return
            }
            insideFence = true
        }
        tags.append(unit)
    }

    private companion object {
        const val BACKTICK = 0x60
        const val CAPACITY = 1000

        /** 代码块占位符，与 Dart 侧的 `' [代码] '` 一致。 */
        const val CODE_PLACEHOLDER = " [代码] "

        /** 行首标记字符：`#` `*` `>` `-` `.` 与数字。 */
        fun isMarker(unit: Int): Boolean =
            unit == 0x23 || unit == 0x2A || unit == 0x3E || unit == 0x2D || unit == 0x2E ||
                (unit in 0x30..0x39)

        /**
         * 与 Dart 侧 `_isWhitespace` 完全一致。
         *
         * 注意这里**不含** U+0085（NEL）——Dart 的 `trim` 会去掉它，但正则 `\s` 不会，
         * 上游刻意保持与正则一致，所以 NEL 在 [PreviewText] 里单独处理。
         */
        fun isWhitespace(unit: Int): Boolean =
            unit == 0x20 ||
                (unit in 0x09..0x0D) ||
                unit == 0xA0 ||
                unit == 0x1680 ||
                (unit in 0x2000..0x200A) ||
                unit == 0x2028 ||
                unit == 0x2029 ||
                unit == 0x202F ||
                unit == 0x205F ||
                unit == 0x3000 ||
                unit == 0xFEFF
    }

    // ------------------------------------------------------------------ TagCleaner

    /** 去掉 `<...>`；未闭合的标签按原文逐字显示（保持流式时不吞字）。 */
    private class TagCleaner {
        private var line = LineCleaner()
        private var tagStart: LineCleaner? = null
        private var tagHasBody = false

        val text: String get() = line.text
        val textOffset: Int get() = line.textOffset

        fun copy(): TagCleaner = TagCleaner().also {
            it.line = line.copy()
            it.tagStart = tagStart?.copy()
            it.tagHasBody = tagHasBody
        }

        fun append(unit: Int) {
            if (tagStart != null) {
                if (unit == GT) {
                    if (tagHasBody) {
                        // 是一个完整标签：丢弃它，补一个空格。
                        line = tagStart!!
                        line.append(SPACE)
                    } else {
                        // `<>` 这种空标签：按原文保留。
                        line.append(unit)
                    }
                    tagStart = null
                    tagHasBody = false
                    return
                }
                tagHasBody = true
            } else if (unit == LT) {
                tagStart = line.copy()
            }
            line.append(unit)
        }

        private companion object {
            const val LT = 0x3C
            const val GT = 0x3E
            const val SPACE = 0x20
        }
    }

    // ------------------------------------------------------------------ LineCleaner

    /** 去掉行首的 markdown 标记（`#` `*` `>` `-` `.` 数字）。 */
    private class LineCleaner {
        private var output = PreviewText()
        private var prefixStart: PreviewText? = null
        private var lineStart = true
        private var skipWhitespace = false

        val text: String get() = output.text
        val textOffset: Int get() = output.textOffset

        fun copy(): LineCleaner = LineCleaner().also {
            it.output = output.copy()
            it.prefixStart = prefixStart?.copy()
            it.lineStart = lineStart
            it.skipWhitespace = skipWhitespace
        }

        fun append(unit: Int) {
            val whitespace = isWhitespace(unit)
            if (prefixStart != null) {
                if (whitespace) {
                    // 「疑似行首标记 + 空白」确认成立：回退到标记之前，丢弃标记。
                    output = prefixStart!!
                    prefixStart = null
                    skipWhitespace = true
                } else {
                    // 不是标记字符就否定候选，保留已写入内容。
                    if (!isMarker(unit)) prefixStart = null
                    output.append(unit)
                }
            } else if (skipWhitespace && whitespace) {
                // 标记后的空白被正则一并吃掉。
            } else {
                skipWhitespace = false
                if (lineStart && isMarker(unit)) prefixStart = output.copy()
                output.append(unit)
            }
            lineStart = unit == 0x0A || unit == 0x0D || unit == 0x2028 || unit == 0x2029
        }
    }

    // ------------------------------------------------------------------ PreviewText

    /** 空白折叠：连续空白压成一个空格；尾部空白延迟到下一个可见字符才真正写入。 */
    private class PreviewText {
        private var units = CodeUnitRing()
        private var pendingTrim: CodeUnitRing? = null
        private var pendingSpace = false

        val text: String get() = units.text
        val textOffset: Int get() = units.totalLength - units.length

        fun copy(): PreviewText = PreviewText().also {
            it.units = units.copy()
            it.pendingTrim = pendingTrim?.copy()
            it.pendingSpace = pendingSpace
        }

        fun append(unit: Int) {
            if (isWhitespace(unit)) {
                pendingSpace = true
                return
            }
            // NEL(U+0085)：保留，但不像普通空白那样被折叠掉。
            if (unit == 0x85) {
                if (units.isNotEmpty) {
                    val pending = pendingTrim ?: CodeUnitRing().also { pendingTrim = it }
                    if (pendingSpace) pending.append(SPACE)
                    pending.append(unit)
                }
                pendingSpace = false
                return
            }
            if (units.isNotEmpty) {
                pendingTrim?.let { pending ->
                    // 追加到真正的输出时，把这些待定字符一并提交。
                    units.totalLength += pending.totalLength - pending.length
                    var index = 0
                    while (index < pending.length) {
                        units.append(pending.unitAt(index))
                        index++
                    }
                    pendingTrim = null
                }
                if (pendingSpace) units.append(SPACE)
            }
            pendingSpace = false
            units.append(unit)
        }

        private companion object {
            const val SPACE = 0x20
        }
    }

    // ------------------------------------------------------------------ CodeUnitRing

    /** 定长环形缓冲：O(1) 淘汰最旧码元，不做整体搬移。 */
    private class CodeUnitRing {
        private var units = CharArray(CAPACITY)
        private var head = 0
        var length = 0
            private set
        var totalLength = 0

        val isNotEmpty: Boolean get() = length != 0

        fun unitAt(index: Int): Int = units[(head + index) % units.size].code

        fun append(unit: Int) {
            totalLength++
            if (length == units.size) {
                units[head] = unit.toChar()
                head = (head + 1) % units.size
            } else {
                units[((head + length) % units.size)] = unit.toChar()
                length++
            }
        }

        fun copy(): CodeUnitRing = CodeUnitRing().also {
            it.units = units.copyOf()
            it.head = head
            it.length = length
            it.totalLength = totalLength
        }

        val text: String
            get() = buildString(length) {
                var index = 0
                while (index < length) {
                    append(unitAt(index).toChar())
                    index++
                }
            }
    }

    // 字符分类（isMarker / isWhitespace）已并入上方唯一的 companion object：
    // Kotlin 每个类只允许一个 companion object，第二个会导致其成员在嵌套类中不可解析。
}
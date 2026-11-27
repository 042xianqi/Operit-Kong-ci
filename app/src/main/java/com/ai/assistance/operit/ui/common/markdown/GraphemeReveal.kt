package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.ui.text.TextLayoutResult

/**
 * 流式逐字揭示的「字素簇安全」换算工具。
 *
 * 背景：Kong 的逐字效果（`CanvasMarkdownNodeRenderer` 的 typewriter）按 **UTF-16 码元**推进：
 *
 * ```
 * val revealedCharCount = floor(revealValue).toInt()   // CanvasMarkdownNodeRenderer.kt:926
 * val layoutLength = instruction.layout.text.length     // :1068，也是 UTF-16 长度
 * ```
 *
 * 而 Operit2 是按**字素簇（grapheme cluster）**推进的：
 *
 * ```dart
 * // MarkdownTextReveal.dart:88-92
 * _characterOffsets = [0, for (final character in _text.characters) offset += character.length];
 * ```
 *
 * 两者在纯 ASCII / 中文场景下等价，但在下列字符上不同：
 *
 * - emoji（U+1F600 占 2 个 UTF-16 码元），会先露出半个代理对（渲染为豆腐块）
 * - 组合字符（`é` = `e` + U+0301），会先露出基字符再补上声调
 * - ZWJ 序列（👨‍👩‍👧 占 8 个码元），会露出中间态
 * - 变体选择符（❤️ = U+2764 + U+FE0F），会先露出无样式的 ❤
 *
 * 本文件提供把「码元进度」对齐到「字素簇边界」的换算，供逐字渲染与光标定位共用。
 */

/**
 * 把 [codeUnitOffset] 向下对齐到最近的**字素簇边界**。
 *
 * 语义与 Rust/Flutter 侧一致：一个只揭示了一半的字素簇，整个都不揭示。
 * 因此返回值 `<= codeUnitOffset`，且永远落在字素簇起点上。
 *
 * @param text 完整的段落文本
 * @param codeUnitOffset 按 UTF-16 码元计的揭示进度
 * @return 已经可以完整展示的码元长度
 */
internal fun graphemeAlignedRevealLength(text: CharSequence, codeUnitOffset: Int): Int {
    if (codeUnitOffset <= 0) return 0
    if (codeUnitOffset >= text.length) return text.length

    var index = 0
    while (index < text.length) {
        val next = nextGraphemeBoundary(text, index)
        if (next > codeUnitOffset) return index
        index = next
    }
    return text.length
}

/**
 * 返回从 [start] 开始的下一个**字素簇边界**（即当前字素簇的结束码元下标）。
 *
 * 覆盖 `android.icu.text.BreakIterator` 在常见场景下的主要规则，且不引入 ICU 依赖：
 * 代理对、组合附加符、变体选择符、ZWJ 序列、肤色修饰符、区域指示符（国旗）。
 * 这不是完整 UAX #29 实现，但对聊天正文的实际输入足够；未覆盖的情形退化为「按码点推进」，
 * 即至少不会切断代理对。
 */
internal fun nextGraphemeBoundary(text: CharSequence, start: Int): Int {
    if (start >= text.length) return text.length

    var index = start

    // 1. 先吃一个基础码点（可能是代理对）。
    val first = Character.codePointAt(text, index)
    index += Character.charCount(first)

    // 2. 吃掉紧随其后的组合附加符、变体选择符、肤色修饰符。
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        if (!isGraphemeExtend(codePoint)) break
        index += Character.charCount(codePoint)
    }

    // 3. 处理 ZWJ 序列：U+200D 后面紧跟的码点属于同一个字素簇。
    while (index < text.length && Character.codePointAt(text, index) == ZWJ) {
        val afterZwj = index + Character.charCount(ZWJ)
        if (afterZwj >= text.length) {
            index = afterZwj
            break
        }
        val joined = Character.codePointAt(text, afterZwj)
        index = afterZwj + Character.charCount(joined)
        // 被 ZWJ 连接的码点之后同样可能再挂组合符。
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (!isGraphemeExtend(codePoint)) break
            index += Character.charCount(codePoint)
        }
    }

    // 4. 处理区域指示符（国旗是两个 RI 组成一个字素簇）。
    if (isRegionalIndicator(first) && index < text.length) {
        val nextCodePoint = Character.codePointAt(text, index)
        if (isRegionalIndicator(nextCodePoint)) {
            index += Character.charCount(nextCodePoint)
        }
    }

    return index
}

private const val ZWJ = 0x200D
private const val ZWJ_CHAR = ZWJ.toChar()

/** 组合附加符、变体选择符、肤色修饰符等「附加到前一个字符」的码点。 */
private fun isGraphemeExtend(codePoint: Int): Boolean = when {
    // 组合用附加符号（Combining Diacritical Marks 及扩展）
    codePoint in 0x0300..0x036F -> true
    codePoint in 0x1AB0..0x1AFF -> true
    codePoint in 0x1DC0..0x1DFF -> true
    codePoint in 0x20D0..0x20FF -> true
    codePoint in 0xFE20..0xFE2F -> true
    // 变体选择符（VS1..VS16 与 VS17..VS256）
    codePoint in 0xFE00..0xFE0F -> true
    codePoint in 0xE0100..0xE01EF -> true
    // emoji 肤色修饰符
    codePoint in 0x1F3FB..0x1F3FF -> true
    // emoji tag sequences（如英格兰旗）的标签字符
    codePoint in 0xE0020..0xE007F -> true
    // 注意：ZWJ(U+200D) 不在此列。它必须留给 nextGraphemeBoundary 的第 3 步处理，
    // 否则会被当成普通附加符吃掉，导致 👨‍👩‍👧 被切成三段。
    else -> false
}

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

/**
 * 计算光标在**已揭示文本末尾**的位置。
 *
 * 对应 Operit2 `MarkdownTextReveal.dart:190-209` 的 `_cursorOffset`：
 * 取第 `index` 个字符的插入点与第 `index + 1` 个字符的插入点做线性插值，
 * 再按行高把光标垂直居中。差异是这里按码元对齐到字素簇，避免光标停在半个 emoji 中间。
 *
 * @param layout 当前的文本布局结果
 * @param text 该布局对应的文本
 * @param revealedLength 已经揭示的码元长度（应已通过 [graphemeAlignedRevealLength] 对齐）
 * @param cursorHeightPx 光标高度（像素）
 * @return 光标的左上角坐标（相对该 layout 的坐标系）
 */
internal fun cursorPositionAt(
    layout: TextLayoutResult,
    text: CharSequence,
    revealedLength: Int,
    cursorHeightPx: Float,
): androidx.compose.ui.geometry.Offset {
    val alignedLength = graphemeAlignedRevealLength(text, revealedLength)

    // 插入点取「下一个字素簇起点」，这样光标停在一个完整字符之后。
    val nextBoundary = nextGraphemeBoundary(text, alignedLength)
    val caretIndex = alignedLength.coerceIn(0, layout.layoutInput.text.length)
    val caretRect = layout.getCursorRect(caretIndex)
    val nextRect = layout.getCursorRect(nextBoundary.coerceIn(0, layout.layoutInput.text.length))

    val x = if (nextBoundary > alignedLength) (caretRect.left + nextRect.left) / 2f else caretRect.left
    val lineHeight = caretRect.height.takeIf { it > 0f } ?: cursorHeightPx
    val y = caretRect.top + (lineHeight - cursorHeightPx) / 2f

    return androidx.compose.ui.geometry.Offset(x, y)
}

/** 该文本是否需要按字素簇处理（纯 ASCII 时可直接走快路径，避免逐字循环开销）。 */
internal fun needsGraphemeAwareReveal(text: CharSequence): Boolean {
    for (index in text.indices) {
        val char = text[index]
        if (char.isLowSurrogate() || char.isHighSurrogate()) return true
        // 组合区、变体选择符、ZWJ
        if (char in '\u0300'..'\u036F') return true
        if (char in '\uFE00'..'\uFE0F') return true
        if (char == ZWJ_CHAR) return true
    }
    return false
}

/**
 * 计算光标在 `StaticLayout` 中「已揭示文本末尾」的位置，供 Canvas 逐字渲染使用。
 *
 * `CanvasMarkdownNodeRenderer` 走的是 `android.text.StaticLayout`（不是 Compose 的
 * `TextLayoutResult`），所以需要单独一份实现。语义与 [cursorPositionAt] 一致。
 *
 * @param layout 该段落的 StaticLayout
 * @param text 该 layout 对应的文本（用于字素簇对齐）
 * @param revealedLength 已揭示的码元长度（相对 layout 起点）
 * @param cursorHeightPx 光标高度（px）
 * @param cursorWidthPx 光标宽度（px）
 * @param outRect 输出矩形，避免每帧分配
 */
internal fun fillCursorRect(
    layout: android.text.Layout,
    text: CharSequence,
    revealedLength: Int,
    cursorHeightPx: Float,
    cursorWidthPx: Float,
    outRect: android.graphics.RectF,
) {
    val aligned = graphemeAlignedRevealLength(text, revealedLength)
        .coerceIn(0, layout.text.length)

    // 光标停在一个完整字素簇之后：取该簇的右边界。
    val nextBoundary = nextGraphemeBoundary(text, aligned)
        .coerceIn(aligned, layout.text.length)

    val line = layout.getLineForOffset(aligned)
    val top = layout.getLineTop(line).toFloat()
    val bottom = layout.getLineBottom(line).toFloat()
    val lineHeight = bottom - top

    val x = if (nextBoundary > aligned && layout.getLineForOffset(nextBoundary) == line) {
        // 同一行内：取该字素簇结束处的水平位置
        layout.getPrimaryHorizontal(nextBoundary)
    } else {
        // 跨行或到达行尾：退回起始位置
        layout.getPrimaryHorizontal(aligned)
    }

    val y = top + ((lineHeight - cursorHeightPx) / 2f).coerceAtLeast(0f)
    outRect.set(x, y, x + cursorWidthPx, y + cursorHeightPx)
}

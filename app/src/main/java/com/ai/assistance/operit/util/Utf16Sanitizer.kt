package com.ai.assistance.operit.util

/**
 * 把孤立的 UTF-16 代理码元替换为 U+FFFD。
 *
 * 为什么需要：流式输出按任意字节边界切分，一个 4 字节的 emoji（如 U+1F600）可能被切成
 * 两个 chunk，导致某次渲染拿到只有「高代理」而没有「低代理」的半截字符。
 * Android 的 `StaticLayout` / Compose 文本布局在遇到孤立代理时的行为未定义：
 * 轻则渲染成豆腐块，重则在某些字体上抛 `IllegalArgumentException`。
 *
 * 对应 Operit2 `apps/flutter/app/lib/ui/features/chat/components/part/Utf16Text.dart`
 * 的 `sanitizeUtf16`，调用点在 `CustomXmlRenderer.dart:1909`（`_ParsedXml.from` 入口）。
 *
 * 实现要点：
 * - 只替换**孤立**的半边；成对的代理原样保留（emoji 不能被破坏）。
 * - 不改变字符串长度，这样上游基于下标的偏移（token 估算、滚动位置）不需要重算。
 */
internal fun String.sanitizeUtf16(): String {
    // 快路径：绝大多数文本没有代理码元，直接返回原串避免分配。
    var hasSurrogate = false
    for (index in indices) {
        if (this[index].isSurrogate()) {
            hasSurrogate = true
            break
        }
    }
    if (!hasSurrogate) return this

    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() -> {
                val next = if (index + 1 < length) this[index + 1] else '\u0000'
                if (next.isLowSurrogate()) {
                    // 成对：原样保留。
                    builder.append(char).append(next)
                    index += 2
                } else {
                    // 孤立高代理：替换。
                    builder.append(REPLACEMENT)
                    index += 1
                }
            }
            char.isLowSurrogate() -> {
                // 走到这里说明它前面不是高代理（否则已在上面被消费），即孤立低代理。
                builder.append(REPLACEMENT)
                index += 1
            }
            else -> {
                builder.append(char)
                index += 1
            }
        }
    }
    return builder.toString()
}

private const val REPLACEMENT = '\uFFFD'
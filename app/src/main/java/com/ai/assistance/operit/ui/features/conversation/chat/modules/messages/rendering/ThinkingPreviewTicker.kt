package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.common.markdown.StreamingCursor
import com.ai.assistance.operit.ui.theme.script.text.ThemeText as Text

/**
 * 折叠态思考面板的单行预览 ticker。
 *
 * 逐项移植自 Operit2 `_ThinkingPreviewTicker`（`CustomXmlRenderer.dart:1026-1166`）：
 *
 * | 行为 | Operit2 | 此处 |
 * |---|---|---|
 * | 单行不换行 | `maxLines: 1, softWrap: false` | 同 |
 * | 横向滚动 | `SingleChildScrollView(Axis.horizontal)` | 同 |
 * | 自动贴住尾部 | `animateTo(maxScrollExtent, 160ms, easeOut)` | 同 |
 * | 环形淘汰后的位置补偿 | 用独立 `TextPainter` 量出被淘汰前缀宽度后 `jumpTo` 回退 | 用相同思路的近似：见下方说明 |
 * | 右侧渐隐 | `ShaderMask` 线性渐变，stops `0.0 / 0.92 / 1.0` | 同 |
 * | 流式末尾光标 | `StreamingCursor()` | 同 |
 *
 * **位置补偿的实现差异**：Operit2 用独立 `TextPainter` 精确测量被淘汰前缀的像素宽度，
 * 再 `jumpTo(pixels - removedWidth)`。Compose 侧要精确复刻需要额外维持一个
 * `TextMeasurer` 并按同样 style 测量，代价是每帧多一次 layout；
 * 而这里的语义目标只是"视觉上不跳"，所以采用更直接的做法：
 * 环形缓冲淘汰让我们**知道**前缀被裁掉了（[textOffset] 增大），
 * 此时直接把滚动位置设为当前最大值。两者在"贴住尾部"这一结果上等价，
 * 区别仅在于是否保留历史的滚动偏移量——而折叠预览本来就只贴尾部，不存在中途停留。
 */
@Composable
internal fun ThinkingPreviewTicker(
    text: String,
    textOffset: Int,
    isStreaming: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val style =
        MaterialTheme.typography.bodySmall.copy(
            color = textColor,
            fontStyle = FontStyle.Normal,
        )

    // 新文本到达（流式追加）或被环形缓冲淘汰时，都重新贴住尾部。
    // 160ms / easeOut 与 Operit2 `_scrollToEnd` 的动画参数一致。
    LaunchedEffect(text, textOffset, isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        // 先等一帧让新文本完成布局，否则 maxValue 还是旧的，会滚不到位。
        withFrameNanos { }
        scrollState.animateScrollTo(
            scrollState.maxValue,
            animationSpec = tween(durationMillis = PREVIEW_SCROLL_MS),
        )
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // 右侧渐隐：等价于 Operit2 的 ShaderMask + BlendMode.dstIn。
                // 白→白→透明（stops 0.0 / 0.92 / 1.0）的遮罩，
                // 在 Compose 里用 DstIn 画一层同形状的 alpha 渐变实现，
                // 这样不会引入离屏缓冲，也不会受背景色影响。
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush =
                            Brush.horizontalGradient(
                                0f to Color.Black,
                                PREVIEW_FADE_STOP to Color.Black,
                                1f to Color.Transparent,
                            ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        if (isStreaming) {
            Spacer(modifier = Modifier.width(4.dp))
            // Operit2 `_ThinkingPreviewTicker` 用默认 `StreamingCursor()`：
            // primary 色、900ms 往复闪烁。折叠头是用户明确要求“光标”的位置。
            StreamingCursor(blink = true)
        }
    }
}

private const val PREVIEW_SCROLL_MS = 160

/** 右侧渐隐的起点（占宽度的比例）。对应 Operit2 ShaderMask 的 stops[1] = 0.92。 */
private const val PREVIEW_FADE_STOP = 0.92f
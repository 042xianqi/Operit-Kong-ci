package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 流式输出末尾的光标。
 *
 * 规格对应 Operit2 `CanvasMarkdownNodeRenderer.dart:796-855` 的 `StreamingCursor`：
 *
 * | 项 | 值 |
 * |---|---|
 * | 尺寸 | 7 × 16 dp |
 * | 圆角 | 2 dp |
 * | 颜色 | `colorScheme.primary` |
 * | 闪烁周期 | 900 ms，往复 |
 * | 透明度范围 | 0.25 ↔ 0.85 |
 * | 不闪烁时 | 固定 0.85 |
 *
 * 注意：Operit2 在 `MarkdownTextReveal` 内用的是 `StreamingCursor(blink: false)`（固定 0.85），
 * 因为逐字动画本身已经在动，再叠加闪烁会显得吵。默认值与之一致。
 *
 * @param blink 是否闪烁。逐字揭示进行中建议传 false。
 * @param color 覆盖颜色；默认取主题 primary。
 */
@Composable
fun StreamingCursor(
    modifier: Modifier = Modifier,
    blink: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // 不闪烁时直接给固定透明度，避免无谓的无限动画占用一帧回调。
    val opacity: Float = if (!blink) {
        IDLE_OPACITY
    } else {
        val transition = rememberInfiniteTransition(label = "streaming-cursor")
        val animated by transition.animateFloat(
            initialValue = BLINK_MIN_OPACITY,
            targetValue = BLINK_MAX_OPACITY,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = BLINK_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "streaming-cursor-opacity",
        )
        animated
    }

    Box(
        modifier = modifier
            .size(width = CURSOR_WIDTH, height = CURSOR_HEIGHT)
            .graphicsLayer { alpha = opacity }
            .background(color = color, shape = CURSOR_SHAPE),
    )
}

private val CURSOR_WIDTH = 7.dp
private val CURSOR_HEIGHT = 16.dp
private val CURSOR_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

private const val BLINK_PERIOD_MS = 900
private const val BLINK_MIN_OPACITY = 0.25f
private const val BLINK_MAX_OPACITY = 0.85f
private const val IDLE_OPACITY = 0.85f
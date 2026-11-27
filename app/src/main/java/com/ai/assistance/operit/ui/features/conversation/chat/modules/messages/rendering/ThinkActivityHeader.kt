package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.theme.script.text.ThemeText as Text

/**
 * 思考面板折叠头，逐项对齐 Operit2 `_ThinkPanel.build`（CustomXmlRenderer.dart:704-780）。
 *
 * | 元素 | Operit2 | 此处 |
 * |---|---|---|
 * | 图标 | `Icons.psychology_outlined`，16dp | 同 |
 * | 图标间距 | `ChatActivityRowStyle.iconGap` = 8dp | 同 |
 * | 标题 | `_ThinkingTitle`，流式时扫光 1400ms 循环 | [ThinkingTitleWithSweep] |
 * | 分隔符 | 折叠且预览非空时 `·`，左右各 6dp | 同 |
 * | 预览 | `Expanded(_ThinkingPreviewTicker)` | 同（`Modifier.weight(1f)`） |
 * | 颜色 | 流式 primary；否则 onSurfaceVariant（图标 alpha 0.8，预览 alpha 0.7） | 同 |
 *
 * 与旧实现的关键差别：旧版复用 `CanvasExpandableHeaderRow`（箭头图标、标题占满整行），
 * 预览 ticker 作为未加权子项先被测量，拿走了整行宽度，标题本身反而被挤成 0 宽。
 * 这里标题只占自身宽度，ticker 用剩余宽度。
 */
@Composable
internal fun ThinkActivityHeader(
    title: String,
    streaming: Boolean,
    expanded: Boolean,
    previewText: String,
    previewOffset: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand)
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor = if (streaming) primary else onSurfaceVariant.copy(alpha = 0.8f)
    val titleColor = if (streaming) primary else onSurfaceVariant
    val previewColor = onSurfaceVariant.copy(alpha = 0.7f)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = action
                    role = Role.Button
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onToggle,
                )
                // ChatActivityRowStyle.headerPadding
                .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Psychology,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ThinkingTitleWithSweep(text = title, color = titleColor, streaming = streaming)
        if (!expanded && previewText.isNotEmpty()) {
            Text(
                text = "·",
                color = MaterialTheme.colorScheme.outlineVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            ThinkingPreviewTicker(
                text = previewText,
                textOffset = previewOffset,
                isStreaming = streaming,
                textColor = previewColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 标题扫光。Operit2 `_ThinkingTitle`（CustomXmlRenderer.dart:900-1000）：
 * 1400ms 循环，扫光带宽度 0.9×标题宽，路径 [-1.9, 1.0]。
 *
 * 用两层文字实现，与 Flutter 的 `ShaderMask(srcIn)` 等价：
 * 底层常显标题；上层白色文字在独立图层里被渐变 `SrcIn` 着色，
 * 渐变透明的区域把上层清掉，于是只有扫光带可见。
 * 非流式分支不创建任何动画，流式结束即退出组合（不泄漏无限动画）。
 */
@Composable
private fun ThinkingTitleWithSweep(text: String, color: Color, streaming: Boolean) {
    val style =
        MaterialTheme.typography.bodySmall.copy(
            color = color,
            fontWeight = FontWeight.W400,
        )
    if (!streaming) {
        Text(text = text, style = style, maxLines = 1)
        return
    }
    val transition = rememberInfiniteTransition(label = "thinkingTitleSweep")
    val value by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "thinkingTitleSweepValue",
        )
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)

    Box {
        Text(text = text, style = style, maxLines = 1)
        Text(
            text = text,
            style = style.copy(color = Color.White),
            maxLines = 1,
            modifier =
                Modifier
                    // 独立图层：SrcIn 只清理这一层的像素，不影响下面的常显标题。
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val bandWidth = size.width * HIGHLIGHT_BAND_WIDTH
                        val bandStart = size.width * (HIGHLIGHT_PATH_START + HIGHLIGHT_PATH_RANGE * value)
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    0.5f to highlight,
                                    1f to Color.Transparent,
                                    startX = bandStart,
                                    endX = bandStart + bandWidth,
                                ),
                            blendMode = BlendMode.SrcIn,
                        )
                    },
        )
    }
}

private const val HIGHLIGHT_BAND_WIDTH = 0.9f
private const val HIGHLIGHT_PATH_START = -1.9f
private const val HIGHLIGHT_PATH_RANGE = 2.9f

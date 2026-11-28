package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

import com.ai.assistance.operit.ui.theme.script.text.drawThemeText
import com.ai.assistance.operit.ui.theme.script.text.themeTextStroke
import com.ai.assistance.operit.ui.theme.script.text.LocalThemeTextStroke
import com.ai.assistance.operit.ui.theme.script.themeScriptTextStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.ai.assistance.operit.R
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.ui.common.markdown.StreamingCursor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import kotlin.math.max

private const val CANVAS_SINGLE_LINE_TEXT_LIMIT = 160

@Composable
internal fun ToolSummaryRow(
    toolName: String,
    summary: String,
    semanticDescription: String,
    leadingIcon: ImageVector,
    titleColor: Color,
    summaryColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingText: String = "",
    isStreaming: Boolean = false,
    /**
     * 工具执行结果状态，对应 Operit2 `ToolCallRow.isSuccess`。
     * null 表示尚未配对到结果（流式中或未合并），此时不画状态图标。
     */
    isSuccess: Boolean? = null,
    /** 详情面板是否展开，对应 Operit2 `_ToolCallRowState._expanded`。 */
    expanded: Boolean = false,
) {
    // 对应 Operit2 `ToolCallRow` (`ToolDisplayComponents.dart:432-489`)：
    // Compose Row 自然流式排版，标题自适应宽度，不再用 Canvas 像素级定位，
    // 也不再钳位 80~120dp（那是折行的根因）。
    val rowColor =
        if (isSuccess == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.W400, color = rowColor)
    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier =
        if (onClick != null) {
            Modifier
                .semantics {
                    contentDescription = semanticDescription
                    role = Role.Button
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                )
        } else {
            Modifier.semantics { contentDescription = semanticDescription }
        }

    // 展开箭头旋转 220ms，与 ChatActivityDisclosure 同值。
    val chevronTurns by animateFloatAsState(
        targetValue = if (expanded) 0.25f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tool-chevron",
    )

    Row(
        // Operit2 `ChatActivityRowStyle.headerPadding`：vertical 5。
        modifier = modifier.fillMaxWidth().padding(vertical = TOOL_ROW_HEADER_PAD).then(clickableModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = rowColor,
            modifier = Modifier.size(TOOL_ROW_ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(TOOL_ROW_ICON_GAP))
        // 工具名占据自己的列：多个工具行叠在一起时，右侧的状态标记能够对齐成一条竖线。
        // Operit2 `ToolDisplayComponents.dart:453`：工具名是自然宽度，不分配权重。
        // 分配权重会让每一行的文字起点随内容长度浮动，无法对齐成一条直线。
        Text(
            text = toolName,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingText.isNotEmpty()) {
            Spacer(modifier = Modifier.width(TOOL_ROW_ICON_GAP))
            Text(
                text = trailingText,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else if (summary.isNotEmpty()) {
            Spacer(modifier = Modifier.width(TOOL_ROW_ICON_GAP))
            Text(
                text = summary,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // Operit2：流式中显示光标，否则显示状态图标（14dp）。
        if (isStreaming) {
            Spacer(modifier = Modifier.width(TOOL_ROW_CURSOR_GAP))
            StreamingCursor(blink = true)
        } else if (isSuccess != null) {
            val statusText =
                if (isSuccess) {
                    stringResource(R.string.tool_status_completed)
                } else {
                    stringResource(R.string.tool_status_failed)
                }
            Spacer(modifier = Modifier.width(TOOL_ROW_CURSOR_GAP))
            Icon(
                imageVector = if (isSuccess) Icons.Filled.Check else Icons.Filled.ErrorOutline,
                contentDescription = statusText,
                tint = if (isSuccess) rowColor else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(TOOL_ROW_STATUS_SIZE),
            )
        }

        if (onClick != null) {
            Spacer(modifier = Modifier.width(TOOL_ROW_CURSOR_GAP))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = rowColor,
                modifier = Modifier.size(TOOL_ROW_ICON_SIZE).rotate(chevronTurns * 90f),
            )
        }
    }
}

/** Operit2 `ChatActivityRowStyle`：iconSize 16 / iconGap 8 / headerPadding vertical 5。 */
private val TOOL_ROW_ICON_SIZE = 16.dp
private val TOOL_ROW_ICON_GAP = 8.dp
private val TOOL_ROW_STATUS_SIZE = 14.dp
private val TOOL_ROW_CURSOR_GAP = 4.dp
private val TOOL_ROW_HEADER_PAD = 5.dp

/**
 * 结果行的内缩。Operit2 `ChatActivityDisclosure.contentInset`
 * = iconSize + iconGap = 16 + 8 = 24，左内缩对齐摘要行的图标右缘；右侧取 16。
 */
private val TOOL_RESULT_INSET_START = 24.dp
private val TOOL_RESULT_INSET_END = 16.dp
/** 尾部复制按钮的点击/占位宽度（图标本身用 TOOL_ROW_STATUS_SIZE）。 */
private val TOOL_ROW_COPY_SLOT = 24.dp

private fun String.toCanvasSingleLineText(): String {
    val normalized = replace('\r', ' ').replace('\n', ' ').trim()
    return if (normalized.length <= CANVAS_SINGLE_LINE_TEXT_LIMIT) {
        normalized
    } else {
        normalized.take(CANVAS_SINGLE_LINE_TEXT_LIMIT) + "..."
    }
}

@Composable
internal fun CanvasToolResultRow(
    summary: String,
    isSuccess: Boolean,
    semanticDescription: String,
    modifier: Modifier = Modifier,
    emphasizeSummary: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null,
) {
    val textStroke = themeTextStroke("toolCard")
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val arrowPainter = rememberVectorPainter(Icons.Default.SubdirectoryArrowRight)
    val statusPainter =
        rememberVectorPainter(
            if (isSuccess) {
                Icons.Default.Check
            } else {
                Icons.Default.Close
            }
        )
    val copyPainter = rememberVectorPainter(Icons.Default.ContentCopy)
    val summaryColor =
        if (isSuccess) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        }
    val summaryStyle =
        MaterialTheme.typography.bodySmall.copy(
            color = summaryColor,
        )
    val leadingTint =
        if (isSuccess) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        }
    val statusTint =
        if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val copyTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val rowInteractionSource = remember { MutableInteractionSource() }
    val copyInteractionSource = remember { MutableInteractionSource() }
    val rowModifier =
        if (onClick != null) {
            Modifier
                .semantics {
                    contentDescription = semanticDescription
                    role = Role.Button
                }
                .indication(rowInteractionSource, ripple())
                .clickable(
                    interactionSource = rowInteractionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
        } else {
            Modifier.semantics { contentDescription = semanticDescription }
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .then(rowModifier)
    ) {
        val widthPx = with(density) { maxWidth.roundToPx() }.fastCoerceAtLeast(1)
        val startPaddingPx = with(density) { TOOL_RESULT_INSET_START.roundToPx() }
        val endPaddingPx = with(density) { TOOL_RESULT_INSET_END.roundToPx() }
        val verticalPaddingPx = with(density) { TOOL_ROW_HEADER_PAD.roundToPx() }
        val arrowSizePx = with(density) { TOOL_ROW_ICON_SIZE.roundToPx().toFloat() }
        val statusSizePx = with(density) { TOOL_ROW_STATUS_SIZE.roundToPx().toFloat() }
        val gapPx = with(density) { TOOL_ROW_ICON_GAP.roundToPx().toFloat() }
        val trailingSlotPx = if (onCopyClick != null) with(density) { TOOL_ROW_COPY_SLOT.roundToPx() } else 0
        val trailingIconPx = with(density) { TOOL_ROW_STATUS_SIZE.roundToPx().toFloat() }

        val summaryWidthPx =
            (widthPx - startPaddingPx - endPaddingPx - arrowSizePx.toInt() - gapPx.toInt() -
                statusSizePx.toInt() - gapPx.toInt() - trailingSlotPx)
                .fastCoerceAtLeast(0)
        val summaryLayout =
            remember(summary, summaryStyle, textMeasurer, summaryWidthPx) {
                textMeasurer.measure(
                    text = AnnotatedString(summary),
                    style = summaryStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = summaryWidthPx),
                )
            }

        val contentHeightPx =
            max(
                max(arrowSizePx.toInt(), statusSizePx.toInt()),
                max(summaryLayout.size.height, trailingSlotPx),
            )
        val totalHeightPx = verticalPaddingPx * 2 + contentHeightPx
        val trailingStartPx = widthPx - endPaddingPx - trailingSlotPx

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { totalHeightPx.toDp() })
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                val contentTop = verticalPaddingPx.toFloat()
                val arrowTop = contentTop + (contentHeightPx - arrowSizePx) / 2f
                translate(left = startPaddingPx.toFloat(), top = arrowTop) {
                    with(arrowPainter) {
                        draw(
                            size = Size(arrowSizePx, arrowSizePx),
                            colorFilter = ColorFilter.tint(leadingTint),
                        )
                    }
                }

                val statusLeft = startPaddingPx + arrowSizePx + gapPx
                val statusTop = contentTop + (contentHeightPx - statusSizePx) / 2f
                translate(left = statusLeft, top = statusTop) {
                    with(statusPainter) {
                        draw(
                            size = Size(statusSizePx, statusSizePx),
                            colorFilter = ColorFilter.tint(statusTint),
                        )
                    }
                }

                val textX = statusLeft + statusSizePx + gapPx
                val textY = contentTop + (contentHeightPx - summaryLayout.size.height) / 2f
                drawThemeText(summaryLayout, topLeft = Offset(textX, textY), textStroke = textStroke)

                if (onCopyClick != null) {
                    val copyLeft = widthPx - endPaddingPx - trailingSlotPx + (trailingSlotPx - trailingIconPx) / 2f
                    val copyTop = contentTop + (contentHeightPx - trailingIconPx) / 2f
                    translate(left = copyLeft, top = copyTop) {
                        with(copyPainter) {
                            draw(
                                size = Size(trailingIconPx, trailingIconPx),
                                colorFilter = ColorFilter.tint(copyTint),
                            )
                        }
                    }
                }
            }

            if (onCopyClick != null && trailingSlotPx > 0) {
                Box(
                    modifier =
                        Modifier
                            .offset(x = with(density) { trailingStartPx.toDp() })
                            .width(with(density) { trailingSlotPx.toDp() })
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = "$semanticDescription, copy"
                                role = Role.Button
                            }
                            .indication(copyInteractionSource, ripple(bounded = false))
                            .clickable(
                                interactionSource = copyInteractionSource,
                                indication = null,
                                role = Role.Button,
                                onClick = onCopyClick,
                            )
                )
            }
        }
    }
}

@Composable
internal fun CanvasStatusCard(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    /** 是否是流式尾部：为 true 时在卡片右侧画光标（对应 Operit2 `_StatusCard.isStreaming`）。 */
    isStreaming: Boolean = false,
) {
    val textStroke = themeTextStroke("toolCard")
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodySmall.copy(color = textColor)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }.fastCoerceAtLeast(1)
        val horizontalPaddingPx = with(density) { 12.dp.roundToPx() }
        val verticalPaddingPx = with(density) { 12.dp.roundToPx() }
        val outerVerticalPx = with(density) { 4.dp.roundToPx() }
        val cornerRadiusPx = with(density) { 8.dp.toPx() }
        val borderWidthPx = with(density) { 1.dp.toPx() }
        // 光标位与 Operit2 的 Padding(left:6) 一致：文字区先让出 7dp 光标 + 6dp 间距。
        val cursorSlotPx =
            if (isStreaming) with(density) { (7.dp + 6.dp).roundToPx() } else 0

        val textLayout =
            remember(text, textStyle, textMeasurer, widthPx, horizontalPaddingPx, cursorSlotPx) {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = textStyle,
                    constraints =
                        Constraints(
                            maxWidth =
                                (widthPx - horizontalPaddingPx * 2 - cursorSlotPx)
                                    .fastCoerceAtLeast(0)
                        ),
                )
            }
        val cardHeightPx = textLayout.size.height + verticalPaddingPx * 2
        val totalHeightPx = cardHeightPx + outerVerticalPx * 2

        Box(modifier = Modifier.fillMaxWidth().height(with(density) { totalHeightPx.toDp() })) {
            Canvas(modifier = Modifier.fillMaxWidth().height(with(density) { totalHeightPx.toDp() })) {
                val cardTop = outerVerticalPx.toFloat()
                drawRoundRect(
                    color = backgroundColor,
                    topLeft = Offset(0f, cardTop),
                    size = Size(size.width, cardHeightPx.toFloat()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                )
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(0f, cardTop),
                    size = Size(size.width, cardHeightPx.toFloat()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = borderWidthPx),
                )

                drawThemeText(
                    textLayout,
                    topLeft = Offset(horizontalPaddingPx.toFloat(), cardTop + verticalPaddingPx), textStroke = textStroke)
            }
            if (isStreaming) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                ) {
                    StreamingCursor(blink = true)
                }
            }
        }
    }
}

@Composable
internal fun CanvasWarningStatusRow(
    summaryText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** 是否是流式尾部：为 true 时在行尾画光标（对应 Operit2 `_WarningStatusRow.isStreaming`）。 */
    isStreaming: Boolean = false,
) {
    val textStroke = themeTextStroke("toolCard")
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    val textColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val textStyle = MaterialTheme.typography.bodySmall.copy(color = textColor)
    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier =
        if (onClick != null) {
            Modifier
                .semantics { role = Role.Button }
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                )
        } else {
            Modifier
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .then(clickableModifier)
    ) {
        val widthPx = with(density) { maxWidth.roundToPx() }.fastCoerceAtLeast(1)
        val barWidthPx = with(density) { 2.dp.toPx() }
        val barHeightPx = with(density) { 16.dp.toPx() }
        val gapPx = with(density) { 8.dp.roundToPx().toFloat() }
        val verticalPaddingPx = with(density) { 6.dp.roundToPx() }
        val cornerRadiusPx = with(density) { 999.dp.toPx() }
        // 光标位与 Operit2 的 Padding(left:6) 一致。
        val cursorSlotPx =
            if (isStreaming) with(density) { (7.dp + 6.dp).roundToPx() } else 0

        val textLayout =
            remember(summaryText, textStyle, textMeasurer, widthPx, gapPx, barWidthPx, cursorSlotPx) {
                textMeasurer.measure(
                    text = AnnotatedString(summaryText),
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints =
                        Constraints(
                            maxWidth =
                                (widthPx - barWidthPx.toInt() - gapPx.toInt() - cursorSlotPx)
                                    .fastCoerceAtLeast(0)
                        ),
                )
            }

        val contentHeightPx = max(barHeightPx.toInt(), textLayout.size.height)
        val totalHeightPx = verticalPaddingPx * 2 + contentHeightPx

        Box(modifier = Modifier.fillMaxWidth().height(with(density) { totalHeightPx.toDp() })) {
            Canvas(modifier = Modifier.fillMaxWidth().height(with(density) { totalHeightPx.toDp() })) {
                val top = verticalPaddingPx.toFloat()
                val barTop = top + (contentHeightPx - barHeightPx) / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(0f, barTop),
                    size = Size(barWidthPx, barHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                )

                drawThemeText(
                    textLayout,
                    topLeft = Offset(barWidthPx + gapPx, top + (contentHeightPx - textLayout.size.height) / 2f), textStroke = textStroke)
            }
            if (isStreaming) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    StreamingCursor(blink = true)
                }
            }
        }
    }
}

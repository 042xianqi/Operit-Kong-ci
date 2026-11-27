package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.stream.Stream

sealed class MarkdownGroupedItem {
    data class Single(val index: Int) : MarkdownGroupedItem()

    data class Group(
        val startIndex: Int,
        val endIndexInclusive: Int,
        val stableKey: String
    ) : MarkdownGroupedItem()

    /**
     * 一个 `&lt;tool&gt;` 调用与它配对到的 `&lt;tool_result&gt;` 合成一行。
     *
     * 对应 Operit2 `ToolCallResultMergeRender.dart` 的 `MergedToolCallResultRow`：
     *   - `callIndex` 一定有效
     *   - `resultIndex` 为 -1 表示结果尚未到达（流式中）
     * 配对由 Rust 侧 `NativeXmlGroup.plan()` 计算，Kotlin 只负责按结果渲染。
     */
    data class Merged(
        val callIndex: Int,
        val resultIndex: Int,
        val stableKey: String,
        val isStreaming: Boolean,
    ) : MarkdownGroupedItem()
}

interface MarkdownNodeGrouper {
    /** 分组和单独节点可共享消息级渲染信息，默认不增加额外行为。 */
    @Composable
    fun RenderScope(nodes: List<MarkdownNodeStable>, content: @Composable () -> Unit) { content() }

    fun group(nodes: List<MarkdownNodeStable>, rendererId: String): List<MarkdownGroupedItem>

    @Composable
    fun RenderGroup(
        group: MarkdownGroupedItem.Group,
        nodes: List<MarkdownNodeStable>,
        rendererId: String,
        isVisible: Boolean,
        isLastNode: Boolean,
        modifier: Modifier,
        textColor: Color,
        onLinkClick: ((String) -> Unit)?,
        xmlRenderer: XmlContentRenderer,
        xmlStreamResolver: (Int) -> Stream<String>?,
        fillMaxWidth: Boolean,
        fontSize: TextUnit
    )
}

object NoopMarkdownNodeGrouper : MarkdownNodeGrouper {
    override fun group(nodes: List<MarkdownNodeStable>, rendererId: String): List<MarkdownGroupedItem> {
        return nodes.indices.map { MarkdownGroupedItem.Single(it) }
    }

    @Composable
    override fun RenderGroup(
        group: MarkdownGroupedItem.Group,
        nodes: List<MarkdownNodeStable>,
        rendererId: String,
        isVisible: Boolean,
        isLastNode: Boolean,
        modifier: Modifier,
        textColor: Color,
        onLinkClick: ((String) -> Unit)?,
        xmlRenderer: XmlContentRenderer,
        xmlStreamResolver: (Int) -> Stream<String>?,
        fillMaxWidth: Boolean,
        fontSize: TextUnit
    ) {
    }
}

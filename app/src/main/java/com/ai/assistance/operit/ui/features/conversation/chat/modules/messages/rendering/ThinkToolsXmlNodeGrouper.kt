package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

import androidx.compose.runtime.CompositionLocalProvider
import com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering.tooltiming.ToolTimingIndex
import com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering.tooltiming.LocalToolTimingIndex
import com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering.tooltiming.LocalInsideToolGroup
import com.ai.assistance.operit.ui.common.markdown.lazy.LocalMarkdownCardNodeIndex
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.preferences.ToolCollapseMode
import com.ai.assistance.operit.core.chat.tools.timing.ToolCallExecution
import com.ai.assistance.operit.ui.common.markdown.LocalMarkdownRenderMode
import com.ai.assistance.operit.ui.common.markdown.LocalXmlBlockStreaming
import com.ai.assistance.operit.ui.common.markdown.MarkdownRenderMode
import com.ai.assistance.operit.ui.common.markdown.MarkdownGroupedItem
import com.ai.assistance.operit.ui.common.markdown.MarkdownNodeGrouper
import com.ai.assistance.operit.ui.common.markdown.NativeXmlGroup
import androidx.compose.material3.MaterialTheme
import com.ai.assistance.operit.ui.common.markdown.lazy.markdownNodeIdentityKeys
import com.ai.assistance.operit.ui.common.markdown.XmlContentRenderer
import com.ai.assistance.operit.core.chat.protocol.ChatMarkupRegex
import com.ai.assistance.operit.core.chat.protocol.ThinkingMarkup
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import com.ai.assistance.operit.util.stream.Stream

class ThinkToolsXmlNodeGrouper(
    private val showThinkingProcess: Boolean,
    private val forceExpandGroups: Boolean = false,
    private val toolCollapseMode: ToolCollapseMode = ToolCollapseMode.ALL,
    private val groupProcessNodes: Boolean = true,
) : MarkdownNodeGrouper {
    // 聊天列表可注入消息持有的展开选择，其他完整渲染入口自行持有状态。
    internal var expansionOverrides = mutableStateMapOf<String, Boolean>()

    internal var executionTimings by mutableStateOf<List<ToolCallExecution>>(emptyList())
    private var timingNodes: List<Pair<MarkdownProcessorType, String>>? = null
    private var timingRecords: List<ToolCallExecution>? = null
    private var timingIndex = ToolTimingIndex.Empty
    private fun timingFor(nodes: List<MarkdownNodeStable>): ToolTimingIndex {
        val snapshot = nodes.map { it.type to it.content }
        val records = executionTimings
        if (timingNodes != snapshot || timingRecords != records) {
            timingIndex = ToolTimingIndex.parse(nodes, records)
            timingNodes = snapshot
            timingRecords = records
        }
        return timingIndex
    }
    @Composable
    override fun RenderScope(nodes: List<MarkdownNodeStable>, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalToolTimingIndex provides timingFor(nodes)) { content() }
    }

    override fun group(nodes: List<MarkdownNodeStable>, rendererId: String): List<MarkdownGroupedItem> {
        // Waifu 的逻辑过程段持续增长，节点仍逐项懒加载，避免再生成一层折叠分组。
        if (!groupProcessNodes) return nodes.indices.map { MarkdownGroupedItem.Single(it) }
        val out = ArrayList<MarkdownGroupedItem>(nodes.size)
        val nodeKeys = markdownNodeIdentityKeys(nodes)
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]

            if (node.type != MarkdownProcessorType.XML_BLOCK) {
                out.add(MarkdownGroupedItem.Single(i))
                i++
                continue
            }

            val tag = extractXmlTagName(node.content)

            if (showThinkingProcess && (tag == "think" || tag == "thinking")) {
                var j = i + 1
                var toolCount = 0
                var searchCount = 0
                var xmlToolRelatedCount = 0
                while (j < nodes.size) {
                    val next = nodes[j]
                    // 允许 think 与 tool/tool_result 之间出现纯空白文本（通常是换行）
                    if (next.type == MarkdownProcessorType.PLAIN_TEXT && next.content.isBlank()) {
                        j++
                        continue
                    }
                    if (next.type != MarkdownProcessorType.XML_BLOCK) break

                    val nextTag = extractXmlTagName(next.content)
                    if (isIgnorableXmlTagForToolGrouping(nextTag)) {
                        j++
                        continue
                    }
                    val isThinkAgain = nextTag == "think" || nextTag == "thinking"
                    val isToolRelated = nextTag == "tool" || nextTag == "tool_result"
                    val isSearchRelated = nextTag == "search"
                    if (!isThinkAgain && !isToolRelated && !isSearchRelated) break

                    if (isToolRelated) {
                        val toolName = extractToolNameFromToolOrResult(next.content)
                        if (!shouldGroupToolByName(toolName, toolCollapseMode)) break
                        if (nextTag == "tool") toolCount++
                        xmlToolRelatedCount++
                    }
                    if (isSearchRelated) {
                        searchCount++
                        xmlToolRelatedCount++
                    }

                    j++
                }

                if (shouldCollapseThinkSequence(toolCollapseMode, toolCount, searchCount, xmlToolRelatedCount)) {
                    out.add(
                        MarkdownGroupedItem.Group(
                            startIndex = i,
                            endIndexInclusive = j - 1,
                            stableKey = "think-tools-${nodeKeys[i]}"
                        )
                    )
                    i = j
                    continue
                }

                out.add(MarkdownGroupedItem.Single(i))
                i++
                continue
            }

            if (tag == "tool" || tag == "tool_result") {
                // 配对优先：先把 tool 与它的 tool_result 合成一行（Operit2 merge 层），
                // 剩余节点再按原有折叠逻辑处理。配对索引来自 Rust `NativeXmlGroup`。
                val merged = mergedItemFor(i, nodes, nodeKeys)
                if (merged != null) {
                    out.add(merged)
                    i = consumedEnd(merged, i) + 1
                    continue
                }
                val firstToolName = extractToolNameFromToolOrResult(node.content)
                if (!shouldGroupToolByName(firstToolName, toolCollapseMode)) {
                    out.add(MarkdownGroupedItem.Single(i))
                    i++
                    continue
                }

                var j = i + 1
                var toolCount = if (tag == "tool") 1 else 0
                var xmlToolRelatedCount = 1

                while (j < nodes.size) {
                    val next = nodes[j]
                    if (next.type == MarkdownProcessorType.PLAIN_TEXT && next.content.isBlank()) {
                        j++
                        continue
                    }
                    if (next.type != MarkdownProcessorType.XML_BLOCK) break

                    val nextTag = extractXmlTagName(next.content)
                    if (isIgnorableXmlTagForToolGrouping(nextTag)) {
                        j++
                        continue
                    }
                    val isToolRelated = nextTag == "tool" || nextTag == "tool_result"
                    if (!isToolRelated) break

                    val toolName = extractToolNameFromToolOrResult(next.content)
                    if (!shouldGroupToolByName(toolName, toolCollapseMode)) break

                    xmlToolRelatedCount++
                    if (nextTag == "tool") toolCount++
                    j++
                }

                if (shouldCollapseToolSequence(toolCollapseMode, toolCount, xmlToolRelatedCount)) {
                    out.add(
                        MarkdownGroupedItem.Group(
                            startIndex = i,
                            endIndexInclusive = j - 1,
                            stableKey = "tools-only-${nodeKeys[i]}"
                        )
                    )
                    i = j
                } else {
                    out.add(MarkdownGroupedItem.Single(i))
                    i++
                }
                continue
            }

            if (tag == "search") {
                var j = i + 1

                while (j < nodes.size) {
                    val next = nodes[j]
                    if (next.type == MarkdownProcessorType.PLAIN_TEXT && next.content.isBlank()) {
                        j++
                        continue
                    }
                    if (next.type != MarkdownProcessorType.XML_BLOCK) break

                    val nextTag = extractXmlTagName(next.content)
                    if (isIgnorableXmlTagForToolGrouping(nextTag)) {
                        j++
                        continue
                    }
                    if (nextTag != "search") break

                    j++
                }

                out.add(
                    MarkdownGroupedItem.Group(
                        startIndex = i,
                        endIndexInclusive = j - 1,
                        stableKey = "search-only-${nodeKeys[i]}"
                    )
                )
                i = j
                continue
            }

            out.add(MarkdownGroupedItem.Single(i))
            i++
        }

        return out
    }

    internal fun describeGroup(
        group: MarkdownGroupedItem.Group,
        nodes: List<MarkdownNodeStable>,
        isMessageStreaming: Boolean,
    ): ThinkToolsGroupInfo {
        val end = (group.endIndexInclusive + 1).coerceAtMost(nodes.size)
        val slice = if (group.startIndex in 0 until end) nodes.subList(group.startIndex, end) else emptyList()
        val toolCount = slice.count { it.type == MarkdownProcessorType.XML_BLOCK && extractXmlTagName(it.content) == "tool" }
        val searchCount = slice.count { it.type == MarkdownProcessorType.XML_BLOCK && extractXmlTagName(it.content) == "search" }
        // XML 子流在每个工具块完成后都会移除，批次间隙必须沿用整段回复的生成状态。
        val hasNonConformingTail = isMessageStreaming && nodes.subList(end, nodes.size).any { node ->
            when (node.type) {
                MarkdownProcessorType.PLAIN_TEXT -> node.content.isNotBlank()
                MarkdownProcessorType.XML_BLOCK -> when (extractXmlTagName(node.content)) {
                    "think", "thinking", "search", "meta" -> false
                    "tool", "tool_result" -> {
                        val name = extractToolNameFromToolOrResult(node.content)
                        !(name == null && !isXmlFullyClosed(node.content)) && !shouldGroupToolByName(name, toolCollapseMode)
                    }
                    null -> isXmlFullyClosed(node.content)
                    else -> true
                }
                else -> true
            }
        }
        val thoughtCount = slice.count {
            it.type == MarkdownProcessorType.XML_BLOCK && extractXmlTagName(it.content) in setOf("think", "thinking")
        }
        return ThinkToolsGroupInfo(
            group, toolCount, searchCount, isMessageStreaming && !hasNonConformingTail,
            thoughtCount, timingFor(nodes).group(group.startIndex, group.endIndexInclusive),
        )
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
        val alpha = if (forceExpandGroups) {
            1f
        } else {
            animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 800),
                label = "fadeIn-think-tools-$rendererId"
            ).value
        }
        val sliceEndExclusive = (group.endIndexInclusive + 1).coerceAtMost(nodes.size)
        val slice = if (group.startIndex in 0 until sliceEndExclusive) {
            nodes.subList(group.startIndex, sliceEndExclusive)
        } else {
            emptyList()
        }
        val info = describeGroup(
            group,
            nodes,
            isMessageStreaming = LocalMarkdownRenderMode.current == MarkdownRenderMode.STREAMING,
        )
        val titleText = thinkToolsGroupTitle(info)
        val shouldAutoExpand = info.autoExpand

        var expanded by remember(rendererId, group.stableKey, forceExpandGroups) {
            mutableStateOf(forceExpandGroups || (expansionOverrides[group.stableKey] ?: shouldAutoExpand))
        }
        val userOverride = expansionOverrides[group.stableKey]
        val appearedKeys = remember(rendererId, group.stableKey) { mutableStateMapOf<String, Boolean>() }

        LaunchedEffect(forceExpandGroups, shouldAutoExpand, userOverride) {
            when {
                forceExpandGroups -> expanded = true
                userOverride == null -> expanded = shouldAutoExpand
                else -> expanded = userOverride
            }
        }

        CompositionLocalProvider(LocalInsideToolGroup provides true) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 4.dp)
                    .graphicsLayer { this.alpha = alpha }
        ) {
            val railColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            val rotation = if (forceExpandGroups) {
                if (expanded) 90f else 0f
            } else {
                animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "arrowRotation-think-tools-$rendererId"
                ).value
            }

            CanvasExpandableHeaderRow(
                title = titleText,
                semanticDescription = if (expanded) "Collapse" else "Expand",
                expanded = expanded,
                // Operit2 活跃组用 primary 颜色，非活跃用正文色 alpha 0.7。
                titleColor = if (info.autoExpand) MaterialTheme.colorScheme.primary else textColor.copy(alpha = 0.7f),
                rotationDegrees = rotation,
                onClick = {
                    val newExpanded = !expanded
                    expanded = newExpanded
                    expansionOverrides[group.stableKey] = newExpanded
                },
            )

            AnimatedVisibility(
                visible = expanded,
                enter = if (forceExpandGroups) fadeIn(animationSpec = tween(0)) else fadeIn(animationSpec = tween(200)),
                exit = if (forceExpandGroups) fadeOut(animationSpec = tween(0)) else fadeOut(animationSpec = tween(200))
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            // Operit2 `_ThinkToolsXmlGroup`：内容左侧 1.5dp 竖轨
                            // （outlineVariant α0.55，x=10dp），内容从 24dp 开始。
                            // 旧实现只有缩进、没有轨道线，层级看不出连接关系。
                            .drawBehind {
                                val x = 10.dp.toPx()
                                drawLine(
                                    color = railColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }
                            .padding(top = 2.dp, bottom = 4.dp, start = 24.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        slice.forEachIndexed { idx, node ->
                            val absoluteIndex = group.startIndex + idx
                            val innerKey = "think-tools-$rendererId-${group.stableKey}-$absoluteIndex"
                            androidx.compose.runtime.key(innerKey) {
                                CompositionLocalProvider(
                                    LocalMarkdownCardNodeIndex provides absoluteIndex,
                                    LocalXmlBlockStreaming provides
                                        (isLastNode &&
                                            absoluteIndex == group.endIndexInclusive &&
                                            LocalMarkdownRenderMode.current == MarkdownRenderMode.STREAMING),
                                ) {
                                if (node.type == MarkdownProcessorType.XML_BLOCK) {
                                    if (forceExpandGroups) {
                                        xmlRenderer.RenderXmlContent(
                                            xmlContent = node.content,
                                            modifier = Modifier.fillMaxWidth(),
                                            textColor = textColor,
                                            xmlStream = xmlStreamResolver(absoluteIndex),
                                            renderInstanceKey = innerKey
                                        )
                                    } else {
                                        val alreadyAppeared = appearedKeys[innerKey] == true
                                        var itemVisible by remember(innerKey, alreadyAppeared) {
                                            mutableStateOf(alreadyAppeared)
                                        }

                                        LaunchedEffect(innerKey) {
                                            if (!alreadyAppeared) {
                                                itemVisible = true
                                                appearedKeys[innerKey] = true
                                            }
                                        }

                                        val itemAlpha by animateFloatAsState(
                                            targetValue = if (itemVisible) 1f else 0f,
                                            animationSpec = tween(durationMillis = 800),
                                            label = "fadeIn-$innerKey"
                                        )

                                        xmlRenderer.RenderXmlContent(
                                            xmlContent = node.content,
                                            modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = itemAlpha },
                                            textColor = textColor,
                                            xmlStream = xmlStreamResolver(absoluteIndex),
                                            renderInstanceKey = innerKey
                                        )
                                    }
                                }

                                }
}
                        }
                    }
                }
            }
        }
        }
    }
}


    /** Rust 计算的配对缓存：节点内容快照 -> 每个索引的所属/配对关系。 */
    private var mergeNodesSnapshot: List<String>? = null
    private var mergePlan: NativeXmlGroup.MergePlan? = null

    private fun planFor(nodes: List<MarkdownNodeStable>): NativeXmlGroup.MergePlan? {
        val snapshot = nodes.map { it.content }
        if (mergeNodesSnapshot == snapshot) return mergePlan
        val computed = NativeXmlGroup.plan(snapshot)
        mergeNodesSnapshot = snapshot
        mergePlan = computed
        return computed
    }

    /**
     * 若第 [index] 个节点是一个 `&lt;tool&gt;` 调用，且能确定它的配对结果，
     * 返回合并项；否则返回 null，交回原逻辑处理。
     *
     * 与 Operit2 `ToolCallResultMergeRender.match()` 一致：只有 call 侧发起合并，
     * `&lt;tool_result&gt;` 自身不会单独生成合并项，避免重复渲染。
     */
    private fun mergedItemFor(
        index: Int,
        nodes: List<MarkdownNodeStable>,
        nodeKeys: List<String>,
    ): MarkdownGroupedItem.Merged? {
        if (extractXmlTagName(nodes[index].content) != "tool") return null
        val plan = planFor(nodes) ?: return null
        if (index >= plan.resultOf.size) return null
        val resultIndex = plan.resultOf[index]
        val lastIndex = nodes.lastIndex
        val callOpen = !isXmlFullyClosed(nodes[index].content)
        // 结果已到 -> 合并；否则只在 call 自身仍在流式写入时标记为流式行。
        if (resultIndex in 0..lastIndex) {
            val resultOpen = !isXmlFullyClosed(nodes[resultIndex].content)
            return MarkdownGroupedItem.Merged(
                callIndex = index,
                resultIndex = resultIndex,
                stableKey = "merged-${nodeKeys[index]}",
                isStreaming = callOpen || resultOpen,
            )
        }
        if (callOpen) {
            return MarkdownGroupedItem.Merged(
                callIndex = index,
                resultIndex = -1,
                stableKey = "merged-${nodeKeys[index]}",
                isStreaming = true,
            )
        }
        return null
    }

    /** 合并项吞掉 call 与 result 两个节点。 */
    private fun consumedEnd(item: MarkdownGroupedItem.Merged, fallback: Int): Int =
        if (item.resultIndex >= 0) item.resultIndex else fallback

private fun extractXmlTagName(xml: String): String? {
    return ChatMarkupRegex.normalizeToolLikeTagName(extractRawXmlTagName(xml))
}

private fun extractRawXmlTagName(xml: String): String? {
    // 先走 Rust：流式渲染时这里每帧每节点都会调用，正则每次都要分配 Matcher。
    // Rust 不可用时（库未加载/异常）回退到原正则，行为不变。
    NativeXmlGroup.tagName(xml)?.let { return it }
    return ChatMarkupRegex.extractOpeningTagName(xml)
}

private fun extractToolName(xml: String): String? {
    // 流式渲染每帧对每个 tool 节点调用；Rust 线性扫描替代正则 Matcher。
    NativeXmlGroup.nameAttr(xml)?.let { return it }
    val nameMatch = ChatMarkupRegex.nameAttr.find(xml)
    return nameMatch?.groupValues?.getOrNull(1)
}

private fun isXmlFullyClosed(xml: String): Boolean {
    val tagName = extractRawXmlTagName(xml) ?: return false
    // thinking 标签的闭合规则由 ThinkingMarkup 决定，与通用 XML 不同，
    // 必须先分流，否则 Rust 快路径会给出错误结果。
    if (!ThinkingMarkup.isThinkingTag(tagName)) {
        // 流式渲染时每个节点每帧都会调用，Rust 侧一次扫描完成。
        NativeXmlGroup.isFullyClosed(xml)?.let { return it }
    }
    val trimmed = xml.trim()
    if (trimmed.endsWith("/>")) {
        return true
    }
    if (ThinkingMarkup.isThinkingTag(tagName)) {
        return ThinkingMarkup.isClosed(trimmed)
    }
    return trimmed.contains("</$tagName>")
}

private fun extractToolNameFromToolOrResult(xml: String): String? {
    val tag = extractXmlTagName(xml)
    return when (tag) {
        "tool", "tool_result" -> extractToolName(xml)
        else -> null
    }
}

private fun isIgnorableXmlTagForToolGrouping(tag: String?): Boolean {
    return tag == "meta"
}

private fun shouldGroupToolByName(
    toolName: String?,
    toolCollapseMode: ToolCollapseMode
): Boolean {
    if (toolCollapseMode == ToolCollapseMode.ALL || toolCollapseMode == ToolCollapseMode.FULL) {
        return true
    }

    val n = toolName?.trim()?.lowercase() ?: return false
    if (n.contains("search")) return true
    return n in setOf(
        "list_files",
        "grep_code",
        "grep_context",
        "read_file",
        "read_file_part",
        "read_file_full",
        "read_file_binary",
        "use_package",
        "find_files",
        "visit_web"
    )
}

private fun shouldCollapseToolSequence(
    toolCollapseMode: ToolCollapseMode,
    toolCount: Int,
    xmlToolRelatedCount: Int
): Boolean {
    if (xmlToolRelatedCount <= 0) return false
    return when (toolCollapseMode) {
        ToolCollapseMode.FULL -> true
        ToolCollapseMode.READ_ONLY, ToolCollapseMode.ALL -> toolCount >= 2 && xmlToolRelatedCount >= 2
    }
}

private fun shouldCollapseThinkSequence(
    toolCollapseMode: ToolCollapseMode,
    toolCount: Int,
    searchCount: Int,
    xmlToolRelatedCount: Int
): Boolean {
    if (xmlToolRelatedCount <= 0) return false
    return when (toolCollapseMode) {
        ToolCollapseMode.FULL -> true
        ToolCollapseMode.READ_ONLY, ToolCollapseMode.ALL ->
            searchCount > 0 || (toolCount >= 2 && xmlToolRelatedCount >= 2)
    }
}

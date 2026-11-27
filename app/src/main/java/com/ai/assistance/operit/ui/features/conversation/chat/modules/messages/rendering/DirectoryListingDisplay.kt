package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.theme.script.text.ThemeText as Text

/** 目录列表里的一项。对应 Operit2 `result_data.entries` 的单个元素。 */
internal data class DirectoryEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 字节数；目录项为 null（上游目录不带 size）。 */
    val sizeBytes: Long?,
)

/** 目录列表的完整载荷。对应 Operit2 `result_data` 里 `resultRenderer == directory` 的形态。 */
internal data class DirectoryListing(
    val path: String,
    val entries: List<DirectoryEntry>,
)

/**
 * 校验并解析目录载荷。对应 Dart `isDirectoryListingData`（ToolDisplayComponents.dart:364-375）。
 *
 * 严格校验每个字段的类型：只要有一项不符就整体作废。
 * 理由与上游一致——这个渲染器**只在工具显式声明** `resultRenderer: "directory"` 时才会被选中，
 * 而声明可能来自模型输出，不能假定其形状正确。
 */
internal fun directoryListingFrom(resultData: Map<String, Any?>?): DirectoryListing? {
    val data = resultData ?: return null
    val path = data["path"] as? String ?: return null
    val rawEntries = data["entries"] as? List<*> ?: return null
    val entries = ArrayList<DirectoryEntry>(rawEntries.size)
    for (raw in rawEntries) {
        val entry = raw as? Map<*, *> ?: return null
        val name = entry["name"] as? String ?: return null
        val isDirectory = entry["isDirectory"] as? Boolean ?: return null
        val size = entry["size"] as? Number ?: return null
        entries +=
            DirectoryEntry(
                name = name,
                isDirectory = isDirectory,
                sizeBytes = if (isDirectory) null else size.toLong(),
            )
    }
    return DirectoryListing(path = path, entries = entries)
}

/**
 * 渲染目录列表结果。
 *
 * 移植自 Operit2 `DirectoryListingDisplay`（ToolDisplayComponents.dart:560-635）：
 * - 最大高度 240dp，内部可滚动（长列表不撑爆消息气泡）。
 * - 目录显示文件夹图标、无大小；文件显示文件图标 + 字节数。
 * - 空目录给出明确文案，而不是留白。
 *
 * 与上游的差异：上游用 `ChainedSingleChildScrollView` 接入外层的链式滚动；
 * Kong 的 XML 渲染块不参与那种链式滚动协议，这里直接用 `Column` + `verticalScroll`
 * 加固定高度上限，视觉与行为等价，但不与外层滚动联动。
 */
@Composable
internal fun DirectoryListingDisplay(
    listing: DirectoryListing,
    textColor: Color,
    modifier: Modifier = Modifier,
    showPath: Boolean = true,
) {
    val secondaryColor = textColor.copy(alpha = 0.7f)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showPath) {
                    Text(
                        text = listing.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (listing.entries.isEmpty()) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.R.string.empty_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                    )
                }
                for (entry in listing.entries) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector =
                                if (entry.isDirectory) Icons.Default.Folder
                                else Icons.Default.Description,
                            contentDescription = null,
                            tint = secondaryColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.isDirectory) {
                            entry.sizeBytes?.let { size ->
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$size B",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
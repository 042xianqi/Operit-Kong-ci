package com.ai.assistance.operit.ui.features.conversation.chat.modules.messages.rendering

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONObject
import java.util.Locale

/**
 * 工具渲染元数据：由工具提供方（ToolPkg / 内置工具）在 `<tool ... display="...">` 与
 * `<tool_result ... display="..." result_data="...">` 上声明的**语义**展示信息。
 *
 * 逐条移植自 Operit2 `apps/flutter/app/lib/ui/features/chat/components/part/ToolDisplayComponents.dart`：
 * - [ToolResultRenderer] ← `enum ToolResultRenderer`（:110）
 * - [ToolDisplayDeclaration] ← `class ToolDisplayDeclaration`（:113-160）
 * - [decodeToolMetadata] ← `decodeToolMetadata`（:173-184）
 * - [toolDisplayFromAttributes] ← `toolDisplayFromAttributes`（:186-203）
 * - [toolDisplayIcon] ← `toolDisplayIcon`（:292-314）
 *
 * 为什么值得移植：Kong 原来只按**工具名字面子串**选图标（`getToolIcon`），
 * 于是任何自定义工具（尤其是 ToolPkg 注册的）都落到同一个箭头图标，
 * 标题也只能显示原始工具名。声明式元数据让工具自己决定用什么图标、叫什么名字、
 * 展示哪个参数、结果用哪种渲染器。
 */
enum class ToolResultRenderer {
    TEXT,
    DIRECTORY,
    FILE_DIFF,
}

/** Tool owner 提供的不可变展示元数据。对应 Dart `ToolDisplayDeclaration`。 */
class ToolDisplayDeclaration(
    /** 语言标签 → 标题文案。例：`{"zh": "读取文件", "en": "Read file"}`。 */
    val labels: Map<String, String>,
    /** 语义图标名，见 [toolDisplayIcon]。空串等价于未声明。 */
    val icon: String = "tool",
    /** 从工具参数里挑出来作为行尾目标展示的那个参数名。 */
    val targetParameter: String? = null,
    /** 结果渲染器，见 [ToolResultRenderer]。 */
    val resultRenderer: ToolResultRenderer = ToolResultRenderer.TEXT,
) {
    /**
     * 按 locale 回退顺序取标题。顺序与 Dart 侧一致：
     * `toLanguageTag` → `languageCode` → `default` → `en` → 第一个 → [fallback]。
     */
    fun labelFor(locale: Locale, fallback: String): String =
        labels[locale.toLanguageTag()]
            ?: labels[locale.language]
            ?: labels[DEFAULT_LABEL_KEY]
            ?: labels[ENGLISH_LABEL_KEY]
            ?: labels.values.firstOrNull()
            ?: fallback

    companion object {
        private const val DEFAULT_LABEL_KEY = "default"
        private const val ENGLISH_LABEL_KEY = "en"

        /**
         * 从已解码的 JSON 构造。调用方需先做类型校验
         * （见 [toolDisplayFromAttributes]），这里仍保持宽松以复现 Dart 的强制转换语义。
         */
        internal fun fromJson(json: Map<String, Any?>): ToolDisplayDeclaration {
            val rawLabels = json["label"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            return ToolDisplayDeclaration(
                labels = rawLabels.entries.associate { it.key.toString() to it.value.toString() },
                icon = json["icon"] as? String ?: "tool",
                targetParameter = json["targetParameter"] as? String,
                resultRenderer =
                    when (json["resultRenderer"] as? String) {
                        "directory" -> ToolResultRenderer.DIRECTORY
                        "fileDiff" -> ToolResultRenderer.FILE_DIFF
                        else -> ToolResultRenderer.TEXT
                    },
            )
        }
    }
}

/**
 * 语义图标名 → 图标。对应 Dart `toolDisplayIcon`。
 *
 * 与 Dart 的两处差异都因为 Kong 用 `material-icons-subset`（332 个图标，见
 * `modules/material-icons-subset`）而不是 Flutter 全量 Material 图标集：
 * - `double_tap` / `long_press` 上游用 `AdsClick` / `PanToolOutline`，subset 里没有，
 *   统一回落到 [TouchApp]（同为触摸动作语义）。
 * - `swipe` 上游用 `SwipeOutline`，subset 里没有，回落到 `SwapHoriz`。
 *
 * 若要精确对齐，需要往 subset 里补 `AdsClick` / `PanTool` / `Swipe` 三个图标文件。
 */
internal fun toolDisplayIcon(icon: String?): ImageVector =
    when (icon) {
        "file" -> Icons.Default.Description
        "folder" -> Icons.Default.Folder
        "edit" -> Icons.Default.Edit
        "delete" -> Icons.Default.DeleteOutline
        "search" -> Icons.Default.Search
        "terminal" -> Icons.Default.Terminal
        "web" -> Icons.Default.Language
        "download" -> Icons.Default.Download
        "package" -> Icons.Default.Extension
        "time" -> Icons.Default.Schedule
        "notification" -> Icons.Default.NotificationsNone
        "clipboard" -> Icons.Default.ContentPaste
        "audio" -> Icons.AutoMirrored.Filled.VolumeUp
        "info" -> Icons.Default.Info
        "screen" -> Icons.Default.ScreenshotMonitor
        "tap" -> Icons.Default.TouchApp
        "double_tap" -> Icons.Default.TouchApp
        "long_press" -> Icons.Default.TouchApp
        "swipe" -> Icons.Default.SwapHoriz
        "keyboard" -> Icons.Default.Keyboard
        else -> Icons.Default.Build
    }

/**
 * 只解码**显式**的渲染元数据，接受原文或 XML 转义后的 JSON。对应 Dart `decodeToolMetadata`。
 *
 * 两次尝试是因为属性值可能被写成 `"` 转义形态（模型输出的 XML 里很常见），
 * 也可能已经是裸 JSON。两种情况都要能解。
 */
internal fun decodeToolMetadata(value: String?): Map<String, Any?>? {
    if (value.isNullOrEmpty()) return null
    for (candidate in listOf(value, unescapeXmlForMetadata(value))) {
        val decoded = parseJsonObjectOrNull(candidate) ?: continue
        return decoded
    }
    return null
}

/**
 * 从开标签的属性表解析展示声明。对应 Dart `toolDisplayFromAttributes`。
 *
 * 类型校验必须严格：任何一项类型不对就整条作废（返回 null），
 * 避免模型输出半个畸形 JSON 时把 UI 带进异常状态。
 */
internal fun toolDisplayFromAttributes(attributes: Map<String, String>?): ToolDisplayDeclaration? {
    if (attributes == null) return null
    val json = decodeToolMetadata(attributes["display"]) ?: return null
    val label = json["label"]
    if (label !is Map<*, *>) return null
    val labelsAreWellTyped =
        label.entries.all { entry -> entry.key is String && entry.value is String }
    if (!labelsAreWellTyped) return null
    if (json["icon"] != null && json["icon"] !is String) return null
    if (json["targetParameter"] != null && json["targetParameter"] !is String) return null
    if (json["resultRenderer"] != null && json["resultRenderer"] !is String) return null
    return ToolDisplayDeclaration.fromJson(json)
}

/**
 * 解析一个开标签内的全部属性（`name="value"` / `name='value'`）。
 *
 * 上游用的是 `_ParsedXml.from` 里真正的 XML 解析器；Kong 的 `CustomXmlRenderer`
 * 是流式渲染，标签可能还没闭合，所以这里用轻量扫描而不是 `XmlPullParser`：
 * 流式期间每秒重组十几次，起一个 parser 太重。
 *
 * 属性值里的 `"` 等实体会在 [decodeToolMetadata] 的第二次尝试里被解开，
 * 这里保持原样，不做二次转义，以免破坏 `&amp;quot;` 这种双层转义。
 */
internal fun parseXmlTagAttributes(openingTag: String): Map<String, String> {
    val attributes = linkedMapOf<String, String>()
    for (match in XML_ATTRIBUTE_REGEX.findAll(openingTag)) {
        attributes[match.groupValues[1]] = match.groupValues[3]
    }
    return attributes
}

/**
 * 从工具参数块里提取 `name → value`。对应 Dart `extractToolParamMap`。
 *
 * 与 `CustomXmlRenderer.extractParamsFromTool` 的区别：这里对参数值做了 XML 反转义，
 * 因为 `targetParameter` 的用途是**展示**路径/名称，需要可读值而不是转义序列。
 */
internal fun extractToolParamMapForDisplay(params: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (match in TOOL_PARAM_REGEX.findAll(params)) {
        result[unescapeXmlForMetadata(match.groupValues[1])] =
            unescapeXmlForMetadata(match.groupValues[2])
    }
    return result
}

/** XML 反转义，仅用于展示与元数据解码。对应 Dart `unescapeXmlForDisplay`。 */
internal fun unescapeXmlForMetadata(input: String): String {
    var result = input
    if (result.startsWith("<![CDATA[") && result.endsWith("]]>")) {
        result = result.substring(9, result.length - 3)
    }
    if (result.endsWith("]]>")) {
        result = result.substring(0, result.length - 3)
    }
    if (result.startsWith("<![CDATA[")) {
        result = result.substring(9)
    }
    return result
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        // 用 \u0026 而不是字面 &：某些编辑器/工具链会把 " 这类实体会被误当成
        // XML 实体解码，写进源码后就成了裸引号，直接编译失败。
        .replace("\u0026quot;", "\"")
        .replace("\u0026apos;", "'")
}

private fun parseJsonObjectOrNull(text: String): Map<String, Any?>? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        jsonObjectToMap(JSONObject(trimmed))
    } catch (_: Exception) {
        null
    }
}

private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = json.opt(key)
        map[key] =
            when (value) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                else -> value
            }
    }
    return map
}

/** `name="value"` 或 `name='value'`；值内允许 `"` 这类实体，不允许裸同类引号。 */
private val XML_ATTRIBUTE_REGEX =
    Regex("""([A-Za-z_:][A-Za-z0-9_:.\-]*)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)

private val TOOL_PARAM_REGEX =
    Regex("""<param\s+name="([^"]+)">([\s\S]*?)</param>""")

package com.ai.assistance.operit.ui.common.markdown

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust 加速的 XML 分组/配对层（`tools/native_xml_group`）。
 *
 * 本层只做纯逻辑计算：决定哪个 `&lt;tool_result&gt;` 属于哪个 `&lt;tool&gt;`，以及语义图标到
 * 图标的映射。对应 Operit2 `ToolCallResultMergeRender.dart` 的 `match()`/`renderMerge()`
 * 与 `ToolDisplayComponents.dart:292-314` 的 `toolDisplayIcon`。
 *
 * 失败时返回 null，调用方回退到 Kotlin 实现，保证渲染链路不会因 native 层异常中断。
 */
internal object NativeXmlGroup {

    private val loaded: Boolean = try {
        System.loadLibrary("operit_xml_group")
        android.util.Log.i("NativeXmlGroup", "loaded liboperit_xml_group.so")
        true
    } catch (error: Throwable) {
        // 明确记录，避免 native 层静默失效后只能靠猜。
        android.util.Log.w("NativeXmlGroup", "load failed, falling back to Kotlin", error)
        false
    }

    @JvmStatic
    private external fun planJson(nodes: Array<String>): String

    @JvmStatic
    private external fun iconForJson(semantic: String): String

    /** 配对结果；长度与输入节点数一致，-1 表示无。 */
    internal data class MergePlan(
        val ownerOf: IntArray,
        val resultOf: IntArray,
        val callOf: IntArray,
    )

    internal fun available(): Boolean = loaded

    internal fun plan(nodes: List<String>): MergePlan? {
        if (!loaded) return null
        val raw = try {
            planJson(nodes.toTypedArray())
        } catch (error: Throwable) {
            return null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            if (!obj.optBoolean("ok", false)) return null
            val n = nodes.size
            MergePlan(
                ownerOf = obj.optJSONArray("ownerOf").toIntArray(n),
                resultOf = obj.optJSONArray("resultOf").toIntArray(n),
                callOf = obj.optJSONArray("callOf").toIntArray(n),
            )
        } catch (error: Throwable) {
            null
        }
    }

    internal fun iconFor(semantic: String): String? {
        if (!loaded || semantic.isBlank()) return null
        val raw = try {
            iconForJson(semantic)
        } catch (error: Throwable) {
            return null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            if (!obj.optBoolean("ok", false)) null else obj.optString("icon").takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            null
        }
    }

    private fun JSONArray?.toIntArray(size: Int): IntArray {
        val out = IntArray(size) { -1 }
        if (this == null) return out
        val limit = minOf(size, length())
        for (i in 0 until limit) {
            out[i] = optInt(i, -1)
        }
        return out
    }
}

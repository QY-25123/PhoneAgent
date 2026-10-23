package com.android.phoneagent.perception

import org.json.JSONArray
import org.json.JSONObject

/** 纯 Kotlin 的矩形（不用 android.graphics.Rect，这样单元测试能在 JVM 上直接跑）。 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = maxOf(0, width).toLong() * maxOf(0, height)
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    /** [o] 是否（允许 [tol] 像素误差地）完整落在本矩形内。 */
    fun containsRect(o: Bounds, tol: Int = 2) =
        o.left >= left - tol && o.top >= top - tol && o.right <= right + tol && o.bottom <= bottom + tol

    fun intersect(o: Bounds): Bounds? {
        val l = maxOf(left, o.left); val t = maxOf(top, o.top)
        val r = minOf(right, o.right); val b = minOf(bottom, o.bottom)
        return if (r > l && b > t) Bounds(l, t, r, b) else null
    }
}

/** UserService 抓回的一个节点（字段含义见 UiAutomationHelper.snapshot）。节点按深度优先顺序平铺，[depth] 表示层级。 */
class RawNode(
    val depth: Int,
    val className: String,
    val bounds: Bounds,
    val resourceId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val disabled: Boolean = false,
    val password: Boolean = false,
    /** [text] 其实是占位提示（输入框还是空的） */
    val showingHint: Boolean = false,
    val scrollForward: Boolean = false,
    val scrollBackward: Boolean = false,
)

class RawWindow(
    val id: Int,
    val type: Int,
    val layer: Int,
    val title: String?,
    val focused: Boolean,
    val active: Boolean,
    val bounds: Bounds,
    val packageName: String?,
    val nodes: List<RawNode>,
)

class RawSnapshot(
    val displayId: Int,
    val timeMs: Long,
    /** 节点数超过上限被截断 */
    val truncated: Boolean,
    val windows: List<RawWindow>,
) {
    companion object {
        fun parse(json: String): RawSnapshot {
            val o = JSONObject(json)
            val ws = o.getJSONArray("windows")
            return RawSnapshot(
                displayId = o.optInt("display"),
                timeMs = o.optLong("time"),
                truncated = o.optBoolean("truncated"),
                windows = List(ws.length()) { parseWindow(ws.getJSONObject(it)) },
            )
        }

        private fun parseWindow(w: JSONObject): RawWindow {
            val ns = w.getJSONArray("nodes")
            return RawWindow(
                id = w.optInt("id"), type = w.optInt("type"), layer = w.optInt("layer"),
                title = w.optStringOrNull("title"), focused = w.optBoolean("focused"), active = w.optBoolean("active"),
                bounds = w.getJSONArray("b").toBounds(), packageName = w.optStringOrNull("pkg"),
                nodes = List(ns.length()) { parseNode(ns.getJSONObject(it)) },
            )
        }

        private fun parseNode(n: JSONObject) = RawNode(
            depth = n.optInt("d"), className = n.optString("cls", ""), bounds = n.getJSONArray("b").toBounds(),
            resourceId = n.optStringOrNull("res"), text = n.optStringOrNull("text"), desc = n.optStringOrNull("desc"),
            hint = n.optStringOrNull("hint"), visible = n.optBoolean("vis", true),
            clickable = n.optBoolean("clk"), longClickable = n.optBoolean("lclk"), editable = n.optBoolean("edit"),
            scrollable = n.optBoolean("scr"), focused = n.optBoolean("foc"), checkable = n.optBoolean("chkable"),
            checked = n.optBoolean("chk"), selected = n.optBoolean("sel"), disabled = n.optBoolean("dis"),
            password = n.optBoolean("pw"), showingHint = n.optBoolean("sh"), scrollForward = n.optBoolean("sf"), scrollBackward = n.optBoolean("sb"),
        )

        private fun JSONArray.toBounds() = Bounds(getInt(0), getInt(1), getInt(2), getInt(3))

        // org.json 对缺失字段的 optString 返回 ""，对 JSON null 返回 "null"，这里统一成 null
        private fun JSONObject.optStringOrNull(k: String): String? =
            if (!has(k) || isNull(k)) null else getString(k)
    }
}

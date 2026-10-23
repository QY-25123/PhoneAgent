package com.android.phoneagent.perception

/** 元素上可以执行的操作。 */
enum class ElementAction(val label: String) {
    CLICK("click"), LONG_CLICK("long-click"), EDIT("edit"),
    SCROLL_FORWARD("scroll-down"), SCROLL_BACKWARD("scroll-up"), TOGGLE("toggle"),
}

/**
 * 虚拟屏某一时刻的观察结果。agent 只通过元素编号 [UiElement.index] 引用控件；
 * [UiElement.stableId] 在相邻步骤间尽量不变，用于 M4 验证和日志对照。
 */
class ScreenState(
    val displayId: Int,
    val packageName: String?,
    val pageTitle: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestampMs: Long,
    val elements: List<UiElement>,
    /** 截图全黑（多半是安全窗口 FLAG_SECURE）；界面树照常输出 */
    val secureWindow: Boolean = false,
    /** 没有任何窗口，或筛选后没有元素 */
    val treeEmpty: Boolean = false,
    /** 给模型和日志的提示（未稳定、WebView 空壳、截断等） */
    val notes: List<String> = emptyList(),
    /** 标注了编号和边框的截图（缩放后的 JPEG）；没有截图时为 null */
    val screenshotJpeg: ByteArray? = null,
)

data class UiElement(
    val index: Int,
    val stableId: String,
    /** 类型简称：EditText / Button / Text / Image / Checkbox / Scrollable / WebView ... */
    val type: String,
    val text: String?,
    val desc: String?,
    val hint: String?,
    /** 可点击父节点没有自己的文字时，取其内部文字拼出的标签（已按长度截断） */
    val mergedText: String?,
    val resourceId: String?,
    val className: String,
    val bounds: Bounds,
    val actions: Set<ElementAction>,
    val focused: Boolean,
    /** 可勾选时的当前状态，否则 null */
    val checked: Boolean?,
    val enabled: Boolean,
    val password: Boolean = false,
)

package com.android.phoneagent.perception

import android.graphics.Rect

/** 虚拟屏某一时刻的观察结果（M1 实现生成逻辑）。agent 只通过元素编号引用屏幕上的控件。 */
class ScreenState(
    val displayId: Int,
    val packageName: String?,
    val timestampMs: Long,
    val elements: List<UiElement>,
    val screenshot: ByteArray?,
)

class UiElement(
    val index: Int,
    val text: String?,
    val desc: String?,
    val resourceId: String?,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
)

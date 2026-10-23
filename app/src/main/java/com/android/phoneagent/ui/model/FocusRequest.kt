package com.android.phoneagent.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 从悬浮小窗打开 App 时要定位到的卡片：confirm / question / handoff。对话页滚动到对应卡片后清空。 */
object FocusRequest {
    var target by mutableStateOf<String?>(null)
}

package com.android.phoneagent.agent

enum class Direction { UP, DOWN, LEFT, RIGHT }

/**
 * agent 能下达的动作（PRD 6.2）。目标用元素编号，不用坐标；坐标由 perception 的当前 ScreenState 换算，
 * 换算后的动作必须先经 safety 检查才能执行。
 */
sealed interface Action {
    /** 只能打开允许的 App（日历 / Gmail / Amazon），名字或包名都行 */
    data class OpenApp(val app: String) : Action
    data class Tap(val index: Int) : Action

    /** 兜底：界面树读不到要点的东西（例如微信的弹窗）时按截图上的位置点击。[x] [y] 是在截图上的比例，0–1000（左上角 0,0，右下角 1000,1000）。 */
    data class TapXY(val x: Int, val y: Int) : Action
    data class Type(val index: Int, val text: String) : Action

    /** 兜底：往「当前聚焦的输入框」粘贴文字（经剪贴板），用于界面树读不到输入框时（先用 tap_xy 点中输入框）。 */
    data class Paste(val text: String, val x: Int? = null, val y: Int? = null) : Action

    /** [dragSheet]：从 [index] 元素所在的那一行（取屏幕左侧的空白处）长距离拖动，用来拉开底部面板（地图路线面板）。
     *  [direction] 是手指滑动的方向的反面：DOWN ＝ 看到更下面的内容（手指向上滑） */
    data class Scroll(val direction: Direction, val index: Int? = null, val dragSheet: Boolean = false) : Action
    data object Back : Action
    data object PressEnter : Action
    data class Wait(val seconds: Int) : Action
    data class AskUser(val question: String, val options: List<String> = emptyList()) : Action

    /** 模型认为任务结束：[success] 为 false 表示放弃（[result] 写原因）。是否真的完成还要自查。 */
    data class Done(val success: Boolean, val result: String) : Action

    /** 是否算一步（PRD 6.1：只有实际执行的动作计入步数；等待、询问、结束不计）。 */
    val countsAsStep: Boolean get() = this !is Wait && this !is AskUser && this !is Done

    /** 给人看的一句话（步骤列表、日志）。 */
    fun describe(): String = when (this) {
        is OpenApp -> "打开 $app"
        is Tap -> "点击 #$index"
        is TapXY -> "点击截图位置 ($x,$y)"
        is Paste -> if (x != null && y != null) "点击 ($x,$y) 并粘贴「$text」" else "粘贴「$text」"
        is Type -> "在 #$index 输入「$text」"
        is Scroll -> "向${when (direction) { Direction.UP -> "上"; Direction.DOWN -> "下"; Direction.LEFT -> "左"; Direction.RIGHT -> "右" }}滚动"
        Back -> "返回"
        PressEnter -> "按回车"
        is Wait -> "等待 ${seconds} 秒"
        is AskUser -> "询问：$question"
        is Done -> if (success) "完成：$result" else "放弃：$result"
    }
}

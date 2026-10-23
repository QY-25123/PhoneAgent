package com.android.phoneagent.agent

enum class Direction { UP, DOWN, LEFT, RIGHT }

/**
 * agent 能下达的动作（PRD 6.2）。目标用元素编号，不用坐标；坐标由 perception 按最新 ScreenState 换算，
 * 换算后的动作必须先经 safety 检查才能执行。
 */
sealed interface Action {
    data class Launch(val packageOrComponent: String) : Action
    data class Tap(val index: Int) : Action
    data class Type(val index: Int, val text: String) : Action
    data class Scroll(val index: Int?, val direction: Direction) : Action
    data object Back : Action
    data class Wait(val ms: Long) : Action
    data class Ask(val question: String, val options: List<String> = emptyList()) : Action

    /** 只是模型「自称完成」；是否真的完成由 verification 判断。 */
    data class Finish(val claim: String) : Action
}

package com.android.phoneagent.task

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 任务进行中发给界面的事件（界面只依赖这里，不直接碰 agent / privileged）。 */
sealed interface TaskEvent {
    /** agent 在对话里说一句话（复述、说明、出错原因……） */
    data class AgentSaid(val text: String) : TaskEvent

    /** 追问或执行中询问：有 [options] 时显示成选项按钮，否则就是一条普通提问（用户在输入栏回答） */
    data class Ask(val text: String, val options: List<String>) : TaskEvent

    /** 开始执行：显示实时画面卡片 */
    data class Started(val title: String, val maxSteps: Int) : TaskEvent

    /** 一步进展。[step] 只给计入步数的动作；[thumb] 是虚拟屏当前画面的小图 */
    data class Progress(val step: Int?, val maxSteps: Int, val saw: String, val did: String, val thumb: Bitmap?) : TaskEvent

    data class Succeeded(val title: String, val detail: String, val evidence: List<String>, val image: Bitmap?) : TaskEvent
    data class Failed(val reason: String, val leftovers: List<String>) : TaskEvent
    data class Stopped(val reason: String) : TaskEvent
}

/** 界面接入真实流程的入口（M2：[TaskCoordinator] 实现）。 */
interface TaskBackend {
    val events: SharedFlow<TaskEvent>

    /** 有任务在澄清或执行 */
    val busy: StateFlow<Boolean>

    /** 用户发来一句话：可能是新任务，也可能是对追问的回答。返回 true 表示开始了一个新任务。 */
    fun submit(text: String): Boolean

    /** 停止当前任务并清理虚拟屏 */
    fun stop()
}

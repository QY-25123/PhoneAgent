package com.android.phoneagent.task

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 任务进行中发给界面的事件（界面只依赖这里，不直接碰 agent / privileged）。 */
sealed interface TaskEvent {
    /** 虚拟屏当前画面的小图（执行期间约每秒一张，不进历史）：实时画面卡片和全屏观看用它保持流畅 */
    data class Frame(val thumb: Bitmap) : TaskEvent

    /** 用户说的话（不管是对话页输入的，还是别的入口提交的，界面都靠这个事件显示用户气泡） */
    data class UserSaid(val text: String) : TaskEvent

    /** agent 在对话里说一句话（复述、说明、出错原因……） */
    data class AgentSaid(val text: String) : TaskEvent

    /** 追问或执行中询问：有 [options] 时显示成选项按钮，否则就是一条普通提问（用户在输入栏回答） */
    data class Ask(val text: String, val options: List<String>) : TaskEvent

    /** 开始执行：显示实时画面卡片 */
    data class Started(val title: String, val maxSteps: Int) : TaskEvent

    /** 一步进展。[step] 只给计入步数的动作；[thumb] 是虚拟屏当前画面的小图 */
    data class Progress(val step: Int?, val maxSteps: Int, val saw: String, val did: String, val thumb: Bitmap?) : TaskEvent

    /** 邮件确认卡片：收件人、主题、正文**都是从 Gmail 写邮件页实际读到的内容**，用户确认的就是将要发送的内容 */
    data class Confirm(val to: String, val subject: String, val body: String, val kind: String = "email") : TaskEvent

    /** 用户对确认卡片做了选择（不管是点界面上的按钮，还是别的入口）：界面据此更新卡片状态、去掉按钮 */
    data class ConfirmResolved(val choice: ConfirmChoice) : TaskEvent

    /** 用户点了「去完成」，Amazon 已移到主屏：界面把交接卡片置为已完成、小窗收起 */
    data object HandoffDone : TaskEvent

    /** 已停在 Amazon 提交订单页：交给用户付款。用户点「去完成」后 [TaskBackend.handoff] 把页面移到主屏 */
    data class Handoff(val where: String, val summary: String, val action: String = "去完成", val heading: String = "需要你来完成") : TaskEvent

    data class Succeeded(val title: String, val detail: String, val evidence: List<String>, val image: Bitmap?) : TaskEvent
    data class Failed(val reason: String, val leftovers: List<String>) : TaskEvent
    data class Stopped(val reason: String) : TaskEvent
}

enum class ConfirmChoice { SEND, MODIFY, CANCEL }

/** 界面接入真实流程的入口（M2：[TaskCoordinator] 实现）。 */
interface TaskBackend {
    val events: SharedFlow<TaskEvent>

    /** 到现在为止发生过的事件（进展事件不带缩略图）。界面（重新）创建时先回放它，再订阅 [events]，这样关掉 App 再打开、或别的入口提交的任务，对话里都看得到。 */
    fun history(): List<TaskEvent>

    /** A fresh launch of the app starts with an empty conversation. Does nothing while a task is running (the floating window can reopen the app mid-task). */
    fun clearHistory() {}

    /** 有任务在澄清或执行 */
    val busy: StateFlow<Boolean>

    /** 用户发来一句话：可能是新任务，也可能是对追问的回答。返回 true 表示开始了一个新任务。 */
    fun submit(text: String): Boolean

    /** 用户在确认卡片上的选择（发送 / 修改 / 取消）。 */
    fun confirm(choice: ConfirmChoice)

    /** 用户点了交接卡片上的「去完成」：把 Amazon 从虚拟屏移到主屏 */
    fun handoff()

    /** 停止当前任务并清理虚拟屏 */
    fun stop()
}

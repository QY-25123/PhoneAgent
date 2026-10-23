package com.android.phoneagent.agent

import com.android.phoneagent.perception.ScreenState

/**
 * 执行循环要完成的一件事：任务描述、系统提示词、怎么打开起始页、能不能走快速通道、怎么本地核对结果。
 * 日历创建、写邮件草稿、改草稿、查已发送、丢弃草稿都是它的实现，循环本身（感知→决策→执行→停止条件）共用。
 */
interface LoopTask {
    val taskText: String
    /** 已确认的信息（发给模型） */
    val confirmed: String
    val leftovers: List<String>
    val stepSystem: String
    val verifySystem: String

    /** 起始页：能用意图直接打开就打开；返回的 [ActionOutcome.ok] 为 false 时循环退回界面操作 */
    suspend fun prelaunch(phone: PhoneController): ActionOutcome
    val prelaunchOkNote: String
    val prelaunchFailNote: String

    /** 快速通道：页面已经满足要求时，要点的按钮编号；不满足返回 null（交给模型） */
    fun fastTap(screen: ScreenState): Int? = null
    val fastTapNote: String get() = "点击"
    val fastClaim: String get() = "已完成"

    /** 页面已经满足任务目标、不需要任何动作（例如写邮件草稿：预填后内容对得上就算完成） */
    fun alreadyDone(screen: ScreenState): Boolean = false

    /** 点了快速按钮之后，页面是否还停在编辑页（保存动画/转场还没结束） */
    fun stillEditing(screen: ScreenState): Boolean = false

    /** 本地核对最终界面，不调用模型：返回 (是否通过, 说明)；null 表示用模型自查 */
    fun localVerify(screen: ScreenState): Pair<Boolean, String>? = null
}

/** 日历创建事项。 */
class CalendarLoopTask(private val task: CalendarTask) : LoopTask {
    override val taskText = "在 Google 日历里创建事项「${task.title}」"
    override val confirmed = task.confirmedFields()
    override val leftovers = listOf("日历里可能留有标题为「${task.title}」的事项或没保存的新建页，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.CALENDAR_SECTION)
    override val verifySystem = Prompts.VERIFY_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.launchCalendarInsert(task)
    override val prelaunchOkNote = "用「插入事项」意图在虚拟屏打开了日历新建页并预填标题和起止时间"
    override val prelaunchFailNote = "尝试用「插入事项」意图打开新建页"
    override fun fastTap(screen: ScreenState) = CalendarFastPath.saveIndex(task, screen)
    override val fastTapNote = "点击「保存」"
    override val fastClaim = "已点击保存"
    override fun stillEditing(screen: ScreenState) = CalendarFastPath.saveIndex(task, screen) != null || CalendarFastPath.isEditor(screen)
}

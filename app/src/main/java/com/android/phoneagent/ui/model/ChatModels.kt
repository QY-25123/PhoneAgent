package com.android.phoneagent.ui.model

import androidx.compose.runtime.Immutable
import androidx.annotation.DrawableRes
import com.android.phoneagent.R

/** 对话里的一条消息（界面层的假数据模型，和 agent 逻辑无关）。 */
sealed interface ChatItem {
    val id: Long

    @Immutable data class User(override val id: Long, val text: String) : ChatItem
    @Immutable data class Agent(override val id: Long, val text: String) : ChatItem

    /** 实时画面卡片。 */
    @Immutable data class Live(
        override val id: Long,
        val title: String,
        val step: Int,
        val total: Int,
        val stepText: String,
        val status: LiveStatus,
        @DrawableRes val thumb: Int,
    ) : ChatItem

    /** T2 确认卡片。 */
    @Immutable data class Confirm(
        override val id: Long,
        val to: String,
        val subject: String,
        val body: String,
        val state: ConfirmState,
    ) : ChatItem

    /** 选项类提问。 */
    @Immutable data class Question(override val id: Long, val text: String, val options: List<String>, val chosen: Int?) : ChatItem

    @Immutable data class Success(
        override val id: Long,
        val title: String,
        val detail: String,
        val evidenceLines: List<String>,
        @DrawableRes val evidenceImage: Int?,
    ) : ChatItem

    @Immutable data class Failure(override val id: Long, val reason: String, val residue: List<String>) : ChatItem

    @Immutable data class Handoff(override val id: Long, val where: String, val summary: String, val done: Boolean) : ChatItem
}

enum class LiveStatus(val label: String) {
    RUNNING("执行中"), WAITING("等待你处理"), DONE("已完成"), STOPPED("已停止"), PAUSED("已暂停"),
}

enum class ConfirmState { PENDING, SENDING, SENT, CANCELLED, SUPERSEDED }

/** 步骤列表里的一条记录（FR-FS-7）。 */
@Immutable data class StepRecord(val index: Int?, val saw: String, val did: String)

/** 悬浮小窗的 10 个状态（PRD 7.4.2）。 */
enum class FloatState(val title: String, val text: String, val actions: List<String>) {
    RUNNING("执行中", "第 5 步 · 在日历里填写标题", listOf("停止")),
    WAIT_TYPING("等待用户输入完成", "等你输入完成后继续", listOf("停止")),
    NEED_ANSWER("需要用户回答", "几点开始？", listOf("下午 3 点", "下午 4 点", "去回答")),
    WAIT_CONFIRM("等待确认", "待发送：Alice · 周五评审改期", listOf("去确认", "停止")),
    HANDOFF("需要交接", "已到提交订单页，请你下单", listOf("去处理", "停止")),
    DONE("完成", "已完成：邮件已发送", listOf("关闭")),
    FAILED("失败", "失败：日历没有写入权限", listOf("关闭")),
    PAUSED("已暂停", "已暂停", listOf("继续", "停止")),
    CONFLICT("应用冲突", "你正在使用 Gmail，请离开后我再开始", listOf("停止")),
    STOPPED("已停止", "已停止", listOf("关闭")),
}

/** 演示用的静态画面（代替虚拟屏截图）。 */
object Mocks {
    val inbox = R.drawable.mock_inbox
    val composeEmpty = R.drawable.mock_compose_empty
    val composeFilled = R.drawable.mock_compose_filled
    val sent = R.drawable.mock_sent
    val calendar = R.drawable.mock_calendar
    val calendarEvent = R.drawable.mock_calendar_event
    val cart = R.drawable.mock_cart
    val checkout = R.drawable.mock_checkout
}

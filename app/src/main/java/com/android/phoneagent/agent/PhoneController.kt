package com.android.phoneagent.agent

import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.perception.ScreenState

/** 一次动作的执行结果。[blocked] 表示被硬拦截（不执行，不要换个办法绕过）。 */
class ActionOutcome(val ok: Boolean, val message: String, val blocked: Boolean = false)

/**
 * 执行循环操作手机的接口：感知 + 执行动作。真实实现在 task 包（走 safety 检查和 UserActivityGuard），测试里用假的。
 * agent 不直接碰 privileged。
 */
interface PhoneController {
    /** 用日历「插入事项」意图在虚拟屏打开新建页并预填标题和起止时间；失败时循环会退回界面操作。 */
    suspend fun launchCalendarInsert(task: CalendarTask): ActionOutcome

    /** 用 mailto 意图在虚拟屏打开 Gmail 写邮件页，预填收件人、主题、正文；失败时循环退回界面操作。 */
    suspend fun launchEmailCompose(task: EmailTask): ActionOutcome

    /** 用链接在虚拟屏打开 Amazon（搜索结果页或购物车页），只允许 https://www.amazon.com/ 开头的链接。 */
    suspend fun launchAmazon(url: String): ActionOutcome

    /** 打开允许的 App（日历 / Gmail / Amazon）。 */
    suspend fun openApp(app: String): ActionOutcome

    /**
     * 内容绑定的发送放行：特权侧自己读草稿算哈希，与 [hash] 一致才布防（一次性），点击发送时还会再校验一次。
     * 返回 false 表示此刻草稿内容与 [hash] 不一致（或读不到）。
     */
    suspend fun armGmailSend(hash: String): Boolean

    suspend fun disarmGmailSend()

    /** 购物车改动的一次性放行：只有清单里的改动能执行（特权侧硬拦截），其他一律拦截。 */
    suspend fun armCartChanges(changes: List<CartChange>)

    suspend fun disarmCartChanges()

    suspend fun perceive(): PerceptionResult

    /** 执行一个动作（open_app / tap / type / scroll / back / press_enter）。元素编号以 [screen] 为准。 */
    suspend fun execute(action: Action, screen: ScreenState): ActionOutcome
}

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

    suspend fun perceive(): PerceptionResult

    /** 执行一个动作（open_app / tap / type / scroll / back / press_enter）。元素编号以 [screen] 为准。 */
    suspend fun execute(action: Action, screen: ScreenState): ActionOutcome
}

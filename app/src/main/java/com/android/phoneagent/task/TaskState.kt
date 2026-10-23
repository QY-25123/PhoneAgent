package com.android.phoneagent.task

import com.android.phoneagent.agent.Action
import com.android.phoneagent.verification.Evidence

/** 一步的记录：看到了什么、做了什么、效果（PRD FR-LOOP-2）。序号只分配给计入步数的动作。 */
class StepRecord(val number: Int?, val saw: String, val did: String, val effect: String)

/** UI 只订阅这个状态（M2 起由 task 层产出；M5 替换界面里的假数据）。 */
sealed interface TaskState {
    data object Idle : TaskState
    data class Clarifying(val question: String, val options: List<String>) : TaskState
    data class Running(val step: Int, val maxSteps: Int, val log: List<StepRecord>) : TaskState
    data class WaitingUser(val ask: Action.AskUser) : TaskState
    data class Conflict(val app: String, val interrupted: Boolean) : TaskState
    data class Done(val evidence: Evidence) : TaskState
    data class Failed(val reason: String, val leftovers: List<String>) : TaskState
    data class Stopped(val reason: String) : TaskState
    data class HandedOff(val note: String) : TaskState
}

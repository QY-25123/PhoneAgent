package com.android.phoneagent.agent

import com.android.phoneagent.perception.PerceptionResult

/** 一次任务的运行记录：每步的标注截图、模型输入输出、耗时、token。测试里用 [NoopRunLog]。 */
interface RunLog {
    /** 一次模型调用（成功 [resp] 非空，失败 [error] 非空）。 */
    fun modelCall(req: ModelRequest, resp: ModelResponse?, error: String?)

    /** 执行循环的一步：看到的界面、模型的决定、执行结果。 */
    fun step(n: Int, screen: PerceptionResult, decision: StepDecision?, outcome: ActionOutcome?, note: String? = null)

    /** 任务结束：[result] 是结果摘要。 */
    fun finish(result: String, totalSteps: Int, totalModelCalls: Int, totalMs: Long)
}

object NoopRunLog : RunLog {
    override fun modelCall(req: ModelRequest, resp: ModelResponse?, error: String?) = Unit
    override fun step(n: Int, screen: PerceptionResult, decision: StepDecision?, outcome: ActionOutcome?, note: String?) = Unit
    override fun finish(result: String, totalSteps: Int, totalModelCalls: Int, totalMs: Long) = Unit
}

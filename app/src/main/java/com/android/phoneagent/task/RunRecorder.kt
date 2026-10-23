package com.android.phoneagent.task

import android.util.Log
import com.android.phoneagent.agent.ActionOutcome
import com.android.phoneagent.agent.ModelRequest
import com.android.phoneagent.agent.ModelResponse
import com.android.phoneagent.agent.ModelUsage
import com.android.phoneagent.agent.RunLog
import com.android.phoneagent.agent.StepDecision
import com.android.phoneagent.perception.PerceptionResult
import org.json.JSONObject
import java.io.File

/**
 * 把一次任务写到 [dir]（runs/<时间>/）：每次模型调用一个 call_NN_<label>.json（输入文字、输出、token、耗时），
 * 每个循环迭代一个 step_NN.jpg（标注截图）+ step_NN.json（界面文本、决定、结果），最后 summary.json（总耗时、token、大致费用）。
 */
class RunRecorder(private val dir: File) : RunLog {
    private var calls = 0
    private var iterations = 0
    private var usage = ModelUsage()
    private var modelMs = 0L

    init { dir.mkdirs() }

    override fun modelCall(req: ModelRequest, resp: ModelResponse?, error: String?) {
        calls++
        if (resp != null) { usage += resp.usage; modelMs += resp.latencyMs }
        write("call_%02d_%s.json".format(calls, req.label), JSONObject().apply {
            put("label", req.label); put("effort", req.effort)
            put("system", req.system)
            put("user", req.messages.joinToString("\n---\n") { it.text })
            put("images", req.messages.sumOf { it.jpegImages.size })
            if (resp != null) {
                put("response", resp.json); put("latencyMs", resp.latencyMs); put("stop", resp.stopReason)
                put("tokens", JSONObject().put("input", resp.usage.inputTokens).put("output", resp.usage.outputTokens)
                    .put("cacheRead", resp.usage.cacheReadTokens).put("cacheWrite", resp.usage.cacheWriteTokens))
            }
            if (error != null) put("error", error)
        })
    }

    override fun step(n: Int, screen: PerceptionResult, decision: StepDecision?, outcome: ActionOutcome?, note: String?) {
        iterations++
        val name = "step_%02d".format(iterations)
        screen.state.screenshotJpeg?.let { File(dir, "$name.jpg").writeBytes(it) }
        write("$name.json", JSONObject().apply {
            put("stepNumber", n); put("screen", screen.text); put("perception", screen.timings.toString())
            decision?.let { put("understanding", it.understanding); put("reason", it.reason); put("action", it.action.describe()) }
            outcome?.let { put("ok", it.ok); put("outcome", it.message); put("blocked", it.blocked) }
            note?.let { put("note", it) }
        })
    }

    override fun finish(result: String, totalSteps: Int, totalModelCalls: Int, totalMs: Long) {
        val summary = JSONObject().apply {
            put("result", result); put("steps", totalSteps); put("modelCalls", totalModelCalls)
            put("totalMs", totalMs); put("modelMs", modelMs)
            put("tokens", JSONObject().put("input", usage.inputTokens).put("output", usage.outputTokens)
                .put("cacheRead", usage.cacheReadTokens).put("cacheWrite", usage.cacheWriteTokens))
            put("approxCostUsd", "%.4f".format(usage.approxCostUsd).toDouble())
        }
        write("summary.json", summary)
        Log.i("PA", "TASKRUN ${dir.name} $result steps=$totalSteps calls=$totalModelCalls total=${totalMs / 1000}s cost≈\$${"%.3f".format(usage.approxCostUsd)} dir=${dir.absolutePath}")
    }

    private fun write(name: String, o: JSONObject) = File(dir, name).writeText(o.toString(2))
}

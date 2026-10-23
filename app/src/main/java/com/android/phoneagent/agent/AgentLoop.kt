package com.android.phoneagent.agent

import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.perception.StabilityWaiter
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

class LoopConfig(
    val maxSteps: Int = 30,
    val maxModelCalls: Int = 60,
    /** 连续失败（动作执行失败或模型输出不合法）这么多次就停 */
    val maxConsecutiveFailures: Int = 3,
    /** 连续这么多步动作之后界面没有变化就停 */
    val maxUnchanged: Int = 3,
    val historyWindow: Int = 6,
    val maxSelfCheckFailures: Int = 2,
    /** 同一个动作在同一个界面上出现这么多次（不要求连续）就判定为在打转 */
    val maxRepeats: Int = 3,
    /** 日历快速通道（见 [CalendarFastPath]） */
    val calendarFastPath: Boolean = true,
)

enum class StopKind { REPEATING, MODEL_GAVE_UP, STEP_LIMIT, CALL_LIMIT, REPEATED_FAILURE, NO_CHANGE, MODEL_ERROR, BLOCKED, SELF_CHECK_FAILED }

sealed interface LoopResult {
    val steps: Int
    val modelCalls: Int

    data class Success(val summary: String, val evidence: String, override val steps: Int, override val modelCalls: Int) : LoopResult
    data class Failure(
        val kind: StopKind, val reason: String, val leftovers: List<String>,
        override val steps: Int, override val modelCalls: Int,
    ) : LoopResult
}

/** 给界面的一步进展。[number] 只给计入步数的动作（PRD FR-LOOP-2）。 */
class StepInfo(val number: Int?, val saw: String, val did: String, val screen: PerceptionResult)

interface LoopHost {
    fun onStep(info: StepInfo)

    /** ask_user：在对话里问用户，等回答。不计步数。 */
    suspend fun askUser(question: String, options: List<String>): String
}

/**
 * 执行循环（PRD 6.1）：感知 → 调用 Claude → 一个动作 → 执行（安全检查在 [PhoneController] 里）→ 重新感知。
 * 停止条件：done（并通过自查）、30 步、60 次模型调用、连续 3 次失败、连续 3 步界面无变化。
 * 用户停止靠协程取消。
 */
class AgentLoop(
    private val model: ModelClient,
    private val phone: PhoneController,
    private val host: LoopHost,
    private val cfg: LoopConfig = LoopConfig(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val log: RunLog = NoopRunLog,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(task: CalendarTask): LoopResult = run(CalendarLoopTask(task))

    suspend fun run(task: LoopTask): LoopResult {
        val taskText = task.taskText
        val confirmed = task.confirmed
        val leftovers = task.leftovers
        val history = ArrayList<Prompts.HistoryItem>()
        var steps = 0
        var calls = 0
        var failures = 0
        var unchanged = 0
        var selfCheckFailures = 0
        var sigBeforeAction: String? = null
        val seen = HashMap<String, Int>()   // 「界面签名 + 动作」出现次数
        val t0 = System.nanoTime()

        fun fail(kind: StopKind, reason: String): LoopResult {
            log.finish("失败（$kind）：$reason", steps, calls, (System.nanoTime() - t0) / 1_000_000)
            return LoopResult.Failure(kind, reason, leftovers, steps, calls)
        }

        val pre = task.prelaunch(phone)
        history += if (pre.ok) Prompts.HistoryItem(null, "", task.prelaunchOkNote, "成功")
        else Prompts.HistoryItem(null, "", task.prelaunchFailNote, "失败（${pre.message}）：改用界面操作，从 open_app 开始")

        var pending: PerceptionResult? = null   // 已经感知过、下一轮直接用的界面

        // 自查：只看界面上实际看到的内容。返回 null 表示通过，否则是问题描述；模型失败抛出 [ModelException]
        suspend fun selfCheck(screen: PerceptionResult, claim: String): Pair<Boolean, String?> {
            task.localVerify(screen.state)?.let { return it }   // 能本地核对的不调用模型
            calls++
            val vreq = ModelRequest(
                "verify", task.verifySystem,
                listOf(ModelMessage("user", Prompts.verifyUser(taskText, confirmed, claim, screen.text), listOfNotNull(screen.state.screenshotJpeg))),
                Schemas.VERIFY, 600, "low", Models.verify,
            )
            val vresp = try { model.complete(vreq).also { log.modelCall(vreq, it, null) } } catch (e: ModelException) {
                log.modelCall(vreq, null, e.message); throw e
            }
            val v = try { org.json.JSONObject(vresp.json) } catch (e: Exception) { null }
            if (v != null && v.optBoolean("verified")) return true to v.optString("evidence")
            return false to (v?.takeIf { !it.isNull("problem") }?.optString("problem") ?: "自查没有通过")
        }

        // 快速通道：页面已经满足要求（本地核对一致）就直接完成/点按钮，省掉模型调用
        if (cfg.calendarFastPath && pre.ok) {
            val first = phone.perceive()
            val idx = task.fastTap(first.state)
            if (task.alreadyDone(first.state)) {
                val (ok, info) = try { selfCheck(first, task.fastClaim) } catch (e: ModelException) { return fail(StopKind.MODEL_ERROR, e.message ?: "调用模型失败") }
                if (ok) {
                    log.step(steps, first, null, null, "快速通道：页面已满足要求，不需要操作")
                    log.finish("成功：${task.fastClaim}", steps, calls, (System.nanoTime() - t0) / 1_000_000)
                    return LoopResult.Success(task.fastClaim, info ?: "", steps, calls)
                }
                history += Prompts.HistoryItem(null, "", "本地核对页面", "没有通过：$info")
                pending = first
            } else if (idx == null) {
                pending = first
                history += Prompts.HistoryItem(null, "", "本地核对：页面上的内容没有全部对上", "交给你检查和处理")
            } else {
                steps++
                val tapAction = Action.Tap(idx)
                host.onStep(StepInfo(steps, "页面已按要求预填好，各项都对得上（本地核对）", task.fastTapNote, first))
                val out = phone.execute(tapAction, first.state)
                log.step(steps, first, null, out, "快速通道：本地核对通过，直接执行")
                history += Prompts.HistoryItem(steps, "页面各项与已确认信息一致（本地核对）", tapAction.describe(), if (out.ok) "成功" else "失败：${out.message}")
                if (out.blocked) return fail(StopKind.BLOCKED, "操作被安全规则拦截（${out.message}），不会绕过")
                if (out.ok) {
                    // 等保存/转场结束（编辑页消失）再自查，最多等约 3 秒，避免看到还没关掉的编辑页
                    var after = phone.perceive()
                    var tries = 0
                    while (task.stillEditing(after.state) && tries++ < 6) { sleep(500); after = phone.perceive() }
                    val (ok, info) = try { selfCheck(after, task.fastClaim) } catch (e: ModelException) { return fail(StopKind.MODEL_ERROR, e.message ?: "调用模型失败") }
                    if (ok) {
                        log.finish("成功：${task.fastClaim}", steps, calls, (System.nanoTime() - t0) / 1_000_000)
                        return LoopResult.Success(task.fastClaim, info ?: "", steps, calls)
                    }
                    history += Prompts.HistoryItem(null, "", "快速操作后自查", "自查未通过：$info")
                    sigBeforeAction = StabilityWaiter.signatureOf(first.state.elements)
                    pending = after
                } else {
                    failures++
                    pending = phone.perceive()
                }
            }
        }

        while (true) {
            if (steps >= cfg.maxSteps) return fail(StopKind.STEP_LIMIT, "已执行 ${cfg.maxSteps} 步仍未完成，已停止")
            if (calls >= cfg.maxModelCalls) return fail(StopKind.CALL_LIMIT, "模型调用已达上限（${cfg.maxModelCalls} 次），已停止")

            val screen = pending ?: phone.perceive()
            pending = null
            val sig = signature(screen)
            if (sigBeforeAction != null) {
                unchanged = if (sig == sigBeforeAction) unchanged + 1 else 0
                sigBeforeAction = null
                if (unchanged >= cfg.maxUnchanged) {
                    log.step(steps, screen, null, null, "连续 $unchanged 步界面没有变化")
                    return fail(StopKind.NO_CHANGE, "连续 ${cfg.maxUnchanged} 步操作后界面没有变化，已停止")
                }
            }

            val req = ModelRequest(
                label = "step",
                system = task.stepSystem,
                messages = listOf(ModelMessage("user",
                    Prompts.stepUser(taskText, confirmed, clock(), history.takeLast(cfg.historyWindow), screen.text, steps, cfg.maxSteps),
                    listOfNotNull(screen.state.screenshotJpeg))),
                schemaJson = Schemas.STEP,
                maxTokens = 1200,
                effort = "low",
                model = Models.step,
            )
            calls++
            val resp = try {
                model.complete(req).also { log.modelCall(req, it, null) }
            } catch (e: ModelException) {
                log.modelCall(req, null, e.message)
                log.step(steps, screen, null, null, "模型调用失败：${e.message}")
                return fail(StopKind.MODEL_ERROR, e.message ?: "调用模型失败")
            }
            var decision = try { StepDecision.parse(resp.json) } catch (e: ModelException) { null }
            if (decision == null && calls < cfg.maxModelCalls) {
                // 输出不合法：自动重试一次（同样的请求）
                calls++
                val retry = try { model.complete(req).also { log.modelCall(req, it, null) } } catch (e: ModelException) {
                    log.modelCall(req, null, e.message)
                    return fail(StopKind.MODEL_ERROR, e.message ?: "调用模型失败")
                }
                decision = try { StepDecision.parse(retry.json) } catch (e: ModelException) { null }
            }
            if (decision == null) {
                failures++
                history += Prompts.HistoryItem(null, "", "（上一步输出格式不对，重试一次仍不对）", "未执行")
                log.step(steps, screen, null, null, "输出不合法（重试后仍不合法）")
                if (failures >= cfg.maxConsecutiveFailures) return fail(StopKind.REPEATED_FAILURE, "模型连续 $failures 次给出无法执行的回复，已停止")
                continue
            }
            val a = decision.action

            when (a) {
                is Action.Done -> {
                    log.step(steps, screen, decision, null)
                    if (!a.success) return fail(StopKind.MODEL_GAVE_UP, a.result.ifBlank { "没能完成这件事" })
                    val (ok, info) = try { selfCheck(screen, a.result) } catch (e: ModelException) { return fail(StopKind.MODEL_ERROR, e.message ?: "调用模型失败") }
                    if (ok) {
                        log.finish("成功：${a.result}", steps, calls, (System.nanoTime() - t0) / 1_000_000)
                        return LoopResult.Success(a.result, info ?: "", steps, calls)
                    }
                    selfCheckFailures++
                    history += Prompts.HistoryItem(null, decision.understanding, "声称已完成：${a.result}", "自查未通过：$info")
                    if (selfCheckFailures >= cfg.maxSelfCheckFailures) return fail(StopKind.SELF_CHECK_FAILED, "没能确认事项已经创建：$info")
                }

                is Action.AskUser -> {
                    log.step(steps, screen, decision, null)
                    host.onStep(StepInfo(null, decision.understanding, a.describe(), screen))
                    val answer = host.askUser(a.question, a.options)
                    history += Prompts.HistoryItem(null, decision.understanding, "询问用户：${a.question}", "用户回答：$answer")
                }

                is Action.Wait -> {
                    log.step(steps, screen, decision, null)
                    host.onStep(StepInfo(null, decision.understanding, a.describe(), screen))
                    sleep(a.seconds * 1000L)
                    history += Prompts.HistoryItem(null, decision.understanding, a.describe(), "已等待")
                }

                else -> {
                    val key = sig + "|" + a
                    val times = (seen[key] ?: 0) + 1
                    seen[key] = times
                    if (times >= cfg.maxRepeats) {
                        log.step(steps, screen, decision, null, "同一个动作在同一个界面上重复了 $times 次")
                        return fail(StopKind.REPEATING, "同一界面反复做同一个操作（${a.describe()}），没有进展，已停止")
                    }
                    steps++
                    host.onStep(StepInfo(steps, decision.understanding, a.describe(), screen))
                    val out = phone.execute(a, screen.state)
                    log.step(steps, screen, decision, out)
                    history += Prompts.HistoryItem(steps, decision.understanding, a.describe(), if (out.ok) "成功" else "失败：${out.message}")
                    if (out.blocked) return fail(StopKind.BLOCKED, "操作被安全规则拦截（${out.message}），不会绕过")
                    if (out.ok) failures = 0 else {
                        failures++
                        if (failures >= cfg.maxConsecutiveFailures) return fail(StopKind.REPEATED_FAILURE, "连续 $failures 次操作失败（${out.message}），已停止")
                    }
                    sigBeforeAction = sig
                }
            }
        }
    }

    private fun signature(p: PerceptionResult) = StabilityWaiter.signatureOf(p.state.elements)
}

package com.android.phoneagent.task

import android.graphics.Bitmap
import com.android.phoneagent.agent.AgentLoop
import com.android.phoneagent.agent.CalendarTask
import com.android.phoneagent.agent.ClarifyOutcome
import com.android.phoneagent.agent.Clarifier
import com.android.phoneagent.agent.ConfirmDecision
import com.android.phoneagent.agent.ShoppingFlow
import com.android.phoneagent.agent.ShoppingHost
import com.android.phoneagent.agent.ShoppingResult
import com.android.phoneagent.agent.ShoppingTask
import com.android.phoneagent.agent.DialogTurn
import com.android.phoneagent.agent.EmailFlow
import com.android.phoneagent.agent.EmailHost
import com.android.phoneagent.agent.EmailResult
import com.android.phoneagent.agent.EmailTask
import com.android.phoneagent.perception.Draft
import com.android.phoneagent.agent.LoopConfig
import com.android.phoneagent.agent.LoopHost
import com.android.phoneagent.agent.LoopResult
import com.android.phoneagent.agent.ModelClient
import com.android.phoneagent.agent.PhoneController
import com.android.phoneagent.agent.RunLog
import com.android.phoneagent.agent.StepInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** 任务运行的外部环境（真实实现用虚拟屏和 Shizuku；测试里用假的）。 */
interface TaskEnvironment {
    /** 确保能操作手机并返回控制器；失败抛 [IllegalStateException]（message 给用户看） */
    suspend fun prepare(): PhoneController
    fun newRunLog(): RunLog
    /** 虚拟屏当前画面的小图（宽约 [width] 像素），没有则 null */
    fun thumbnail(width: Int): Bitmap?
    /** 把虚拟屏上的 Amazon 移到主屏并释放虚拟屏（交接）。 */
    suspend fun handoffToMain()

    /** 任务结束或被停止：关掉虚拟屏上的任务并释放 */
    suspend fun release()
}

/**
 * 一条对话任务的完整流程：澄清（追问 → 复述）→ 执行循环 → 结果。界面通过 [TaskBackend] 提交文字、订阅事件。
 * 同一时间只运行一个任务（PRD FR-LOOP-5）。
 */
class TaskCoordinator(
    private val scope: CoroutineScope,
    private val model: ModelClient,
    private val env: TaskEnvironment,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val cfg: LoopConfig = LoopConfig(),
) : TaskBackend {
    private val _events = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<TaskEvent> = _events

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy

    private val lock = Any()
    private var session: Job? = null
    private var pendingAnswer: CompletableDeferred<String>? = null
    private var pendingConfirm: CompletableDeferred<ConfirmChoice>? = null
    private var earlyInstruction: String? = null   // 确认卡片等待期间用户直接打字：当作修改要求
    @Volatile private var handoffPending = false   // 已停在提交订单页、虚拟屏保留着等用户点「去完成」
    private val sendAttempted = HashSet<String>()   // 点过发送的邮件（收件人|主题）：重试前必须先查「已发送」

    override fun submit(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        synchronized(lock) {
            pendingAnswer?.let { it.complete(t); pendingAnswer = null; return false }
            pendingConfirm?.let { earlyInstruction = t; it.complete(ConfirmChoice.MODIFY); pendingConfirm = null; return false }
            if (session?.isActive == true) {
                emit(TaskEvent.AgentSaid("我还在处理上一件事，你可以先停止它。"))
                return false
            }
            _busy.value = true
            session = scope.launch { runSession(t) }
            return true
        }
    }

    override fun stop() {
        val job: Job?
        synchronized(lock) { job = session; pendingAnswer = null; pendingConfirm = null }
        if (job?.isActive != true) return
        job.cancel()
        scope.launch {
            job.join()
            withContext(NonCancellable) { env.release() }
            emit(TaskEvent.Stopped("已停止。"))
            _busy.value = false
        }
    }

    override fun handoff() {
        if (!handoffPending) return
        scope.launch {
            handoffPending = false
            withContext(NonCancellable) { env.handoffToMain() }
            emit(TaskEvent.AgentSaid("已把 Amazon 移到你的屏幕上，下单和付款由你来完成。"))
        }
    }

    override fun confirm(choice: ConfirmChoice) {
        synchronized(lock) { pendingConfirm?.complete(choice); pendingConfirm = null }
    }

    private fun emit(e: TaskEvent) {
        // 事件摘要写到 logcat（tag PA），自动化测试脚本靠它判断进度
        android.util.Log.i("PA", "TASK " + when (e) {
            is TaskEvent.AgentSaid -> "说：${e.text}"
            is TaskEvent.Ask -> "问：${e.text} 选项=${e.options}"
            is TaskEvent.Started -> "开始：${e.title}"
            is TaskEvent.Progress -> "进展 step=${e.step} ${e.did}｜${e.saw}"
            is TaskEvent.Confirm -> "确认卡片：收件人=${e.to} 主题=${e.subject}"
            is TaskEvent.Handoff -> "交接：${e.where}｜${e.summary}"
            is TaskEvent.Succeeded -> "成功：${e.title} ${e.detail}"
            is TaskEvent.Failed -> "失败：${e.reason}"
            is TaskEvent.Stopped -> "停止：${e.reason}"
        })
        _events.tryEmit(e)
    }

    private suspend fun awaitUser(): String {
        val d = CompletableDeferred<String>()
        synchronized(lock) { pendingAnswer = d }
        return d.await()
    }

    private suspend fun runSession(first: String) {
        if (handoffPending) { handoffPending = false; withContext(NonCancellable) { env.release() } }   // 上一次交接的虚拟屏还留着：先清掉
        val log = env.newRunLog()
        // 提速：一看就是日历类的话，澄清的同时就在后台把虚拟屏准备好（创建虚拟屏要几秒），澄清完直接开始。
        // 结果不是开始任务（只回复/出错）时在 finally 里释放
        var prefetch: Deferred<Result<PhoneController>>? =
            if (CALENDAR_HINT.containsMatchIn(first)) scope.async { runCatching { env.prepare() } } else null
        try {
            val clarifier = Clarifier(model, clock, log = log)
            val dialog = arrayListOf(DialogTurn(true, first))
            var rounds = 0
            while (true) {
                when (val o = clarifier.clarify(dialog)) {
                    is ClarifyOutcome.Failed -> { emit(TaskEvent.AgentSaid("出了点问题：${o.reason}")); return }
                    is ClarifyOutcome.Reply -> { emit(TaskEvent.AgentSaid(o.text)); return }
                    is ClarifyOutcome.Ask -> {
                        if (++rounds > MAX_CLARIFY_ROUNDS) { emit(TaskEvent.AgentSaid("信息还是不够，你换个说法再告诉我一次吧。")); return }
                        dialog += DialogTurn(false, o.question)
                        emit(TaskEvent.Ask(o.question, o.options))
                        dialog += DialogTurn(true, awaitUser())
                    }
                    is ClarifyOutcome.ReadyEmail -> {
                        emit(TaskEvent.AgentSaid(o.restatement))
                        val p = prefetch; prefetch = null
                        executeEmail(o.task, log, p)
                        return
                    }
                    is ClarifyOutcome.ReadyShopping -> {
                        emit(TaskEvent.AgentSaid(o.restatement))
                        val p = prefetch; prefetch = null
                        executeShopping(o.task, log, p)
                        return
                    }
                    is ClarifyOutcome.Ready -> {
                        emit(TaskEvent.AgentSaid(o.restatement))
                        val p = prefetch; prefetch = null
                        execute(o.task, log, p)
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e   // stop() 负责收尾
        } finally {
            prefetch?.let { p -> withContext(NonCancellable) { runCatching { p.await() }; env.release() } }
            if (session?.isCancelled != true) _busy.value = false
        }
    }

    private suspend fun execute(task: CalendarTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("日历 · ${task.title}", cfg.maxSteps))
        var lastStep = 0
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好虚拟屏", emptyList())); return
            }
            val host = object : LoopHost {
                override fun onStep(info: StepInfo) {
                    info.number?.let { lastStep = it }
                    emit(TaskEvent.Progress(info.number, cfg.maxSteps, info.saw, info.did, env.thumbnail(THUMB_W)))
                }
                override suspend fun askUser(question: String, options: List<String>): String {
                    emit(TaskEvent.Ask(question, options))
                    return awaitUser()
                }
            }
            val result = AgentLoop(model, phone, host, cfg, clock, log).run(task)
            val image = env.thumbnail(EVIDENCE_W)   // 释放虚拟屏之前取最后一帧
            when (result) {
                is LoopResult.Success -> emit(TaskEvent.Succeeded(
                    "日历里已有「${task.title}」",
                    "${task.date.monthValue}月${task.date.dayOfMonth}日（${com.android.phoneagent.agent.weekdayZh(task.date.dayOfWeek)}） " +
                        "%02d:%02d–%02d:%02d".format(task.start.hour, task.start.minute, task.end.hour, task.end.minute),
                    listOf(result.evidence.ifBlank { "在界面上看到了该事项" }, "标题和时间与你说的一致（${result.steps} 步，${result.modelCalls} 次模型调用）"),
                    image,
                ))
                is LoopResult.Failure -> emit(TaskEvent.Failed(result.reason, result.leftovers))
            }
        } finally {
            withContext(NonCancellable) { env.release() }
        }
    }

    private suspend fun executeShopping(task: ShoppingTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("Amazon · ${task.product ?: "购物车"}", cfg.maxSteps))
        var handedOff = false
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好虚拟屏", emptyList())); return
            }
            val host = object : ShoppingHost {
                override fun onStep(info: StepInfo) {
                    emit(TaskEvent.Progress(info.number, cfg.maxSteps, info.saw, info.did, env.thumbnail(THUMB_W)))
                }
                override suspend fun askUser(question: String, options: List<String>): String {
                    emit(TaskEvent.Ask(question, options))
                    return awaitUser()
                }
                override fun say(text: String) = emit(TaskEvent.AgentSaid(text))
                override suspend fun confirmCartChanges(lines: List<String>): Boolean {
                    emit(TaskEvent.Confirm("Amazon 购物车", "确认修改购物车", lines.joinToString("\n") { "· $it" }, kind = "cart"))
                    val d = CompletableDeferred<ConfirmChoice>()
                    synchronized(lock) { earlyInstruction = null; pendingConfirm = d }
                    return d.await() == ConfirmChoice.SEND
                }
            }
            when (val r = ShoppingFlow(model, phone, host, cfg, clock, log).run(task)) {
                is ShoppingResult.Handoff -> {
                    handedOff = true; handoffPending = true
                    emit(TaskEvent.Handoff("已到提交订单页", r.summary + "。我没有点下单，由你来付款。"))
                }
                is ShoppingResult.Cancelled -> emit(TaskEvent.Stopped(r.note))
                is ShoppingResult.Failed -> emit(TaskEvent.Failed(r.reason, r.leftovers))
            }
        } finally {
            if (!handedOff) withContext(NonCancellable) { env.release() }   // 交接成功时虚拟屏保留，等用户点「去完成」
        }
    }

    private suspend fun executeEmail(task: EmailTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("邮件 · ${task.recipient}", cfg.maxSteps))
        val key = task.recipient.lowercase() + "|" + task.subject
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好虚拟屏", emptyList())); return
            }
            val host = object : EmailHost {
                override fun onStep(info: StepInfo) {
                    emit(TaskEvent.Progress(info.number, cfg.maxSteps, info.saw, info.did, env.thumbnail(THUMB_W)))
                }
                override suspend fun askUser(question: String, options: List<String>): String {
                    emit(TaskEvent.Ask(question, options))
                    return awaitUser()
                }
                override fun say(text: String) = emit(TaskEvent.AgentSaid(text))
                override suspend fun confirm(draft: Draft): ConfirmDecision {
                    emit(TaskEvent.Confirm(draft.to.joinToString(", "), draft.subject, draft.body))
                    val d = CompletableDeferred<ConfirmChoice>()
                    synchronized(lock) { earlyInstruction = null; pendingConfirm = d }
                    return when (d.await()) {
                        ConfirmChoice.SEND -> ConfirmDecision.Send
                        ConfirmChoice.CANCEL -> ConfirmDecision.Cancel
                        ConfirmChoice.MODIFY -> {
                            val early = synchronized(lock) { earlyInstruction.also { earlyInstruction = null } }
                            if (early != null) ConfirmDecision.Modify(early) else {
                                emit(TaskEvent.Ask("想怎么改？直接告诉我就行。", emptyList()))
                                ConfirmDecision.Modify(awaitUser())
                            }
                        }
                    }
                }
            }
            val result = EmailFlow(model, phone, host, cfg, clock, log).run(task, checkSentFirst = key in sendAttempted)
            val image = env.thumbnail(EVIDENCE_W)
            when (result) {
                is EmailResult.Sent -> {
                    sendAttempted += key
                    emit(TaskEvent.Succeeded("邮件已发送给 ${result.draft.to.joinToString(", ")}", "主题：${result.draft.subject}",
                        listOf(result.evidence.ifBlank { "在「已发送」里找到了这封邮件" }, "收件人和主题与你确认的一致"), image))
                }
                is EmailResult.AlreadySent -> emit(TaskEvent.Succeeded("这封邮件之前已经发出，没有重复发送", "收件人：${task.recipient}　主题：${task.subject}",
                    listOf(result.evidence.ifBlank { "在「已发送」里找到了这封邮件" }), image))
                is EmailResult.Cancelled -> emit(TaskEvent.Stopped("已取消。" + result.note))
                is EmailResult.Failed -> { if (result.sendAttempted) sendAttempted += key; emit(TaskEvent.Failed(result.reason, result.leftovers)) }
            }
        } finally {
            withContext(NonCancellable) { env.release() }
        }
    }

    companion object {
        const val MAX_CLARIFY_ROUNDS = 6
        private val CALENDAR_HINT = Regex("日历|事项|提醒|会议|开会|约|预约|日程|邮件|邮箱|发给|购物|亚马逊|amazon|购买|买|结算|mail|email|calendar|schedule|remind|meeting|event", RegexOption.IGNORE_CASE)
        private const val THUMB_W = 360
        private const val EVIDENCE_W = 540
    }
}

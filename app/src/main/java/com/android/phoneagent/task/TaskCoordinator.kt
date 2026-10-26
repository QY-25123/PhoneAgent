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
import com.android.phoneagent.agent.MapsFlow
import com.android.phoneagent.agent.MapsHost
import com.android.phoneagent.agent.MapsResult
import com.android.phoneagent.agent.MapsReader
import com.android.phoneagent.agent.MapsTask
import com.android.phoneagent.agent.DoordashFlow
import com.android.phoneagent.agent.DoordashHost
import com.android.phoneagent.agent.DoordashResult
import com.android.phoneagent.agent.DoordashTask
import com.android.phoneagent.agent.WechatFlow
import com.android.phoneagent.agent.WechatHost
import com.android.phoneagent.agent.WechatResult
import com.android.phoneagent.agent.WechatTask
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
    /** 执行期间推虚拟屏小图的间隔；0 表示不推（单元测试里用，否则虚拟时间永远推不完） */
    private val frameIntervalMs: Long = 16,
) : TaskBackend {
    private val _events = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<TaskEvent> = _events

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy

    private val lock = Any()
    private val history = ArrayList<TaskEvent>()
    override fun history(): List<TaskEvent> = synchronized(history) { history.toList() }
    override fun clearHistory() {
        if (_busy.value) return
        // A previous task that ended in a hand-off keeps its virtual display waiting for the "go finish" tap: a fresh launch drops that too
        if (handoffPending) { handoffPending = false; scope.launch { withContext(NonCancellable) { env.release() } } }
        synchronized(history) { history.clear() }
    }
    private var session: Job? = null
    private var pendingAnswer: CompletableDeferred<String>? = null
    private var pendingConfirm: CompletableDeferred<ConfirmChoice>? = null
    private var earlyInstruction: String? = null   // 确认卡片等待期间用户直接打字：当作修改要求
    @Volatile private var handoffNote = "已打开 Amazon，下单和付款请你来完成。"
    @Volatile private var handoffPending = false   // 已停在提交订单页、虚拟屏保留着等用户点「去完成」

    override fun submit(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        emit(TaskEvent.UserSaid(t))
        synchronized(lock) {
            pendingAnswer?.let { it.complete(t); pendingAnswer = null; return false }
            pendingConfirm?.let { earlyInstruction = t; it.complete(ConfirmChoice.MODIFY); pendingConfirm = null; emit(TaskEvent.ConfirmResolved(ConfirmChoice.MODIFY)); return false }
            if (session?.isActive == true) {
                emit(TaskEvent.AgentSaid("上一件事还在处理，可以先点停止。"))
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
            emit(TaskEvent.HandoffDone)
            emit(TaskEvent.AgentSaid(handoffNote))
        }
    }

    override fun confirm(choice: ConfirmChoice) {
        val had = synchronized(lock) { pendingConfirm?.complete(choice); (pendingConfirm != null).also { pendingConfirm = null } }
        if (had) emit(TaskEvent.ConfirmResolved(choice))
    }

    private fun emit(e: TaskEvent) {
        synchronized(history) {
            history += if (e is TaskEvent.Progress && e.thumb != null) e.copy(thumb = null) else e
            if (history.size > 400) history.removeAt(0)
        }
        // 事件摘要写到 logcat（tag PA），自动化测试脚本靠它判断进度
        android.util.Log.i("PA", "TASK " + when (e) {
            is TaskEvent.Frame -> "画面"
            is TaskEvent.UserSaid -> "用户：${e.text}"
            is TaskEvent.AgentSaid -> "说：${e.text}"
            is TaskEvent.Ask -> "问：${e.text} 选项=${e.options}"
            is TaskEvent.Started -> "开始：${e.title}"
            is TaskEvent.Progress -> "进展 step=${e.step} ${e.did}｜${e.saw}"
            is TaskEvent.Confirm -> "确认卡片：收件人=${e.to} 主题=${e.subject} 内容=${e.body.take(60)}"
            is TaskEvent.ConfirmResolved -> "确认卡片已选择：${e.choice}"
            is TaskEvent.HandoffDone -> "交接完成"
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
                    is ClarifyOutcome.Failed -> { emit(TaskEvent.AgentSaid("出错了：${o.reason}")); return }
                    is ClarifyOutcome.Reply -> { emit(TaskEvent.AgentSaid(o.text)); return }
                    is ClarifyOutcome.Ask -> {
                        if (++rounds > MAX_CLARIFY_ROUNDS) { emit(TaskEvent.AgentSaid("信息还不够，换个说法再说一次吧。")); return }
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
                    is ClarifyOutcome.ReadyWechat -> {
                        emit(TaskEvent.AgentSaid(o.restatement))
                        val p = prefetch; prefetch = null
                        executeWechat(o.task, log, p)
                        return
                    }
                    is ClarifyOutcome.ReadyMaps -> {
                        emit(TaskEvent.AgentSaid(o.restatement))
                        val p = prefetch; prefetch = null
                        executeMaps(o.task, log, p)
                        return
                    }
                    is ClarifyOutcome.ReadyDoordash -> {
                        emit(TaskEvent.AgentSaid(o.restatement))
                        val p = prefetch; prefetch = null
                        executeDoordash(o.task, log, p)
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

    /** 等用户在确认卡片上选发送 / 修改 / 取消；确认期间直接打字当作修改要求。 */
    private suspend fun awaitDecision(): ConfirmDecision {
        val d = CompletableDeferred<ConfirmChoice>()
        synchronized(lock) { earlyInstruction = null; pendingConfirm = d }
        return when (d.await()) {
            ConfirmChoice.SEND -> ConfirmDecision.Send
            ConfirmChoice.CANCEL -> ConfirmDecision.Cancel
            ConfirmChoice.MODIFY -> {
                val early = synchronized(lock) { earlyInstruction.also { earlyInstruction = null } }
                if (early != null) ConfirmDecision.Modify(early) else {
                    emit(TaskEvent.Ask("想怎么改？", emptyList()))
                    ConfirmDecision.Modify(awaitUser())
                }
            }
        }
    }

    private suspend fun executeWechat(task: WechatTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("微信 · ${task.contact}", cfg.maxSteps))
        val frames = startFrames()
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好，请确认 Shizuku 在运行", emptyList())); return
            }
            val host = object : WechatHost {
                override fun onStep(info: StepInfo) {
                    emit(TaskEvent.Progress(info.number, cfg.maxSteps, info.saw, info.did, env.thumbnail(THUMB_W)))
                }
                override suspend fun askUser(question: String, options: List<String>): String {
                    emit(TaskEvent.Ask(question, options))
                    return awaitUser()
                }
                override fun say(text: String) = emit(TaskEvent.AgentSaid(text))
            }
            val result = WechatFlow(model, phone, host, cfg, clock, log).run(task)
            val image = env.thumbnail(EVIDENCE_W)
            when (result) {
                is WechatResult.Sent -> emit(TaskEvent.Succeeded("微信消息已发送给 ${result.draft.to}", "内容：${result.draft.text}",
                    listOf(result.evidence.ifBlank { "消息出现在聊天记录里，没有发送失败的标记" }, "对方和内容与你确认的一致"), image))
                is WechatResult.Failed -> emit(TaskEvent.Failed(result.reason, result.leftovers))
            }
        } finally {
            frames.cancel()
            withContext(NonCancellable) { env.release() }
        }
    }

    private suspend fun executeMaps(task: MapsTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("地图 · ${task.destination}", cfg.maxSteps))
        var handedOff = false
        val frames = startFrames()
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好，请确认 Shizuku 在运行", emptyList())); return
            }
            val host = object : MapsHost {
                override fun onStep(info: StepInfo) {
                    emit(TaskEvent.Progress(info.number, cfg.maxSteps, info.saw, info.did, env.thumbnail(THUMB_W)))
                }
                override suspend fun askUser(question: String, options: List<String>): String {
                    emit(TaskEvent.Ask(question, options))
                    return awaitUser()
                }
                override fun say(text: String) = emit(TaskEvent.AgentSaid(text))
            }
            when (val r = MapsFlow(model, phone, host, cfg, clock, log).run(task)) {
                is MapsResult.Done -> {
                    handedOff = true; handoffPending = true
                    handoffNote = "已打开 Google 地图。"
                    val lines = r.rows.joinToString("\n") { row ->
                        if (!row.available) "${row.mode.zh}：不可用"
                        else "${row.mode.zh}：${MapsReader.fmtMinutes(row.minutes!!)}" + (row.distance?.let { " · $it" } ?: "") + (if (row.mode == r.fastest) "  ⚡最快" else "")
                    }
                    emit(TaskEvent.Handoff("到「${task.destination}」的路线", lines + (r.note?.let { "\n$it" } ?: ""), action = "去查看", heading = "驾车耗时"))
                }
                is MapsResult.Failed -> emit(TaskEvent.Failed(r.reason, r.leftovers))
            }
        } finally {
            frames.cancel()
            if (!handedOff) withContext(NonCancellable) { env.release() }   // 结果出来后虚拟屏保留，等用户点「去查看」
        }
    }

    private suspend fun execute(task: CalendarTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("日历 · ${task.title}", cfg.maxSteps))
        var lastStep = 0
        val frames = startFrames()
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好，请确认 Shizuku 在运行", emptyList())); return
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
                    listOf(result.evidence.ifBlank { "在日程里看到了该事项" }, "标题和时间与你说的一致"),
                    image,
                ))
                is LoopResult.Failure -> emit(TaskEvent.Failed(result.reason, result.leftovers))
            }
        } finally {
            frames.cancel()
            withContext(NonCancellable) { env.release() }
        }
    }

    /** 执行期间每隔约 1 秒推一张虚拟屏小图，让实时画面卡片和全屏观看是流动的（不进历史）。 */
    private fun startFrames(): Job = scope.launch {
        var lastSeq = -1L
        while (frameIntervalMs > 0) {
            kotlinx.coroutines.delay(frameIntervalMs)
            // Only push when the display produced a new frame (or a tap ring is animating): no point re-sending the same picture
            val seq = Agent.framesProcessed.get()
            val ringOn = Agent.lastTap?.let { System.currentTimeMillis() - it.atMs < Agent.TAP_MARK_MS } == true
            if (seq != lastSeq || ringOn) {
                lastSeq = seq
                env.thumbnail(FRAME_W)?.let { _events.tryEmit(TaskEvent.Frame(it)) }
            }
        }
    }

    private suspend fun executeDoordash(task: DoordashTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("DoorDash · ${task.restaurant}", cfg.maxSteps))
        var handedOff = false
        val frames = startFrames()
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好，请确认 Shizuku 在运行", emptyList())); return
            }
            val host = object : DoordashHost {
                override fun onStep(info: StepInfo) {
                    emit(TaskEvent.Progress(info.number, cfg.maxSteps, info.saw, info.did, env.thumbnail(THUMB_W)))
                }
                override suspend fun askUser(question: String, options: List<String>): String {
                    emit(TaskEvent.Ask(question, options))
                    return awaitUser()
                }
                override fun say(text: String) = emit(TaskEvent.AgentSaid(text))
            }
            when (val r = DoordashFlow(model, phone, host, cfg, clock, log).run(task)) {
                is DoordashResult.Handoff -> {
                    handedOff = true; handoffPending = true
                    handoffNote = "已打开 DoorDash，下单和付款请你来完成。"
                    emit(TaskEvent.Handoff("已到结账页", r.summary + "。下单和付款请你来完成。"))
                }
                is DoordashResult.Failed -> emit(TaskEvent.Failed(r.reason, r.leftovers))
            }
        } finally {
            frames.cancel()
            if (!handedOff) withContext(NonCancellable) { env.release() }   // 交接成功时虚拟屏保留，等用户点「去完成」
        }
    }

    private suspend fun executeShopping(task: ShoppingTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("Amazon · ${task.product ?: "购物车"}", cfg.maxSteps))
        var handedOff = false
        val frames = startFrames()
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好，请确认 Shizuku 在运行", emptyList())); return
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
            }
            when (val r = ShoppingFlow(model, phone, host, cfg, clock, log).run(task)) {
                is ShoppingResult.Handoff -> {
                    handedOff = true; handoffPending = true
                    handoffNote = "已打开 Amazon，下单和付款请你来完成。"
                    emit(TaskEvent.Handoff("已到提交订单页", r.summary + "。下单和付款请你来完成。"))
                }
                is ShoppingResult.Cancelled -> emit(TaskEvent.Stopped(r.note))
                is ShoppingResult.Failed -> emit(TaskEvent.Failed(r.reason, r.leftovers))
            }
        } finally {
            frames.cancel()
            if (!handedOff) withContext(NonCancellable) { env.release() }   // 交接成功时虚拟屏保留，等用户点「去完成」
        }
    }

    private suspend fun executeEmail(task: EmailTask, log: RunLog, prefetched: Deferred<Result<PhoneController>>?) {
        emit(TaskEvent.Started("邮件 · ${task.recipient}", cfg.maxSteps))
        val frames = startFrames()
        try {
            val phone = try { prefetched?.await()?.getOrThrow() ?: env.prepare() } catch (e: IllegalStateException) {
                emit(TaskEvent.Failed(e.message ?: "没能准备好，请确认 Shizuku 在运行", emptyList())); return
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
                    return awaitDecision()
                }
            }
            val result = EmailFlow(model, phone, host, cfg, clock, log).run(task)
            val image = env.thumbnail(EVIDENCE_W)
            when (result) {
                is EmailResult.Sent -> {
                    emit(TaskEvent.Succeeded("邮件已发送给 ${result.draft.to.joinToString(", ")}", "主题：${result.draft.subject}",
                        listOf(result.evidence, "收件人和主题与你确认的一致"), image))
                }
                is EmailResult.Cancelled -> emit(TaskEvent.Stopped("已取消。" + result.note))
                is EmailResult.Failed -> emit(TaskEvent.Failed(result.reason, result.leftovers))
            }
        } finally {
            frames.cancel()
            withContext(NonCancellable) { env.release() }
        }
    }

    companion object {
        const val MAX_CLARIFY_ROUNDS = 6
        private val CALENDAR_HINT = Regex("日历|事项|提醒|会议|开会|约|预约|日程|邮件|邮箱|微信|wechat|地图|路线|多久|导航|maps|发给|购物|亚马逊|amazon|购买|买|结算|外卖|点餐|doordash|wingstop|mail|email|calendar|schedule|remind|meeting|event", RegexOption.IGNORE_CASE)
        private const val THUMB_W = 360
        private const val FRAME_W = 540   // live frames for the floating window / full-screen watch: sharper than the step thumbnails
        private const val EVIDENCE_W = 540
    }
}

package com.android.phoneagent.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.android.phoneagent.task.ConfirmChoice
import com.android.phoneagent.task.TaskBackend
import com.android.phoneagent.task.TaskEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 用户在确认卡片上的选择。 */
enum class ConfirmAction { SEND, MODIFY, CANCEL }

/**
 * 对话界面的假数据控制器：用脚本模拟一次完整流程（不接入 agent 逻辑，也不碰技术验证的核心代码）。
 * 流程：用户任务 → agent 简短回复 + 头像动画 → 实时画面卡片（步骤每隔几秒推进）→ 确认/提问/交接 → 结果。
 */
class ChatController(private val stepMs: Long = 2500, private val backend: TaskBackend? = null) {
    // 脚本协程由控制器自己持有：界面切到全屏观看再回来（对话页被销毁重建）时，任务不能被取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val items = mutableStateListOf<ChatItem>()
    val steps = mutableStateListOf<StepRecord>()
    var workPulse by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var tapPoint by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    private var nextId = 1L
    private var job: Job? = null
    private var confirmWaiter: CompletableDeferred<ConfirmAction>? = null
    private var optionWaiter: CompletableDeferred<Int>? = null
    private var textWaiter: CompletableDeferred<String>? = null

    val activeLive: ChatItem.Live? get() = items.lastOrNull { it is ChatItem.Live } as? ChatItem.Live

    // ---------------- 真实流程（M2）：订阅 task 层的事件 ----------------
    // 界面只依赖 task 暴露的 [TaskBackend]；没有 backend 时仍是上面的假数据演示（画廊、开发者调试用）
    private var lastTask: String? = null

    init {
        backend?.let { b ->
            b.history().forEach { handle(it) }   // 先回放已有的对话（App 被关掉重开、别的入口提交的任务也能看到）
            scope.launch { b.events.collect { handle(it) } }
            scope.launch { b.busy.collect { busy = it } }
        }
    }

    private fun handle(e: TaskEvent) {
        when (e) {
            is TaskEvent.Frame -> activeLive?.let { l ->
                if (l.status == LiveStatus.RUNNING || l.status == LiveStatus.WAITING) replace(l.id) { (it as ChatItem.Live).copy(thumbImage = e.thumb.asImageBitmap()) }
            }
            is TaskEvent.UserSaid -> add(ChatItem.User(id(), e.text))
            is TaskEvent.AgentSaid -> add(ChatItem.Agent(id(), e.text))
            is TaskEvent.Ask -> {
                if (e.options.isEmpty()) add(ChatItem.Agent(id(), e.text)) else add(ChatItem.Question(id(), e.text, e.options, null))
                if (activeLive?.status == LiveStatus.RUNNING) liveStatus(LiveStatus.WAITING, "等你回答")
            }
            is TaskEvent.Started -> {
                workPulse++
                steps.clear()
                add(ChatItem.Live(id(), e.title, 0, e.maxSteps, "准备中…", LiveStatus.RUNNING, Mocks.calendar))
            }
            is TaskEvent.Progress -> {
                val live = activeLive ?: return
                steps.add(StepRecord(e.step, e.saw, e.did))
                replace(live.id) {
                    (it as ChatItem.Live).copy(
                        step = e.step ?: it.step, stepText = "正在执行任务", status = LiveStatus.RUNNING,
                        thumbImage = e.thumb?.asImageBitmap() ?: it.thumbImage,
                    )
                }
            }
            is TaskEvent.Confirm -> {
                items.filter { it is ChatItem.Confirm && it.state == ConfirmState.PENDING }.forEach { c -> replace(c.id) { (it as ChatItem.Confirm).copy(state = ConfirmState.SUPERSEDED) } }
                add(ChatItem.Confirm(id(), e.to, e.subject, e.body, ConfirmState.PENDING, e.kind))
                liveStatus(LiveStatus.WAITING, if (e.kind == "cart") "等你确认修改购物车" else "等你确认后发送")
            }
            is TaskEvent.ConfirmResolved -> {
                val pending = items.lastOrNull { it is ChatItem.Confirm && it.state == ConfirmState.PENDING } as? ChatItem.Confirm ?: return
                replace(pending.id) {
                    (it as ChatItem.Confirm).copy(state = when (e.choice) {
                        ConfirmChoice.SEND -> ConfirmState.SENDING
                        ConfirmChoice.MODIFY -> ConfirmState.SUPERSEDED
                        ConfirmChoice.CANCEL -> ConfirmState.CANCELLED
                    })
                }
                if (e.choice == ConfirmChoice.SEND) liveStatus(LiveStatus.RUNNING, if (pending.kind == "cart") "正在修改购物车" else "正在发送")
            }
            is TaskEvent.HandoffDone -> items.lastOrNull { it is ChatItem.Handoff }?.let { h -> replace(h.id) { (it as ChatItem.Handoff).copy(done = true) } }
            is TaskEvent.Handoff -> {
                items.lastOrNull { it is ChatItem.Confirm && it.state == ConfirmState.SENDING }?.let { c -> replace(c.id) { (it as ChatItem.Confirm).copy(state = ConfirmState.SENT) } }
                liveStatus(LiveStatus.DONE, e.where)
                add(ChatItem.Handoff(id(), e.where, e.summary, false, e.action, e.heading))
            }
            is TaskEvent.Succeeded -> {
                items.lastOrNull { it is ChatItem.Confirm && it.state == ConfirmState.SENDING }?.let { c -> replace(c.id) { (it as ChatItem.Confirm).copy(state = ConfirmState.SENT) } }
                liveStatus(LiveStatus.DONE, e.title)
                add(ChatItem.Success(id(), e.title, e.detail, e.evidence, null, e.image?.asImageBitmap()))
            }
            is TaskEvent.Failed -> {
                liveStatus(LiveStatus.STOPPED, "已停止")
                add(ChatItem.Failure(id(), e.reason, e.leftovers))
            }
            is TaskEvent.Stopped -> {
                liveStatus(LiveStatus.STOPPED, "已停止")
                add(ChatItem.Agent(id(), e.reason))
            }
        }
    }

    /** 失败卡片上的「重试」：从头再来一次（PRD FR-CON-5）。 */
    fun retry() {
        val t = lastTask
        if (backend == null || t == null) return submit("重试")
        add(ChatItem.Agent(id(), "重试中。"))
        backend.submit(t)
    }

    private fun id() = nextId++
    private fun add(item: ChatItem) { items.add(item) }
    private fun replace(id: Long, f: (ChatItem) -> ChatItem) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = f(items[i])
    }

    // ---------------- 入口 ----------------

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (backend != null) {
            if (backend.submit(t)) lastTask = t   // 用户气泡由后端的 UserSaid 事件统一添加
            return
        }
        add(ChatItem.User(id(), t))
        textWaiter?.let { it.complete(t); textWaiter = null; return }
        if (busy) {
            add(ChatItem.Agent(id(), "我还在处理上一件事，你可以先停止它。"))
            return
        }
        job = scope.launch {
            busy = true
            try {
                when {
                    "邮件" in t || "mail" in t.lowercase() -> gmailFlow()
                    "日历" in t || "会议" in t || "事项" in t -> calendarFlow()
                    "Amazon" in t || "亚马逊" in t || "购物" in t -> amazonFlow()
                    else -> add(ChatItem.Agent(id(), "这个任务 v1 还不支持。我现在会：日历创建事项、Gmail 写邮件、Amazon 备购物车。"))
                }
            } finally {
                busy = false
            }
        }
    }

    fun onConfirm(a: ConfirmAction) {
        if (backend != null) {
            // 卡片状态由后端的 ConfirmResolved 事件更新（这样别的入口做的选择，界面也会同步，按钮不会留着）
            backend.confirm(when (a) { ConfirmAction.SEND -> ConfirmChoice.SEND; ConfirmAction.MODIFY -> ConfirmChoice.MODIFY; ConfirmAction.CANCEL -> ConfirmChoice.CANCEL })
            return
        }
        confirmWaiter?.complete(a); confirmWaiter = null
    }
    fun onOption(questionId: Long, index: Int) {
        replace(questionId) { (it as ChatItem.Question).copy(chosen = index) }
        val q = items.firstOrNull { it.id == questionId } as? ChatItem.Question
        if (backend != null) { if (q != null) backend.submit(q.options[index]); return }
        if (q != null) add(ChatItem.User(id(), q.options[index]))
        optionWaiter?.complete(index); optionWaiter = null
    }

    fun stop() {
        if (backend != null) { if (busy) backend.stop(); return }
        if (!busy) return
        job?.cancel()
        confirmWaiter = null; optionWaiter = null; textWaiter = null
        busy = false; paused = false
        activeLive?.let { l -> replace(l.id) { (it as ChatItem.Live).copy(status = LiveStatus.STOPPED, stepText = "已停止") } }
        items.lastOrNull { it is ChatItem.Confirm && (it as ChatItem.Confirm).state == ConfirmState.PENDING }?.let { c ->
            replace(c.id) { (it as ChatItem.Confirm).copy(state = ConfirmState.CANCELLED) }
        }
        add(ChatItem.Agent(id(), "已停止。"))
    }

    fun togglePause() {
        paused = !paused
        activeLive?.let { l -> replace(l.id) { (it as ChatItem.Live).copy(status = if (paused) LiveStatus.PAUSED else LiveStatus.RUNNING) } }
    }

    fun goHandoff(handoffId: Long) {
        replace(handoffId) { (it as ChatItem.Handoff).copy(done = true) }
        backend?.handoff()
    }

    /** 画廊用：显示一个点击位置圆点。 */
    fun markTap() { tapPoint = 0.5f to 0.4f }

    fun reset() {
        job?.cancel(); items.clear(); steps.clear(); busy = false; paused = false; confirmWaiter = null; optionWaiter = null; textWaiter = null
    }

    // ---------------- 脚本 ----------------

    private suspend fun waitWhilePaused() { while (paused) delay(200) }

    private suspend fun start(task: String, title: String, total: Int = 30) {
        add(ChatItem.Agent(id(), "好的，我来处理。"))
        workPulse++
        delay(600)
        steps.clear()
        add(ChatItem.Live(id(), title, 0, total, "正在准备…", LiveStatus.RUNNING, Mocks.inbox))
    }

    private suspend fun step(n: Int, saw: String, did: String, thumb: Int, tap: Pair<Float, Float>? = null) {
        waitWhilePaused()
        val live = activeLive ?: return
        steps.add(StepRecord(n, saw, did))
        tapPoint = tap
        replace(live.id) { (it as ChatItem.Live).copy(step = n, stepText = did, status = LiveStatus.RUNNING, thumb = thumb) }
        delay(stepMs)
        tapPoint = null
    }

    private fun liveStatus(status: LiveStatus, text: String? = null) {
        val live = activeLive ?: return
        replace(live.id) { (it as ChatItem.Live).copy(status = status, stepText = text ?: it.stepText) }
    }

    private suspend fun gmailFlow() {
        start("给 Alice 发邮件", "写邮件 · Gmail")
        step(1, "看到 Gmail 收件箱", "打开 Gmail", Mocks.inbox, 0.8f to 0.92f)
        step(2, "看到空白的新邮件", "填写收件人 alice@example.com", Mocks.composeEmpty, 0.5f to 0.22f)
        step(3, "看到收件人已填好", "撰写主题和正文", Mocks.composeFilled, 0.5f to 0.40f)
        var confirmId = id()
        val subject = "周五评审改到下午 3 点"
        var body = "Alice 你好，\n\n周五的方案评审改到下午 3 点，地点不变。如果时间不方便请告诉我。\n\n谢谢"
        add(ChatItem.Confirm(confirmId, "alice@example.com", subject, body, ConfirmState.PENDING))
        liveStatus(LiveStatus.WAITING, "等你确认后发送")
        while (true) {
            val w = CompletableDeferred<ConfirmAction>(); confirmWaiter = w
            when (w.await()) {
                ConfirmAction.SEND -> {
                    replace(confirmId) { (it as ChatItem.Confirm).copy(state = ConfirmState.SENDING) }
                    step(4, "确认令牌已生成", "点击发送", Mocks.composeFilled, 0.85f to 0.06f)
                    step(5, "回到收件箱，出现「已发送」提示", "等待撤销时间结束", Mocks.inbox)
                    step(6, "打开「已发送」文件夹", "核对收件人和主题", Mocks.sent, 0.3f to 0.2f)
                    replace(confirmId) { (it as ChatItem.Confirm).copy(state = ConfirmState.SENT) }
                    liveStatus(LiveStatus.DONE, "邮件已发送")
                    add(ChatItem.Success(id(), "邮件已发送给 alice@example.com", "主题：$subject", listOf("已发送文件夹里找到该邮件", "收件人和主题与你确认的一致"), Mocks.sent))
                    return
                }
                ConfirmAction.CANCEL -> {
                    replace(confirmId) { (it as ChatItem.Confirm).copy(state = ConfirmState.CANCELLED) }
                    liveStatus(LiveStatus.STOPPED, "已取消")
                    add(ChatItem.Agent(id(), "已取消，草稿已丢弃。"))
                    return
                }
                ConfirmAction.MODIFY -> {
                    add(ChatItem.Agent(id(), "想怎么改？直接告诉我就行。"))
                    val tw = CompletableDeferred<String>(); textWaiter = tw
                    val req = tw.await()
                    add(ChatItem.Agent(id(), "好的，我来改。"))
                    step(4, "用户要求：$req", "修改草稿", Mocks.composeFilled, 0.5f to 0.40f)
                    body = body.replace("谢谢", "辛苦你了，谢谢")
                    replace(confirmId) { (it as ChatItem.Confirm).copy(state = ConfirmState.SUPERSEDED) }
                    confirmId = id()
                    add(ChatItem.Confirm(confirmId, "alice@example.com", subject, body, ConfirmState.PENDING))
                    liveStatus(LiveStatus.WAITING, "等你确认后发送")
                }
            }
        }
    }

    private suspend fun calendarFlow() {
        start("加日历事项", "日历 · 创建事项")
        step(1, "看到日历日程视图", "打开日历", Mocks.calendar, 0.85f to 0.9f)
        step(2, "看到新建事项页", "点击新建事项", Mocks.calendarEvent, 0.5f to 0.15f)
        val qid = id()
        add(ChatItem.Question(qid, "和 Alice 的评审会议几点开始？", listOf("下午 3 点", "下午 4 点", "其他时间"), null))
        liveStatus(LiveStatus.WAITING, "等你回答")
        val ow = CompletableDeferred<Int>(); optionWaiter = ow
        val picked = ow.await()
        var time = if (picked == 0) "15:00–16:00" else "16:00–17:00"
        if (picked == 2) {
            add(ChatItem.Agent(id(), "请直接告诉我具体时间。"))
            val tw = CompletableDeferred<String>(); textWaiter = tw
            time = tw.await()
        }
        step(3, "新建事项页", "填写标题和时间", Mocks.calendarEvent, 0.5f to 0.30f)
        step(4, "日程视图里出现新事项", "核对标题和时间", Mocks.calendar, 0.5f to 0.30f)
        liveStatus(LiveStatus.DONE, "事项已创建")
        add(ChatItem.Success(id(), "日历里已有「和 Alice 评审方案」", "周五 · $time", listOf("日程视图里找到该事项", "标题和时间与你说的一致"), Mocks.calendar))
    }

    private suspend fun amazonFlow() {
        start("备好购物车", "Amazon · 备购物车")
        step(1, "看到购物车，里面有 1 件商品", "打开购物车", Mocks.cart, 0.7f to 0.92f)
        step(2, "购物车数量是 1", "核对数量", Mocks.cart, 0.4f to 0.45f)
        step(3, "看到「去结算」", "点击去结算", Mocks.cart, 0.5f to 0.25f)
        step(4, "出现会员推广页", "选择「不用了」", Mocks.cart, 0.5f to 0.93f)
        step(5, "到达提交订单页", "核对商品和小计", Mocks.checkout, null)
        liveStatus(LiveStatus.DONE, "已停在提交订单页")
        add(ChatItem.Handoff(id(), "已到提交订单页，商品和小计已核对", "转接器 × 1 · 小计 $7.99。我没有点下单，由你来付款。", false))
    }

    companion object {
        /** 画廊用：直接生成某个界面状态，不跑脚本。 */
        fun preset(kind: String): ChatController {
            val c = ChatController(stepMs = 0)
            val u = "给 Alice 发邮件，说明周五评审改到下午 3 点"
            when (kind) {
                "empty" -> Unit
                "working" -> {
                    c.items += ChatItem.User(1, u); c.items += ChatItem.Agent(2, "好的，我来处理。")
                    c.items += ChatItem.Live(3, "写邮件 · Gmail", 2, 30, "填写收件人 alice@example.com", LiveStatus.RUNNING, Mocks.composeEmpty)
                    c.steps += listOf(StepRecord(1, "看到 Gmail 收件箱", "打开 Gmail"), StepRecord(2, "看到空白的新邮件", "填写收件人 alice@example.com"))
                }
                "confirm" -> {
                    c.items += ChatItem.User(1, u); c.items += ChatItem.Agent(2, "好的，我来处理。")
                    c.items += ChatItem.Live(3, "写邮件 · Gmail", 3, 30, "等你确认后发送", LiveStatus.WAITING, Mocks.composeFilled)
                    c.items += ChatItem.Confirm(4, "alice@example.com", "周五评审改到下午 3 点", "Alice 你好，\n\n周五的方案评审改到下午 3 点，地点不变。如果时间不方便请告诉我。\n\n谢谢", ConfirmState.PENDING)
                }
                "question" -> {
                    c.items += ChatItem.User(1, "周五下午帮我在日历加个和 Alice 的评审会议"); c.items += ChatItem.Agent(2, "好的，我来处理。")
                    c.items += ChatItem.Live(3, "日历 · 创建事项", 2, 30, "等你回答", LiveStatus.WAITING, Mocks.calendarEvent)
                    c.items += ChatItem.Question(4, "和 Alice 的评审会议几点开始？", listOf("下午 3 点", "下午 4 点", "其他时间"), null)
                }
                "success" -> {
                    c.items += ChatItem.User(1, u); c.items += ChatItem.Agent(2, "好的，我来处理。")
                    c.items += ChatItem.Live(3, "写邮件 · Gmail", 6, 30, "邮件已发送", LiveStatus.DONE, Mocks.sent)
                    c.items += ChatItem.Success(4, "邮件已发送给 alice@example.com", "主题：周五评审改到下午 3 点", listOf("已发送文件夹里找到该邮件", "收件人和主题与你确认的一致"), Mocks.sent)
                }
                "failure" -> {
                    c.items += ChatItem.User(1, "周五下午帮我在日历加个和 Alice 的评审会议"); c.items += ChatItem.Agent(2, "好的，我来处理。")
                    c.items += ChatItem.Live(3, "日历 · 创建事项", 9, 30, "已停止", LiveStatus.STOPPED, Mocks.calendarEvent)
                    c.items += ChatItem.Failure(4, "保存事项时日历一直没有响应，连续 3 次失败。", listOf("日历里可能留有一个没有时间的草稿事项"))
                }
                "handoff" -> {
                    c.items += ChatItem.User(1, "把亚马逊购物车里的东西带到下单页，我自己付款"); c.items += ChatItem.Agent(2, "好的，我来处理。")
                    c.items += ChatItem.Live(3, "Amazon · 备购物车", 5, 30, "已停在提交订单页", LiveStatus.DONE, Mocks.checkout)
                    c.items += ChatItem.Handoff(4, "已到提交订单页，商品和小计已核对", "转接器 × 1 · 小计 $7.99。我没有点下单，由你来付款。", false)
                }
            }
            return c
        }
    }
}

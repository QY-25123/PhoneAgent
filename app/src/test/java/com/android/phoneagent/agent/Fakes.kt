package com.android.phoneagent.agent

import com.android.phoneagent.perception.Bounds
import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.perception.PerceptionTimings
import com.android.phoneagent.perception.ScreenState
import com.android.phoneagent.perception.UiElement
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/** 固定时钟：2026-10-23 周五 13:40（洛杉矶）。 */
val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 23, 13, 40, 0, 0, ZoneId.of("America/Los_Angeles"))

/** 依次返回预设回复；用完后一直返回最后一个。记录每次请求。回复是 JSON 文本或要抛出的异常。 */
class FakeModel(private val replies: List<Any>) : ModelClient {
    val requests = ArrayList<ModelRequest>()
    override suspend fun complete(req: ModelRequest): ModelResponse {
        requests += req
        val r = replies[minOf(requests.size - 1, replies.size - 1)]
        if (r is ModelException) throw r
        return ModelResponse(r as String, ModelUsage(1000, 100), 5)
    }
}

fun clarifyJson(
    type: String = "calendar", title: String? = null, date: String? = null, start: String? = null, duration: Int? = null,
    recipient: String? = null, content: String? = null, subject: String? = null, body: String? = null,
    product: String? = null, useCart: Boolean? = null, quantity: Int? = null, cartRequest: String? = null,
    missing: List<String> = emptyList(), question: String? = null, options: List<String> = emptyList(),
    mappedFrom: String? = null, reply: String? = null,
): String = JSONObject().apply {
    put("task_type", type); put("title", title ?: JSONObject.NULL); put("date", date ?: JSONObject.NULL)
    put("start_time", start ?: JSONObject.NULL); put("duration_minutes", duration ?: JSONObject.NULL)
    put("recipient", recipient ?: JSONObject.NULL); put("email_content", content ?: JSONObject.NULL)
    put("email_subject", subject ?: JSONObject.NULL); put("email_body", body ?: JSONObject.NULL)
    put("product", product ?: JSONObject.NULL); put("use_cart", useCart ?: JSONObject.NULL); put("quantity", quantity ?: JSONObject.NULL); put("cart_request", cartRequest ?: JSONObject.NULL)
    put("missing", JSONArray(missing)); put("assumed", JSONArray()); put("question", question ?: JSONObject.NULL)
    put("options", JSONArray(options)); put("mapped_from", mappedFrom ?: JSONObject.NULL); put("reply", reply ?: JSONObject.NULL)
}.toString()

fun stepJson(
    type: String, index: Int? = null, text: String? = null, app: String? = null, direction: String? = null, seconds: Int? = null,
    question: String? = null, options: List<String> = emptyList(), success: Boolean? = null, result: String? = null,
    understanding: String = "页面理解", reason: String = "理由",
): String = JSONObject().apply {
    put("understanding", understanding); put("reason", reason)
    put("action", JSONObject().apply {
        put("type", type); put("app", app ?: JSONObject.NULL); put("index", index ?: JSONObject.NULL); put("text", text ?: JSONObject.NULL)
        put("direction", direction ?: JSONObject.NULL); put("seconds", seconds ?: JSONObject.NULL); put("question", question ?: JSONObject.NULL)
        put("options", JSONArray(options)); put("success", success ?: JSONObject.NULL); put("result", result ?: JSONObject.NULL)
    })
}.toString()

fun verifyJson(verified: Boolean, problem: String? = null) =
    JSONObject().put("verified", verified).put("evidence", "界面上看到该事项").put("problem", problem ?: JSONObject.NULL).toString()

/** 合成一个界面：每个标签一个按钮。[tag] 不同则界面签名不同。 */
fun screenOf(tag: String, vararg labels: String, jpeg: ByteArray? = byteArrayOf(1, 2, 3)): PerceptionResult {
    val els = (listOf(tag) + labels).mapIndexed { i, l ->
        UiElement(i + 1, "id$i$l".take(4).padEnd(4, 'x'), "Button", l, null, null, null, null, "android.widget.Button",
            Bounds(0, i * 100, 500, i * 100 + 80), setOf(ElementAction.CLICK), false, null, true)
    }
    val st = ScreenState(2, "com.google.android.calendar", tag, 1080, 2400, 0, els, screenshotJpeg = jpeg)
    return PerceptionResult(st, "App: calendar  标题: $tag\n" + els.joinToString("\n") { "[${it.index}] Button \"${it.text}\"" }, PerceptionTimings(0, 1, 1, 1, 1, 1))
}

class FakePhone(
    private val screens: (Int) -> PerceptionResult,
    var insert: ActionOutcome = ActionOutcome(true, "ok"),
    private val outcome: (Int, Action) -> ActionOutcome = { _, _ -> ActionOutcome(true, "ok") },
) : PhoneController {
    val executed = ArrayList<Action>()
    var perceives = 0
    var insertedTask: CalendarTask? = null
    override suspend fun launchCalendarInsert(task: CalendarTask): ActionOutcome { insertedTask = task; return insert }
    var emailTask: EmailTask? = null
    var emailLaunch: ActionOutcome = ActionOutcome(true, "ok")
    override suspend fun launchEmailCompose(task: EmailTask): ActionOutcome { emailTask = task; return emailLaunch }
    val amazonUrls = ArrayList<String>()
    override suspend fun launchAmazon(url: String): ActionOutcome { amazonUrls += url; return ActionOutcome(true, "ok") }
    val opened = ArrayList<String>()
    override suspend fun openApp(app: String): ActionOutcome { opened += app; return ActionOutcome(true, "ok") }
    var armResult: (String) -> Boolean = { true }
    val armed = ArrayList<String>()
    var disarms = 0
    override suspend fun armGmailSend(hash: String): Boolean { armed += hash; return armResult(hash) }
    override suspend fun disarmGmailSend() { disarms++ }
    val cartArmed = ArrayList<List<CartChange>>()
    var cartDisarms = 0
    override suspend fun armCartChanges(changes: List<CartChange>) { cartArmed += changes }
    override suspend fun disarmCartChanges() { cartDisarms++ }
    override suspend fun perceive(): PerceptionResult = screens(perceives++)
    override suspend fun execute(action: Action, screen: ScreenState): ActionOutcome {
        executed += action
        return outcome(executed.size, action)
    }
}

class FakeHost(private val answers: List<String> = emptyList()) : LoopHost {
    val steps = ArrayList<StepInfo>()
    val asked = ArrayList<Pair<String, List<String>>>()
    override fun onStep(info: StepInfo) { steps += info }
    override suspend fun askUser(question: String, options: List<String>): String {
        asked += question to options
        return answers.getOrElse(asked.size - 1) { "好" }
    }
}

val TASK = CalendarTask("[PA测试] 评审会", java.time.LocalDate.of(2026, 10, 27), java.time.LocalTime.of(15, 0), 60, listOf("时长按 1 小时算"))

/** 合成一个已预填好的日历新建页：标题框 + 保存 + 日期/开始/结束时间。 */
fun editorScreen(title: String = TASK.title, start: String = "3:00 PM", end: String = "4:00 PM", date: String = "Tue, Oct 27, 2026"): PerceptionResult {
    fun el(i: Int, type: String, text: String?, desc: String?, actions: Set<ElementAction>) =
        UiElement(i, "e$i".padEnd(4, 'x'), type, text, desc, null, null, null, "android.widget.Button", Bounds(0, i * 100, 500, i * 100 + 80), actions, false, null, true)
    val els = listOf(
        el(1, "Button", "Cancel", null, setOf(ElementAction.CLICK)),
        el(2, "Button", "Save", null, setOf(ElementAction.CLICK)),
        el(3, "EditText", title, null, setOf(ElementAction.EDIT, ElementAction.CLICK)),
        el(4, "Button", date, "Start date: $date", setOf(ElementAction.CLICK)),
        el(5, "Button", start, "Start time: $start", setOf(ElementAction.CLICK)),
        el(6, "Button", end, "End time: $end", setOf(ElementAction.CLICK)),
    )
    val st = ScreenState(2, "com.google.android.calendar", "Calendar", 1080, 2400, 0, els, screenshotJpeg = byteArrayOf(1))
    return PerceptionResult(st, "App: calendar 新建页", PerceptionTimings(0, 1, 1, 1, 1, 1))
}

private fun ui(i: Int, type: String, top: Int, text: String? = null, desc: String? = null, hint: String? = null, actions: Set<ElementAction> = emptySet(), res: String? = null) =
    UiElement(i, "g$i".padEnd(4, 'x'), type, text, desc, hint, null, res, "android.widget.Button", Bounds(0, top, 1000, top + 100), actions, false, null, true)

/** 合成 Gmail 写邮件页（结构取自真机录制：From 行有发件账号，To 行有收件人芯片，主题/正文是输入框）。 */
fun gmailScreen(to: String = "qijie_jerry@163.com", subject: String = "[PA测试] 周五评审", body: String = "你好，周五评审改到下午3点。谢谢", pkg: String = "com.google.android.gm"): PerceptionResult {
    val click = setOf(ElementAction.CLICK)
    val edit = setOf(ElementAction.EDIT)
    val els = listOf(
        ui(1, "ImageButton", 0, desc = "Navigate up", actions = click),
        ui(2, "Button", 0, desc = "Send", actions = click, res = "com.google.android.gm:id/send"),
        ui(3, "Button", 300, text = "From", actions = click),
        ui(4, "Text", 300, text = "test.user@example.com"),
        ui(5, "Button", 400, text = "To", actions = click),
        ui(6, "Button", 400, text = to, desc = "$to, $to", actions = click),
        ui(7, "EditText", 465, text = subject.ifEmpty { null }, hint = "Subject", actions = edit),
        ui(8, "EditText", 618, text = body.ifEmpty { "Compose email" }, actions = edit),
    )
    val st = ScreenState(2, pkg, "Gmail", 1080, 2400, 0, els, screenshotJpeg = byteArrayOf(1))
    return PerceptionResult(st, "App: gmail 写邮件页", PerceptionTimings(0, 1, 1, 1, 1, 1))
}

fun inboxScreen(vararg labels: String) = screenOf("收件箱", *labels)

/** 合成 Amazon 购物车页：[n] 件商品，每件有 Delete；底部「Proceed to checkout」。 */
fun cartScreen(n: Int = 1): PerceptionResult {
    val click = setOf(ElementAction.CLICK)
    var i = 0
    fun el(type: String, text: String?, desc: String? = null, a: Set<ElementAction> = emptySet()): UiElement { i++; return UiElement(i, "c$i".padEnd(4, 'x'), type, text, desc, null, null, null, "android.widget.Button", Bounds(0, i * 100, 900, i * 100 + 80), a, false, null, true) }
    val els = ArrayList<UiElement>()
    els += el("Text", "Subtotal ($n ${if (n == 1) "item" else "items"}): \$7.99")
    els += el("Button", "Proceed to checkout", null, click)
    for (k in 1..n) { els += el("Button", null, "Delete 商品$k 很长的名字", click); els += el("Button", null, "Increase quantity by one, Quantity is 1, 商品$k", click) }
    return PerceptionResult(ScreenState(2, AppCatalog.AMAZON, "Amazon", 1080, 2400, 0, els, screenshotJpeg = byteArrayOf(1)), "App: amazon 购物车", PerceptionTimings(0, 1, 1, 1, 1, 1))
}

fun orderScreen(): PerceptionResult {
    var i = 0
    fun el(type: String, text: String, a: Set<ElementAction> = emptySet()): UiElement { i++; return UiElement(i, "o$i".padEnd(4, 'x'), type, text, null, null, null, null, "android.widget.Button", Bounds(0, i * 100, 900, i * 100 + 80), a, false, null, true) }
    val els = listOf(el("Button", "Place your order", setOf(ElementAction.CLICK)), el("Text", "Items:"), el("Text", "\$7.99"), el("Text", "Order total:"), el("Text", "\$16.44"))
    return PerceptionResult(ScreenState(2, AppCatalog.AMAZON, "Amazon", 1080, 2400, 0, els, screenshotJpeg = byteArrayOf(1)), "App: amazon 提交订单页", PerceptionTimings(0, 1, 1, 1, 1, 1))
}

fun cartPlanJson(vararg changes: Triple<String, String, Int?>, question: String? = null) =
    JSONObject().put("changes", JSONArray(changes.map { JSONObject().put("item", it.first).put("action", it.second).put("quantity", it.third ?: JSONObject.NULL) }))
        .put("question", question ?: JSONObject.NULL).toString()

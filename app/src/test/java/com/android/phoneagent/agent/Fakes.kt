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
    missing: List<String> = emptyList(), question: String? = null, options: List<String> = emptyList(),
    mappedFrom: String? = null, reply: String? = null,
): String = JSONObject().apply {
    put("task_type", type); put("title", title ?: JSONObject.NULL); put("date", date ?: JSONObject.NULL)
    put("start_time", start ?: JSONObject.NULL); put("duration_minutes", duration ?: JSONObject.NULL)
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

package com.android.phoneagent.agent

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 澄清阶段的对话记录：用户说的话和助手的追问。 */
class DialogTurn(val fromUser: Boolean, val text: String)

/** 日历任务：信息齐全后的结构化结果（时间是手机本地时区的挂钟时间）。 */
data class CalendarTask(
    val title: String,
    val date: LocalDate,
    val start: LocalTime,
    val durationMinutes: Int,
    /** 用了哪些默认值（例如「时长按 1 小时算」） */
    val assumed: List<String>,
) {
    val end: LocalTime get() = start.plusMinutes(durationMinutes.toLong())
    fun beginMillis(zone: ZoneId) = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
    fun endMillis(zone: ZoneId) = date.atTime(start).plusMinutes(durationMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()

    /** 一句话复述：要做什么、用了哪些默认值（PRD FR-CLR-5）。 */
    fun restatement(mappedFromReminder: Boolean = false): String {
        val d = "${date.monthValue}月${date.dayOfMonth}日（${weekdayZh(date.dayOfWeek)}）"
        val t = "${fmt(start)}–${fmt(end)}"
        val defaults = if (assumed.isEmpty()) "" else "，" + assumed.joinToString("，")
        val lead = if (mappedFromReminder) "好，提醒我会做成日历事项，到时会通知你：「$title」" else "好，我在日历里加「$title」"
        return "$lead：$d $t$defaults。现在去办。"
    }

    /** 给执行循环的「已确认信息」。 */
    fun confirmedFields(): String =
        "标题「$title」；日期 $date（${weekdayZh(date.dayOfWeek)}）；开始 ${fmt(start)}；结束 ${fmt(end)}（时长 $durationMinutes 分钟" +
            (if (assumed.any { "时长" in it }) "，默认值" else "") + "）"

    private fun fmt(t: LocalTime) = t.format(DateTimeFormatter.ofPattern("HH:mm"))
}

fun weekdayZh(d: DayOfWeek) = when (d) {
    DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"; DayOfWeek.WEDNESDAY -> "周三"; DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"; DayOfWeek.SUNDAY -> "周日"
}

sealed interface ClarifyOutcome {
    /** 缺必填项（或日期已过去）：在对话里追问，能给选项就给选项按钮。 */
    data class Ask(val question: String, val options: List<String>, val mappedFrom: String? = null) : ClarifyOutcome

    /** 信息齐全：复述一句话，然后立即开始（不再二次确认）。 */
    data class Ready(val task: CalendarTask, val restatement: String) : ClarifyOutcome

    /** 邮件任务信息齐全：复述一句话，然后开始写草稿（发送前一定会让用户确认）。 */
    data class ReadyEmail(val task: EmailTask, val restatement: String) : ClarifyOutcome

    /** 不开始任务，只回复（不支持的任务、闲聊、还没做的功能）。 */
    data class Reply(val text: String) : ClarifyOutcome

    data class Failed(val reason: String) : ClarifyOutcome
}

/**
 * 任务澄清（PRD 6.9）：收到用户的话之后、碰手机之前，用 Claude 判断任务类型并抽取信息（结构化输出），
 * 代码只根据结构化结果决定下一步：追问 / 复述并开始 / 说明不支持。澄清阶段不读手机屏幕。
 * 必填项是否齐全由代码再校验一遍，不完全信任模型的 missing。
 */
class Clarifier(
    private val model: ModelClient,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    /** 目前能执行的任务类型（M2：只有日历）。识别出来但不能执行的会说明还在开发中 */
    private val enabledKinds: Set<String> = setOf("calendar", "email"),
    private val log: RunLog = NoopRunLog,
) {
    suspend fun clarify(dialog: List<DialogTurn>): ClarifyOutcome {
        val now = clock()
        val req = ModelRequest(
            label = "clarify",
            system = Prompts.clarifySystem(now, enabledKinds),
            messages = listOf(ModelMessage("user", Prompts.transcript(dialog))),
            schemaJson = Schemas.CLARIFY,
            maxTokens = 1000,
            effort = "low",
            model = Models.clarify,
        )
        // 模型输出不合法（不是 JSON、task_type 不认识）时自动重试一次
        var lastFailure: ClarifyOutcome = ClarifyOutcome.Failed("没能理解模型的回复，请换个说法再试一次")
        repeat(2) {
            val resp = try {
                model.complete(req).also { log.modelCall(req, it, null) }
            } catch (e: ModelException) {
                log.modelCall(req, null, e.message)
                return ClarifyOutcome.Failed(e.message ?: "调用模型失败")
            }
            val o = try { JSONObject(resp.json) } catch (e: Exception) { null }
            if (o != null) {
                val out = decide(o, now)
                if (!(out is ClarifyOutcome.Failed)) return out
                lastFailure = out
            }
        }
        return lastFailure
    }

    internal fun decide(o: JSONObject, now: ZonedDateTime): ClarifyOutcome {
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).trim().takeIf { it.isNotEmpty() }
        val options = o.optJSONArray("options")?.let { a -> List(a.length()) { a.getString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        val mappedFrom = str("mapped_from")
        val kind = str("task_type")
        when (kind) {
            "unsupported" -> return ClarifyOutcome.Reply(str("reply") ?: Prompts.capabilityText(enabledKinds))
            "chitchat" -> return ClarifyOutcome.Reply(str("reply") ?: Prompts.capabilityText(enabledKinds))
            "email" -> if (kind in enabledKinds) return decideEmail(o, mappedFrom, options) else return ClarifyOutcome.Reply(Prompts.notYetText(kind, enabledKinds))
            "shopping" ->
                if (kind !in enabledKinds) return ClarifyOutcome.Reply(Prompts.notYetText(kind!!, enabledKinds))
            "calendar" -> Unit
            else -> return ClarifyOutcome.Failed("没能理解模型的回复，请换个说法再试一次")
        }
        if (kind != "calendar") return ClarifyOutcome.Reply(Prompts.notYetText(kind!!, enabledKinds))

        // —— 日历：必填项由代码校验 ——
        val missing = ArrayList<String>()
        val title = str("title")?.also { }
        if (title == null) missing += "标题"
        val date = str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (date == null) missing += "日期"
        val start = str("start_time")?.let { parseTime(it) }
        if (start == null) missing += "开始时间"

        if (missing.isEmpty() && date!!.isBefore(now.toLocalDate())) {
            return ClarifyOutcome.Ask("${date.monthValue}月${date.dayOfMonth}日已经过去了，要改成哪一天？", emptyList(), mappedFrom)
        }
        if (missing.isNotEmpty()) {
            val q = str("question") ?: ("还需要这几项信息：" + missing.joinToString("、") + "。")
            return ClarifyOutcome.Ask(q, options, mappedFrom)
        }
        val dur = if (o.isNull("duration_minutes")) null else o.optInt("duration_minutes").takeIf { it in 5..720 }
        val assumed = if (dur == null) listOf("时长按 1 小时算") else emptyList()
        val task = CalendarTask(title!!, date!!, start!!, dur ?: DEFAULT_DURATION_MIN, assumed)
        return ClarifyOutcome.Ready(task, task.restatement(mappedFrom == "提醒"))
    }

    /** 邮件：收件人和「邮件要表达的内容」必填；主题和正文由模型写好（只用用户给的信息）。 */
    private fun decideEmail(o: JSONObject, mappedFrom: String?, options: List<String>): ClarifyOutcome {
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).trim().takeIf { it.isNotEmpty() }
        val recipient = str("recipient")
        val content = str("email_content")
        val subject = str("email_subject")
        val body = str("email_body")
        val missing = ArrayList<String>()
        if (recipient == null) missing += "收件人"
        if (content == null && body == null) missing += "邮件内容"
        if (missing.isNotEmpty()) {
            val q = str("question") ?: ("还需要这几项信息：" + missing.joinToString("、") + "。")
            return ClarifyOutcome.Ask(q, options, mappedFrom)
        }
        val finalBody = body ?: content!!
        val finalSubject = subject ?: finalBody.lineSequence().first().take(30)
        val task = EmailTask(recipient!!, finalSubject, finalBody)
        return ClarifyOutcome.ReadyEmail(task, task.restatement())
    }

    private fun parseTime(s: String): LocalTime? = runCatching { LocalTime.parse(s.trim().let { if (Regex("\\d:\\d\\d").matches(it)) "0$it" else it }) }.getOrNull()

    companion object {
        const val DEFAULT_DURATION_MIN = 60
    }
}

package com.android.phoneagent.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ClarifierTest {
    private fun clarifier(vararg replies: Any, enabled: Set<String> = setOf("calendar")) =
        Clarifier(FakeModel(replies.toList()), clock = { NOW }, enabledKinds = enabled)

    private fun user(t: String) = listOf(DialogTurn(true, t))

    @Test fun completeInfoIsReadyWithDefaultDurationAndRestatement() = runTest {
        val c = clarifier(clarifyJson(title = "[PA测试] 评审会", date = "2026-10-28", start = "15:00"))
        val r = c.clarify(user("周三下午3点加日历 [PA测试] 评审会")) as ClarifyOutcome.Ready
        assertThat(r.task.title).isEqualTo("[PA测试] 评审会")
        assertThat(r.task.date).isEqualTo(LocalDate.of(2026, 10, 28))
        assertThat(r.task.start).isEqualTo(LocalTime.of(15, 0))
        assertThat(r.task.durationMinutes).isEqualTo(60)
        assertThat(r.task.assumed).containsExactly("时长按 1 小时算")
        assertThat(r.restatement).isEqualTo("好，我在日历里加「[PA测试] 评审会」：10月28日（周三） 15:00–16:00，时长按 1 小时算。现在去办。")
    }

    @Test fun explicitDurationIsUsedAndNotListedAsDefault() = runTest {
        val c = clarifier(clarifyJson(title = "会", date = "2026-10-28", start = "09:30", duration = 90))
        val r = c.clarify(user("x")) as ClarifyOutcome.Ready
        assertThat(r.task.durationMinutes).isEqualTo(90)
        assertThat(r.task.assumed).isEmpty()
        assertThat(r.restatement).contains("09:30–11:00")
        assertThat(r.restatement).doesNotContain("默认")
    }

    @Test fun missingFieldsAreAskedTogetherWithoutOptionsWhenFreeTextNeeded() = runTest {
        val c = clarifier(clarifyJson(missing = listOf("标题", "日期", "开始时间"), question = "标题、哪天、几点开始？"))
        val r = c.clarify(user("帮我加个日历事项")) as ClarifyOutcome.Ask
        assertThat(r.question).isEqualTo("标题、哪天、几点开始？")
        assertThat(r.options).isEmpty()
    }

    @Test fun vagueTimeIsAskedWithOptions() = runTest {
        val c = clarifier(clarifyJson(title = "开会", date = "2026-10-26", start = null, missing = listOf("开始时间"),
            question = "几点开始？", options = listOf("上午 9 点", "下午 3 点")))
        val r = c.clarify(user("下周一下午开会")) as ClarifyOutcome.Ask
        assertThat(r.options).containsExactly("上午 9 点", "下午 3 点").inOrder()
    }

    @Test fun vagueDateIsNotAcceptedEvenIfModelClaimsNothingMissing() = runTest {
        // 模型把含糊日期漏了（date 为 null 但 missing 为空）：代码自己校验，仍然追问
        val c = clarifier(clarifyJson(title = "开会", date = null, start = "15:00", missing = emptyList(), question = null))
        val r = c.clarify(user("下周开会，下午3点")) as ClarifyOutcome.Ask
        assertThat(r.question).contains("日期")
    }

    @Test fun malformedDateOrTimeCountsAsMissing() = runTest {
        val r1 = clarifier(clarifyJson(title = "a", date = "下周", start = "15:00")).clarify(user("x"))
        assertThat(r1).isInstanceOf(ClarifyOutcome.Ask::class.java)
        val r2 = clarifier(clarifyJson(title = "a", date = "2026-10-28", start = "下午")).clarify(user("x"))
        assertThat(r2).isInstanceOf(ClarifyOutcome.Ask::class.java)
    }

    @Test fun singleDigitHourIsAccepted() = runTest {
        val r = clarifier(clarifyJson(title = "a", date = "2026-10-28", start = "9:05")).clarify(user("x")) as ClarifyOutcome.Ready
        assertThat(r.task.start).isEqualTo(LocalTime.of(9, 5))
    }

    @Test fun reminderIsMappedToCalendarAndAsksWithExplanation() = runTest {
        val q = "要提醒你什么事，什么时候提醒？我会在日历里加一个事项，到时会通知你"
        val c = clarifier(clarifyJson(missing = listOf("标题", "日期", "开始时间"), question = q, mappedFrom = "提醒"))
        val r = c.clarify(user("设置一个提醒事项")) as ClarifyOutcome.Ask
        assertThat(r.mappedFrom).isEqualTo("提醒")
        assertThat(r.question).isEqualTo(q)
    }

    @Test fun reminderThatIsCompleteIsRestatedAsACalendarEvent() = runTest {
        val c = clarifier(clarifyJson(title = "交报告", date = "2026-10-30", start = "14:00", mappedFrom = "提醒"))
        val r = c.clarify(user("下周五下午2点提醒我交报告")) as ClarifyOutcome.Ready
        assertThat(r.restatement).startsWith("好，提醒我会做成日历事项，到时会通知你：「交报告」")
        assertThat(r.restatement).contains("10月30日（周五） 14:00–15:00")
    }

    @Test fun unsupportedTaskRepliesWithoutStarting() = runTest {
        val c = clarifier(clarifyJson(type = "unsupported", reply = "这个我做不了。现在能帮你在日历里加事项。"))
        val r = c.clarify(user("帮我订个外卖")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("日历")
    }

    @Test fun unsupportedWithoutModelReplyFallsBackToCapabilityText() = runTest {
        val r = clarifier(clarifyJson(type = "unsupported")).clarify(user("转账")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("现在能帮你")
        assertThat(r.text).contains("日历")
    }

    @Test fun chitchatReplies() = runTest {
        val r = clarifier(clarifyJson(type = "chitchat", reply = "你好！我可以帮你在日历里加事项。")).clarify(user("你能做什么")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("日历")
    }

    @Test fun recognizedButNotYetEnabledKindsSayTheyAreInDevelopment() = runTest {
        val r = clarifier(clarifyJson(type = "email")).clarify(user("给 Alice 发邮件")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("还在开发中")
        assertThat(r.text).contains("日历")
        val s = clarifier(clarifyJson(type = "shopping")).clarify(user("购物车")) as ClarifyOutcome.Reply
        assertThat(s.text).contains("还在开发中")
    }

    @Test fun pastDateIsAskedAgain() = runTest {
        val r = clarifier(clarifyJson(title = "a", date = "2026-10-20", start = "15:00")).clarify(user("x")) as ClarifyOutcome.Ask
        assertThat(r.question).contains("已经过去")
    }

    @Test fun todayIsNotPast() = runTest {
        assertThat(clarifier(clarifyJson(title = "a", date = "2026-10-23", start = "15:00")).clarify(user("x"))).isInstanceOf(ClarifyOutcome.Ready::class.java)
    }

    @Test fun modelErrorAndBadJsonBecomeFailed() = runTest {
        assertThat(clarifier(ModelException("网络不通")).clarify(user("x"))).isEqualTo(ClarifyOutcome.Failed("网络不通"))
        assertThat(clarifier("不是 json").clarify(user("x"))).isInstanceOf(ClarifyOutcome.Failed::class.java)
        assertThat(clarifier(clarifyJson(type = "weird")).clarify(user("x"))).isInstanceOf(ClarifyOutcome.Failed::class.java)
    }

    @Test fun requestCarriesWholeDialogCurrentTimeAndSchema() = runTest {
        val model = FakeModel(listOf(clarifyJson(title = "a", date = "2026-10-28", start = "15:00")))
        Clarifier(model, clock = { NOW }).clarify(listOf(DialogTurn(true, "帮我加个日历事项"), DialogTurn(false, "几点？"), DialogTurn(true, "周三下午3点，评审")))
        val req = model.requests.single()
        assertThat(req.label).isEqualTo("clarify")
        assertThat(req.system).contains("2026-10-23")
        assertThat(req.system).contains("周五")
        assertThat(req.system).contains("America/Los_Angeles")
        val text = req.messages.single().text
        assertThat(text).contains("用户：帮我加个日历事项")
        assertThat(text).contains("助手（追问）：几点？")
        assertThat(text).contains("用户：周三下午3点，评审")
        assertThat(req.schemaJson).contains("task_type")
        assertThat(req.messages.single().jpegImages).isEmpty()   // 澄清阶段不读手机屏幕
    }

    @Test fun calendarTaskMillisUseTheGivenZone() {
        val z = java.time.ZoneId.of("America/Los_Angeles")
        val t = CalendarTask("a", LocalDate.of(2026, 10, 28), LocalTime.of(15, 0), 60, emptyList())
        assertThat(t.endMillis(z) - t.beginMillis(z)).isEqualTo(3_600_000L)
        assertThat(t.beginMillis(z)).isEqualTo(java.time.ZonedDateTime.of(2026, 10, 28, 15, 0, 0, 0, z).toInstant().toEpochMilli())
    }
}

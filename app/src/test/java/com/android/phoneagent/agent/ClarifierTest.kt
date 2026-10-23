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
        assertThat(r.restatement).isEqualTo("日历：「[PA测试] 评审会」· 10月28日（周三）15:00–16:00（默认 1 小时）")
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
        assertThat(r.restatement).startsWith("日历提醒：「交报告」")
        assertThat(r.restatement).contains("10月30日（周五）14:00–15:00")
    }

    @Test fun unsupportedTaskRepliesWithoutStarting() = runTest {
        val c = clarifier(clarifyJson(type = "unsupported", reply = "外卖这个我暂时帮不上忙。有什么别的可以帮到您的吗？祝您有愉快的一天！"))
        val r = c.clarify(user("帮我订个外卖")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("可以帮到您")
    }

    @Test fun unsupportedWithoutModelReplyFallsBackToCapabilityText() = runTest {
        val r = clarifier(clarifyJson(type = "unsupported")).clarify(user("转账")) as ClarifyOutcome.Reply
        // 兜底回复：笼统、友好、带问候，不罗列功能
        assertThat(r.text).contains("有什么可以帮到您")
        assertThat(r.text).contains("愉快")
        assertThat(r.text).doesNotContain("日历")
    }

    @Test fun chitchatReplies() = runTest {
        val r = clarifier(clarifyJson(type = "chitchat", reply = "您好！有什么可以帮到您的吗？祝您今天心情愉快！")).clarify(user("你好")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("有什么可以帮到您")
    }

    @Test fun recognizedButNotYetEnabledKindsSayTheyAreInDevelopment() = runTest {
        val r = clarifier(clarifyJson(type = "email")).clarify(user("给 Alice 发邮件")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("可以帮到您")
        val s = clarifier(clarifyJson(type = "shopping")).clarify(user("购物车")) as ClarifyOutcome.Reply
        assertThat(s.text).contains("可以帮到您")
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

    @Test fun invalidOutputIsRetriedOnceThenAccepted() = runTest {
        val model = FakeModel(listOf("不是 json", clarifyJson(type = "weird"), clarifyJson(title = "a", date = "2026-10-28", start = "15:00")))
        val c = Clarifier(model, clock = { NOW })
        // 第一次不是 JSON → 重试一次，第二次 task_type 不认识 → 失败（只重试一次）
        assertThat(c.clarify(user("x"))).isInstanceOf(ClarifyOutcome.Failed::class.java)
        assertThat(model.requests).hasSize(2)
        // 新的一轮：合法
        assertThat(c.clarify(user("x"))).isInstanceOf(ClarifyOutcome.Ready::class.java)
    }

    @Test fun invalidThenValidSucceedsWithinOneClarify() = runTest {
        val model = FakeModel(listOf("不是 json", clarifyJson(title = "a", date = "2026-10-28", start = "15:00")))
        assertThat(Clarifier(model, clock = { NOW }).clarify(user("x"))).isInstanceOf(ClarifyOutcome.Ready::class.java)
        assertThat(model.requests).hasSize(2)
    }

    @Test fun systemPromptHasDateTableAndExamples() = runTest {
        val model = FakeModel(listOf(clarifyJson(title = "a", date = "2026-10-28", start = "15:00")))
        Clarifier(model, clock = { NOW }).clarify(user("x"))
        val sys = model.requests.single().system
        assertThat(sys).contains("今天：2026-10-23 周五")
        assertThat(sys).contains("明天：2026-10-24 周六")
        assertThat(sys).contains("后天：2026-10-25 周日")
        assertThat(sys).contains("下周（周一到周日）".replace("（周一到周日）", "")+"：周一 2026-10-26")
        assertThat(sys).contains("本周（周一到周日）：周一 2026-10-19")
        assertThat(sys).contains("周五 2026-10-30")          // 今天之后最近的周五
        assertThat(sys).contains("不要追问用户是哪一天")
        assertThat(Regex("^\\d\\. ", RegexOption.MULTILINE).findAll(sys.substringAfter("示例")).count()).isAtLeast(5)
    }

    @Test fun dateTableUsesMondayAsWeekStart() {
        val t = Prompts.dateTable(NOW)
        assertThat(t).contains("再下周：周一 2026-11-02")
        assertThat(t).contains("周日 2026-10-25")   // 本周的周日在今天之后
    }

    // ---- 邮件（T2）----
    private fun emailClarifier(vararg replies: Any) = Clarifier(FakeModel(replies.toList()), clock = { NOW })

    @Test fun emailWithRecipientAndContentIsReady() = runTest {
        val c = emailClarifier(clarifyJson(type = "email", recipient = "qijie_jerry@163.com", content = "周五评审改到下午3点", subject = "[PA测试] 周五评审改期", body = "你好，周五评审改到下午3点。谢谢"))
        val r = c.clarify(user("给 qijie_jerry@163.com 发邮件")) as ClarifyOutcome.ReadyEmail
        assertThat(r.task).isEqualTo(EmailTask("qijie_jerry@163.com", "[PA测试] 周五评审改期", "你好，周五评审改到下午3点。谢谢"))
        assertThat(r.restatement).isEqualTo("邮件：发给 qijie_jerry@163.com，主题「[PA测试] 周五评审改期」")
    }

    @Test fun emailMissingRecipientAndContentAsksOnceForBoth() = runTest {
        val c = emailClarifier(clarifyJson(type = "email", missing = listOf("收件人", "邮件内容"), question = "发给谁？想说什么？"))
        val r = c.clarify(user("帮我写封邮件")) as ClarifyOutcome.Ask
        assertThat(r.question).isEqualTo("发给谁？想说什么？")
    }

    @Test fun emailMissingContentIsCaughtByCodeEvenIfModelForgot() = runTest {
        val r = emailClarifier(clarifyJson(type = "email", recipient = "Alice", missing = emptyList())).clarify(user("给 Alice 写封邮件")) as ClarifyOutcome.Ask
        assertThat(r.question).contains("邮件内容")
    }

    @Test fun emailMissingRecipientIsCaughtByCode() = runTest {
        val r = emailClarifier(clarifyJson(type = "email", content = "明天开会", subject = "明天开会", body = "明天开会")).clarify(user("发邮件说明天开会")) as ClarifyOutcome.Ask
        assertThat(r.question).contains("收件人")
    }

    @Test fun nameOnlyRecipientIsAcceptedForLookup() = runTest {
        val r = emailClarifier(clarifyJson(type = "email", recipient = "Alice", content = "改期", subject = "改期", body = "改期了")).clarify(user("x")) as ClarifyOutcome.ReadyEmail
        assertThat(r.task.recipient).isEqualTo("Alice")
        assertThat(r.task.hasAddress).isFalse()
    }

    @Test fun emailSubjectFallsBackToFirstLineOfBody() = runTest {
        val r = emailClarifier(clarifyJson(type = "email", recipient = "a@b.com", content = "x", body = "明天十点开会\n请准时")).clarify(user("x")) as ClarifyOutcome.ReadyEmail
        assertThat(r.task.subject).isEqualTo("明天十点开会")
    }

    @Test fun emailKindIsRepliedAsInDevelopmentWhenNotEnabled() = runTest {
        val r = clarifier(clarifyJson(type = "email")).clarify(user("发邮件")) as ClarifyOutcome.Reply
        assertThat(r.text).contains("可以帮到您")
    }

    @Test fun emailPromptIsInTheSystemPromptWithExamples() = runTest {
        val model = FakeModel(listOf(clarifyJson(type = "email", recipient = "a@b.com", content = "x", subject = "s", body = "b")))
        Clarifier(model, clock = { NOW }).clarify(user("x"))
        val sys = model.requests.single().system
        assertThat(sys).contains("email_subject")
        assertThat(sys).contains("不编造")
        assertThat(sys).contains("缺内容")
    }

    @Test fun emailTaskMatchesDraftIgnoringWhitespaceNoise() {
        val t = EmailTask("Qijie_Jerry@163.com", "[PA测试] 周五评审", "你好，\n周五评审改到下午3点。")
        assertThat(t.matches(com.android.phoneagent.perception.Draft(listOf("qijie_jerry@163.com"), "[PA测试]  周五评审", "你好，\n周五评审改到下午3点。\u00a0"))).isTrue()
        assertThat(t.matches(com.android.phoneagent.perception.Draft(listOf("other@163.com"), t.subject, t.body))).isFalse()
        assertThat(t.matches(com.android.phoneagent.perception.Draft(listOf("qijie_jerry@163.com"), t.subject, "别的正文"))).isFalse()
        assertThat(t.matches(null)).isFalse()
        assertThat(EmailTask("Alice", "s", "b").matches(com.android.phoneagent.perception.Draft(listOf("alice@x.com"), "s", "b"))).isFalse()   // 只有名字不做本地核对
    }

    @Test fun promptAsksForGeneralFriendlyRepliesWithoutListingFeatures() = runTest {
        val model = FakeModel(listOf(clarifyJson(type = "chitchat", reply = "您好！")))
        Clarifier(model, clock = { NOW }).clarify(user("你好"))
        val sys = model.requests.single().system
        assertThat(sys).contains("不要罗列")
        assertThat(sys).contains("有什么可以帮到您的吗")
        assertThat(sys).contains("愉快的美式问候")
    }
}

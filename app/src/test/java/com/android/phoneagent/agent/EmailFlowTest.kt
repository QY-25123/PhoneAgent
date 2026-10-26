package com.android.phoneagent.agent

import com.android.phoneagent.perception.Draft
import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.safety.ConfirmToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmailFlowTest {
    private val task = EmailTask("qijie_jerry@163.com", "[PA测试] 周五评审", "你好，周五评审改到下午3点。谢谢")
    private val inbox = inboxScreen("已发送")
    private val composeOk = gmailScreen()

    /** 按顺序给出用户的选择；记录每次确认展示的草稿和 agent 说的话；[onConfirm] 在用户做决定的那一刻被调用。 */
    private class Host(private val decisions: List<ConfirmDecision>, private val onConfirm: () -> Unit = {}) : EmailHost {
        val shown = ArrayList<Draft>()
        val said = ArrayList<String>()
        override fun onStep(info: StepInfo) {}
        override suspend fun askUser(question: String, options: List<String>) = "好"
        override suspend fun confirm(draft: Draft): ConfirmDecision { shown += draft; onConfirm(); return decisions[minOf(shown.size - 1, decisions.size - 1)] }
        override fun say(text: String) { said += text }
    }

    private fun flow(model: ModelClient, phone: FakePhone, host: EmailHost) =
        EmailFlow(model, phone, host, LoopConfig(), { NOW }, NoopRunLog, sleep = {}, undoWaitMs = 0)

    private fun sendTaps(p: FakePhone) = p.executed.filterIsInstance<Action.Tap>().filter { it.index == 2 }

    private fun phoneWith(vararg screens: PerceptionResult, last: PerceptionResult = inbox, arm: (String) -> Boolean = { true }): FakePhone {
        val list = screens.toList()
        return FakePhone({ list.getOrElse(it) { last } }).also { it.armResult = arm }
    }

    private val verifiedSent = listOf(stepJson("done", success = true, result = "在已发送里看到了"), verifyJson(true))

    @Test fun confirmedSendIsHashCheckedArmedClickedAndVerifiedInSent() = runTest {
        val phone = phoneWith(composeOk, composeOk, composeOk)     // 预填检查、读草稿、点发送前重读
        val host = Host(listOf(ConfirmDecision.Send))
        val r = flow(FakeModel(verifiedSent), phone, host).run(task) as EmailResult.Sent
        assertThat(host.shown.single()).isEqualTo(Draft(listOf("qijie_jerry@163.com"), task.subject, task.body))   // 卡片展示的是页面上实际读到的
        assertThat(phone.armed).containsExactly(ConfirmToken.hash(host.shown.single()))
        assertThat(sendTaps(phone)).hasSize(1)
        assertThat(phone.disarms).isEqualTo(1)
        assertThat(r.draft.subject).isEqualTo(task.subject)
        assertThat(phone.emailTask).isEqualTo(task)
    }

    @Test fun nothingIsSentBeforeTheUserConfirms() = runTest {
        val phone = phoneWith(composeOk, composeOk, composeOk)
        var tapsAtConfirm = -1
        val host = Host(listOf(ConfirmDecision.Send)) { tapsAtConfirm = sendTaps(phone).size + phone.armed.size }
        flow(FakeModel(verifiedSent), phone, host).run(task)
        assertThat(tapsAtConfirm).isEqualTo(0)       // 确认的那一刻：没有布防，也没点过发送
    }

    @Test fun nameOnlyRecipientIsResolvedByTheUiAndTheCardShowsTheRealAddress() = runTest {
        // 用户只说了名字：模型在界面上选了联系人建议；确认卡片展示的是页面上实际的邮箱地址，不是名字
        val t = EmailTask("Alice", "[PA测试] 改期", "你好，改期了。")
        val page = gmailScreen(to = "alice@example.com", subject = t.subject, body = t.body)
        val phone = phoneWith(page, page, last = inboxScreen("Drafts"))
        val host = Host(listOf(ConfirmDecision.Cancel))
        val model = FakeModel(listOf(stepJson("done", success = true, result = "草稿已备好"), stepJson("done", success = true, result = "已丢弃"), stepJson("done", success = true, result = "没有残留"), verifyJson(true)))
        flow(model, phone, host).run(t)
        assertThat(host.shown.first().to).containsExactly("alice@example.com")
        assertThat(phone.emailTask).isEqualTo(t)
    }

    @Test fun modifyRunsTheModelThenAsksForConfirmationAgainWithTheNewContent() = runTest {
        val changed = gmailScreen(body = "你好，周五评审改到下午4点。谢谢")
        // 页面序列：预填(1) 读草稿(2) | 修改循环：快速检查(3)、主循环(4) | 改完重读(5) | 点发送前重读(6)
        val phone = phoneWith(composeOk, composeOk, composeOk, composeOk, changed, changed)
        val host = Host(listOf(ConfirmDecision.Modify("改成下午4点"), ConfirmDecision.Send))
        val model = FakeModel(listOf(stepJson("type", index = 8, text = "你好，周五评审改到下午4点。谢谢"), stepJson("done", success = true, result = "草稿已修改")) + verifiedSent)
        val r = flow(model, phone, host).run(task) as EmailResult.Sent
        assertThat(host.shown).hasSize(2)
        assertThat(host.shown[0].body).contains("3点")
        assertThat(host.shown[1].body).contains("4点")              // 第二张确认卡片是改后的内容
        assertThat(phone.armed.single()).isEqualTo(ConfirmToken.hash(host.shown[1]))
        assertThat(r.draft.body).contains("4点")
        assertThat(model.requests.any { it.messages.single().text.contains("改成下午4点") }).isTrue()
    }

    @Test fun cancelDiscardsTheDraftAndChecksTheDraftsFolder() = runTest {
        val phone = phoneWith(composeOk, composeOk, last = inboxScreen("Drafts"))
        val host = Host(listOf(ConfirmDecision.Cancel))
        // 丢弃循环：点 More options → 写邮件页关闭，本地核对通过（不调用模型）；然后单独打开草稿箱核对（模型 + 自查）
        val model = FakeModel(listOf(stepJson("tap", index = 3), stepJson("done", success = true, result = "草稿已丢弃"), stepJson("done", success = true, result = "草稿箱里没有该主题"), verifyJson(true)))
        val r = flow(model, phone, host).run(task) as EmailResult.Cancelled
        assertThat(r.discarded).isTrue()
        assertThat(phone.opened).contains("gmail")                 // 丢弃后 Gmail 任务已关闭：核对草稿箱前重新打开 Gmail
        assertThat(sendTaps(phone)).isEmpty()                      // 取消：绝不点发送
        assertThat(phone.armed).isEmpty()
        assertThat(model.requests.any { it.messages.single().text.contains("丢弃") }).isTrue()
    }

    @Test fun cancelReportsWhenTheDraftsFolderCouldNotBeConfirmed() = runTest {
        val phone = phoneWith(composeOk, composeOk, last = inboxScreen("Drafts"))
        val model = FakeModel(listOf(stepJson("done", success = true, result = "好了"), stepJson("done", success = true, result = "好了"), verifyJson(false, "草稿箱里还有该主题的草稿"),
            stepJson("done", success = true, result = "好了"), verifyJson(false, "草稿箱里还有该主题的草稿")))
        val r = flow(model, phone, Host(listOf(ConfirmDecision.Cancel))).run(task) as EmailResult.Cancelled
        assertThat(r.discarded).isFalse()
        assertThat(r.note).contains("草稿箱里还有")
    }

    @Test fun contentChangedAfterConfirmationIsNotSentAndAsksAgain() = runTest {
        val drifted = gmailScreen(body = "确认之后被改掉的正文")
        // 预填(1) 读草稿(2) | 用户点发送后重读(3)：内容变了 | 再确认后重读(4)(5)
        val phone = phoneWith(composeOk, composeOk, drifted, drifted, drifted)
        val host = Host(listOf(ConfirmDecision.Send, ConfirmDecision.Send))
        flow(FakeModel(verifiedSent), phone, host).run(task)
        assertThat(host.said.single()).contains("有变化")
        assertThat(host.shown).hasSize(2)
        assertThat(host.shown[1].body).isEqualTo("确认之后被改掉的正文")      // 第二次确认展示的是现在的内容
        assertThat(phone.armed).containsExactly(ConfirmToken.hash(host.shown[1]))   // 只对重新确认的那一版布防，旧哈希从未布防
        assertThat(phone.armed).doesNotContain(ConfirmToken.hash(host.shown[0]))
    }

    @Test fun failedArmingReconfirmsInsteadOfSending() = runTest {
        var first = true
        val phone = phoneWith(composeOk, composeOk, composeOk, composeOk, composeOk, arm = { if (first) { first = false; false } else true })
        val host = Host(listOf(ConfirmDecision.Send, ConfirmDecision.Send))
        flow(FakeModel(verifiedSent), phone, host).run(task)
        assertThat(host.shown).hasSize(2)
        assertThat(host.said.single()).contains("没有通过")
        assertThat(sendTaps(phone)).hasSize(1)      // 只在布防成功之后点了一次
    }

    @Test fun blockedSendClickReconfirms() = runTest {
        var tapped = 0
        val phone = FakePhone({ if (it < 4) composeOk else inboxScreen("Drafts") }, outcome = { _, a -> if (a is Action.Tap && a.index == 2 && tapped++ == 0) ActionOutcome(false, "草稿内容与确认的不一致", blocked = true) else ActionOutcome(true, "ok") })
        val host = Host(listOf(ConfirmDecision.Send, ConfirmDecision.Cancel))
        val model = FakeModel(listOf(stepJson("done", success = true, result = "草稿已丢弃"), stepJson("done", success = true, result = "没有残留"), verifyJson(true)))
        val r = flow(model, phone, host).run(task)
        assertThat(r).isInstanceOf(EmailResult.Cancelled::class.java)
        assertThat(host.said.single()).contains("拦住")
    }

    @Test fun retryChecksTheSentFolderFirstAndDoesNotSendAgain() = runTest {
        val phone = phoneWith(inbox)
        val host = Host(listOf(ConfirmDecision.Send))
        val r = flow(FakeModel(verifiedSent), phone, host).run(task, checkSentFirst = true)
        assertThat(r).isInstanceOf(EmailResult.AlreadySent::class.java)
        assertThat(phone.emailTask).isNull()          // 没有打开写邮件页
        assertThat(host.shown).isEmpty()
        assertThat(sendTaps(phone)).isEmpty()
    }

    @Test fun retryContinuesNormallyWhenNotFoundInSent() = runTest {
        val phone = phoneWith(inbox, composeOk, composeOk, composeOk)
        val model = FakeModel(listOf(stepJson("done", success = false, result = "已发送里没有"), stepJson("done", success = true, result = "ok"), verifyJson(true)))
        val host = Host(listOf(ConfirmDecision.Cancel))
        flow(model, phone, host).run(task, checkSentFirst = true)
        assertThat(phone.emailTask).isEqualTo(task)    // 没找到才继续写草稿
        assertThat(host.shown).hasSize(1)
    }

    @Test fun clickedSendButNotFoundInSentIsReportedAsPossiblySent() = runTest {
        val phone = phoneWith(composeOk, composeOk, composeOk)
        val model = FakeModel(listOf(stepJson("done", success = true, result = "没找到"), verifyJson(false, "已发送里没有这封"), stepJson("done", success = true, result = "没找到"), verifyJson(false, "已发送里没有这封")))
        val r = flow(model, phone, Host(listOf(ConfirmDecision.Send))).run(task) as EmailResult.Failed
        assertThat(r.sendAttempted).isTrue()           // 重试前必须先查已发送
        assertThat(r.reason).contains("已发送")
    }

    @Test fun ifTheComposePageCannotBePreparedNothingIsConfirmedOrSent() = runTest {
        val phone = FakePhone({ inbox }, outcome = { _, _ -> ActionOutcome(false, "x") })
        phone.emailLaunch = ActionOutcome(false, "意图失败")
        val host = Host(listOf(ConfirmDecision.Send))
        val model = FakeModel(listOf(stepJson("done", success = false, result = "打不开写邮件页")))
        val r = flow(model, phone, host).run(task) as EmailResult.Failed
        assertThat(r.sendAttempted).isFalse()
        assertThat(host.shown).isEmpty()
        assertThat(phone.armed).isEmpty()
    }

    @Test fun tooManyRevisionsStops() = runTest {
        val phone = phoneWith(composeOk, composeOk, last = composeOk)
        val model = FakeModel(listOf(stepJson("done", success = true, result = "草稿已修改")))
        val host = Host(listOf(ConfirmDecision.Modify("再改")))
        val r = EmailFlow(model, phone, host, LoopConfig(), { NOW }, NoopRunLog, sleep = {}, undoWaitMs = 0, maxRevisions = 2).run(task)
        assertThat(r).isInstanceOf(EmailResult.Failed::class.java)
        assertThat(host.shown.size).isEqualTo(3)
        assertThat(sendTaps(phone)).isEmpty()
    }
}

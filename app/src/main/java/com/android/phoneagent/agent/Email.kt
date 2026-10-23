package com.android.phoneagent.agent

import com.android.phoneagent.perception.Draft
import com.android.phoneagent.perception.GmailDraft
import com.android.phoneagent.perception.ScreenState

/** 邮件任务：收件人（邮箱地址，或只有名字）、主题、正文（主题和正文由模型根据用户要求写）。 */
data class EmailTask(val recipient: String, val subject: String, val body: String) {
    val hasAddress: Boolean get() = recipient.contains('@')

    fun restatement(): String =
        "好，我给 $recipient 写一封邮件，主题「$subject」。写好后先给你确认，你点发送我才会发。现在去办。"
}

private fun norm(s: String) = s.replace(Regex("[\\s   ]+"), " ").trim()

/** 草稿是否与任务逐项一致（只在给了邮箱地址时可以本地核对）。 */
fun EmailTask.matches(d: Draft?): Boolean =
    d != null && hasAddress && d.to.map { it.lowercase() } == listOf(recipient.trim().lowercase()) &&
        norm(d.subject) == norm(subject) && norm(d.body) == norm(body)

/** 写草稿：mailto 意图预填，对得上就算完成（不调用模型）；对不上交给模型改。绝不点发送。 */
class ComposeDraftTask(private val task: EmailTask) : LoopTask {
    override val taskText = "在 Gmail 里给 ${task.recipient} 准备一封邮件草稿（不发送）"
    override val confirmed = "收件人：${task.recipient}\n主题：${task.subject}\n正文：\n${task.body}"
    override val leftovers = listOf("Gmail 的草稿里可能留有主题为「${task.subject}」的草稿，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.GMAIL_COMPOSE_SECTION)
    override val verifySystem = Prompts.VERIFY_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.launchEmailCompose(task)
    override val prelaunchOkNote = "用 mailto 意图在虚拟屏打开了 Gmail 写邮件页并预填收件人、主题、正文"
    override val prelaunchFailNote = "尝试用 mailto 意图打开写邮件页"
    override val fastClaim = "草稿已备好"
    override fun alreadyDone(screen: ScreenState) = GmailDraft.isCompose(screen) && task.matches(GmailDraft.read(screen))
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? {
        val d = GmailDraft.read(screen) ?: return false to "当前不是 Gmail 写邮件页"
        // 给了邮箱地址：逐项核对；只有名字：只要求收件人框里有一个邮箱地址，主题和正文一致
        val ok = if (task.hasAddress) task.matches(d) else d.to.size == 1 && norm(d.subject) == norm(task.subject) && norm(d.body) == norm(task.body)
        return if (ok) true to "草稿的收件人、主题、正文与要求一致" else false to "草稿内容与要求不一致：收件人=${d.to}，主题=「${d.subject}」"
    }
}

/** 按用户的修改要求改草稿（只改草稿，不发送）。改完后由代码重新读取并重新请用户确认。 */
class ModifyDraftTask(private val current: Draft, private val instruction: String) : LoopTask {
    override val taskText = "按用户的要求修改 Gmail 邮件草稿（不发送）"
    override val confirmed = "当前草稿：收件人 ${current.to.joinToString()}；主题「${current.subject}」；正文：\n${current.body}\n\n用户的修改要求：$instruction"
    override val leftovers = listOf("Gmail 草稿可能已被部分修改，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.GMAIL_MODIFY_SECTION)
    override val verifySystem = Prompts.VERIFY_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = ActionOutcome(true, "当前就在写邮件页")
    override val prelaunchOkNote = "当前应该就在 Gmail 写邮件页，草稿内容见上"
    override val prelaunchFailNote = ""
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? =
        if (GmailDraft.isCompose(screen)) true to "草稿已修改" else false to "当前不是 Gmail 写邮件页"
}

/** 在「已发送」里核对邮件。 */
class VerifySentTask(private val to: String, private val subject: String) : LoopTask {
    override val taskText = "在 Gmail 的「已发送」文件夹里核对：发给 $to、主题为「$subject」的邮件是否已经发出"
    override val confirmed = "收件人：$to\n主题：$subject"
    override val leftovers = listOf("不确定邮件是否已发出，请在 Gmail 的「已发送」里查看（发给 $to，主题「$subject」）")
    override val stepSystem = Prompts.stepSystem(Prompts.GMAIL_SENT_SECTION)
    override val verifySystem = Prompts.VERIFY_SENT_SYSTEM
    // 发送之后 Gmail 的任务常常已经关闭（虚拟屏全黑）：先重新打开 Gmail 再查
    override suspend fun prelaunch(phone: PhoneController) = phone.openApp("gmail")
    override val prelaunchOkNote = "重新打开了 Gmail（刚点过发送，撤销发送时间已过）"
    override val prelaunchFailNote = "重新打开 Gmail"
}

/** 丢弃本次任务创建的草稿，并核对草稿箱没有残留。 */
class DiscardDraftTask(private val subject: String) : LoopTask {
    override val taskText = "丢弃本次任务创建的 Gmail 草稿（主题「$subject」）"
    override val confirmed = "要丢弃的草稿主题：$subject"
    override val leftovers = listOf("Gmail 草稿箱里可能还留有主题为「$subject」的草稿，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.GMAIL_DISCARD_SECTION)
    override val verifySystem = Prompts.VERIFY_DRAFTS_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = ActionOutcome(true, "ok")
    override val prelaunchOkNote = "当前应该就在 Gmail 写邮件页"
    override val prelaunchFailNote = ""
    // 丢弃后写邮件页会关闭（屏幕常常全黑）：不再是写邮件页就算丢弃动作完成，草稿箱另外核对
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? =
        if (GmailDraft.isCompose(screen)) false to "还停在写邮件页，草稿没有丢弃" else true to "写邮件页已关闭"
}

/** 核对草稿箱里没有本次任务的草稿（没有就算干净；有就丢弃）。 */
class CheckDraftsTask(private val subject: String) : LoopTask {
    override val taskText = "确认 Gmail 草稿箱里没有主题为「$subject」的草稿"
    override val confirmed = "要确认没有的草稿主题：$subject"
    override val leftovers = listOf("Gmail 草稿箱里可能还留有主题为「$subject」的草稿，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.GMAIL_DRAFTS_SECTION)
    override val verifySystem = Prompts.VERIFY_DRAFTS_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.openApp("gmail")
    override val prelaunchOkNote = "重新打开了 Gmail"
    override val prelaunchFailNote = "重新打开 Gmail"
}

// ---------------------------------------------------------------- 流程

sealed interface ConfirmDecision {
    data object Send : ConfirmDecision
    data class Modify(val instruction: String) : ConfirmDecision
    data object Cancel : ConfirmDecision
}

interface EmailHost : LoopHost {
    /** 把从 Gmail 实际读到的草稿展示给用户，等用户选择发送 / 修改 / 取消 */
    suspend fun confirm(draft: Draft): ConfirmDecision
    fun say(text: String)
}

sealed interface EmailResult {
    data class Sent(val draft: Draft, val evidence: String) : EmailResult
    /** 重试前发现已经发出过：不再重复发送 */
    data class AlreadySent(val evidence: String) : EmailResult
    data class Cancelled(val discarded: Boolean, val note: String) : EmailResult
    /** [sendAttempted]：已经点过发送（不确定是否发出），重试前必须先查「已发送」 */
    data class Failed(val reason: String, val leftovers: List<String>, val sendAttempted: Boolean) : EmailResult
}

/**
 * T2：写草稿 → 从界面实际读取并让用户确认 → 用户点发送后核对内容哈希、一次性放行、点击发送 → 在「已发送」里核对。
 * 修改会让模型改草稿后重新确认；取消会丢弃草稿并核对草稿箱。确认发生之前不会有任何发送动作。
 */
class EmailFlow(
    private val model: ModelClient,
    private val phone: PhoneController,
    private val host: EmailHost,
    private val cfg: LoopConfig = LoopConfig(),
    private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val log: RunLog = NoopRunLog,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** 点发送之后等「撤销发送」时间结束再去核对（Gmail 默认约 5 秒） */
    private val undoWaitMs: Long = 7000,
    private val maxRevisions: Int = 5,
) {
    private fun loop() = AgentLoop(model, phone, host, cfg, clock, log, sleep)

    suspend fun run(task: EmailTask, checkSentFirst: Boolean = false): EmailResult {
        if (checkSentFirst) {
            // 重试：上次可能已经点过发送。先查「已发送」，发过了就不再发（FR-CON-5）
            val r = loop().run(VerifySentTask(task.recipient, task.subject))
            if (r is LoopResult.Success) return EmailResult.AlreadySent(r.evidence)
        }
        val prep = loop().run(ComposeDraftTask(task))
        if (prep is LoopResult.Failure) return EmailResult.Failed(prep.reason, prep.leftovers, sendAttempted = false)

        var draft = readDraft() ?: return EmailResult.Failed("没能从 Gmail 写邮件页读取到草稿内容", emptyList(), false)
        var revisions = 0
        try {
            while (true) {
                when (val d = host.confirm(draft)) {
                    ConfirmDecision.Cancel -> return cancel(draft)
                    is ConfirmDecision.Modify -> {
                        if (++revisions > maxRevisions) return EmailResult.Failed("修改次数太多了，我先停下，草稿还留在 Gmail 里", listOf("Gmail 草稿箱里有主题为「${draft.subject}」的草稿"), false)
                        val r = loop().run(ModifyDraftTask(draft, d.instruction))
                        if (r is LoopResult.Failure) return EmailResult.Failed(r.reason, r.leftovers, false)
                        draft = readDraft() ?: return EmailResult.Failed("改完之后没能读取到草稿内容", emptyList(), false)
                    }
                    ConfirmDecision.Send -> {
                        val shown = com.android.phoneagent.safety.ConfirmToken.hash(draft)
                        val screen = phone.perceive()
                        val current = GmailDraft.read(screen.state)
                        if (current == null || com.android.phoneagent.safety.ConfirmToken.hash(current) != shown) {
                            // 点发送之前页面内容和确认时不一致：不放行，把现在的内容重新给用户确认
                            if (current == null) return EmailResult.Failed("发送前没能读取到草稿内容，没有发送", emptyList(), false)
                            host.say("草稿内容在你确认之后有变化，我把现在的内容重新给你确认一次。")
                            draft = current
                            continue
                        }
                        if (!phone.armGmailSend(shown)) {
                            host.say("发送前的内容核对没有通过，我把现在的内容重新给你确认一次。")
                            draft = readDraft() ?: return EmailResult.Failed("没能重新读取草稿内容，没有发送", emptyList(), false)
                            continue
                        }
                        val idx = GmailDraft.sendIndex(screen.state) ?: return EmailResult.Failed("找不到发送按钮，没有发送", emptyList(), false)
                        host.onStep(StepInfo(null, "草稿内容与你确认的一致", "点击「发送」", screen))
                        val out = phone.execute(Action.Tap(idx), screen.state)
                        if (!out.ok) {
                            if (out.blocked) {
                                host.say("发送被安全规则拦住了，我把现在的内容重新给你确认一次。")
                                draft = readDraft() ?: return EmailResult.Failed("发送被拦截，没能重新读取草稿", emptyList(), false)
                                continue
                            }
                            return EmailResult.Failed("点击发送失败：${out.message}", listOf("草稿可能还在 Gmail 里"), sendAttempted = true)
                        }
                        sleep(undoWaitMs)   // 等「撤销发送」时间结束
                        val v = loop().run(VerifySentTask(draft.to.firstOrNull() ?: task.recipient, draft.subject))
                        return when (v) {
                            is LoopResult.Success -> EmailResult.Sent(draft, v.evidence)
                            is LoopResult.Failure -> EmailResult.Failed("已点击发送，但没能在「已发送」里确认：${v.reason}", v.leftovers, sendAttempted = true)
                        }
                    }
                }
            }
        } finally {
            runCatching { phone.disarmGmailSend() }
        }
    }

    private suspend fun readDraft(): Draft? = GmailDraft.read(phone.perceive().state)

    private suspend fun cancel(draft: Draft): EmailResult {
        val d = loop().run(DiscardDraftTask(draft.subject))
        if (d is LoopResult.Failure) return EmailResult.Cancelled(false, "没能丢弃草稿：${d.reason}。草稿「${draft.subject}」可能还在 Gmail 草稿箱里，请手动检查")
        val c = loop().run(CheckDraftsTask(draft.subject))
        return when (c) {
            is LoopResult.Success -> EmailResult.Cancelled(true, "草稿已丢弃，草稿箱里没有残留")
            is LoopResult.Failure -> EmailResult.Cancelled(false, "草稿已丢弃，但没能确认草稿箱是否干净：${c.reason}。请手动检查 Gmail 草稿箱")
        }
    }
}

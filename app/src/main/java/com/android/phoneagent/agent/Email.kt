package com.android.phoneagent.agent

import com.android.phoneagent.perception.Draft
import com.android.phoneagent.perception.GmailDraft
import com.android.phoneagent.perception.ScreenState

/** 邮件任务：收件人（邮箱地址，或只有名字）、主题、正文（主题和正文由模型根据用户要求写）。 */
data class EmailTask(val recipient: String, val subject: String, val body: String) {
    val hasAddress: Boolean get() = recipient.contains('@')

    fun restatement(): String = "邮件：发给 $recipient，主题「$subject」"
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
    data class Cancelled(val discarded: Boolean, val note: String) : EmailResult
    /** [sendAttempted]：已经点过发送（不确定是否发出） */
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
    /** 点发送之后等一会儿再看写邮件页是不是已经关闭（发出的邮件在后台走完「撤销发送」时间，不用等） */
    private val undoWaitMs: Long = 2500,
    private val maxRevisions: Int = 5,
) {
    private fun loop() = AgentLoop(model, phone, host, cfg, clock, log, sleep)

    suspend fun run(task: EmailTask): EmailResult {
        val prep = loop().run(ComposeDraftTask(task))
        if (prep is LoopResult.Failure) return EmailResult.Failed(prep.reason, prep.leftovers, sendAttempted = false)

        var draft = readDraft() ?: return EmailResult.Failed("没能从 Gmail 写邮件页读取到草稿内容", emptyList(), false)
        var revisions = 0
        try {
            while (true) {
                when (val d = host.confirm(draft)) {
                    ConfirmDecision.Cancel -> return cancel(draft)
                    is ConfirmDecision.Modify -> {
                        if (++revisions > maxRevisions) return EmailResult.Failed("修改次数太多，已停止，草稿还在 Gmail 里", listOf("Gmail 草稿箱里有主题为「${draft.subject}」的草稿"), false)
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
                            host.say("草稿在确认后有变化，请重新确认。")
                            draft = current
                            continue
                        }
                        if (!phone.armGmailSend(shown)) {
                            host.say("核对没有通过，请重新确认。")
                            draft = readDraft() ?: return EmailResult.Failed("没能重新读取草稿内容，没有发送", emptyList(), false)
                            continue
                        }
                        val idx = GmailDraft.sendIndex(screen.state) ?: return EmailResult.Failed("找不到发送按钮，没有发送", emptyList(), false)
                        host.onStep(StepInfo(null, "草稿内容与你确认的一致", "点击「发送」", screen))
                        val out = phone.execute(Action.Tap(idx), screen.state)
                        if (!out.ok) {
                            if (out.blocked) {
                                host.say("发送被拦住了，请重新确认。")
                                draft = readDraft() ?: return EmailResult.Failed("发送被拦截，没能重新读取草稿", emptyList(), false)
                                continue
                            }
                            return EmailResult.Failed("点击发送失败：${out.message}", listOf("草稿可能还在 Gmail 里"), sendAttempted = true)
                        }
                        sleep(undoWaitMs)
                        // 点了发送后 Gmail 会关掉写邮件页：写邮件页没了就是发出去了（不再去「已发送」里查）；还在，说明没发出
                        return if (GmailDraft.read(phone.perceive().state) == null) EmailResult.Sent(draft, "写邮件页已关闭，邮件已发出")
                        else EmailResult.Failed("点了发送，但写邮件页还在，邮件可能没有发出", listOf("草稿可能还在 Gmail 里"), sendAttempted = true)
                    }
                }
            }
        } finally {
            runCatching { phone.disarmGmailSend() }
        }
    }

    private suspend fun readDraft(): Draft? = GmailDraft.read(phone.perceive().state)

    private suspend fun cancel(draft: Draft): EmailResult {
        // 丢弃动作没做成也要继续核对草稿箱：核对步骤会发现并丢弃残留的这份草稿
        loop().run(DiscardDraftTask(draft.subject))
        val c = loop().run(CheckDraftsTask(draft.subject))
        return when (c) {
            is LoopResult.Success -> EmailResult.Cancelled(true, "草稿已丢弃，草稿箱里没有残留")
            is LoopResult.Failure -> EmailResult.Cancelled(false, "草稿已丢弃，但没能确认草稿箱是否干净：${c.reason}。请手动检查 Gmail 草稿箱")
        }
    }
}

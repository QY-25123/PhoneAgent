package com.android.phoneagent.agent

import com.android.phoneagent.perception.ScreenState
import com.android.phoneagent.perception.WechatChat
import com.android.phoneagent.perception.WechatDraft
import com.android.phoneagent.safety.ConfirmToken

/** 微信任务：发给谁、发什么（内容由用户给出，或由模型按用户要求写好）。 */
data class WechatTask(val contact: String, val message: String) {
    fun restatement(): String = "微信：发给「$contact」：$message"
}

private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

/** 打开微信、搜索联系人、进入聊天、把消息写进输入框。绝不点发送、不按回车。 */
class ComposeWechatTask(private val task: WechatTask) : LoopTask {
    override val taskText = "在微信里给「${task.contact}」准备一条消息（写进输入框，不发送）"
    override val confirmed = "联系人：${task.contact}\n消息内容：\n${task.message}"
    override val leftovers = listOf("微信里可能停在与「${task.contact}」的聊天页，输入框里可能留有没发送的文字")
    override val stepSystem = Prompts.stepSystem(Prompts.WECHAT_COMPOSE_SECTION)
    override val verifySystem = Prompts.VERIFY_WECHAT_COMPOSE_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.openApp("wechat")
    override val prelaunchOkNote = "在虚拟屏打开了微信"
    override val prelaunchFailNote = "尝试打开微信"
    // 聊天页能读到、输入框里的文字与要求一致才算备好；读不到（界面树不全）交给模型看截图自查
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? {
        if (screen.packageName != WechatChat.PKG) return false to "当前不在微信"
        val d = WechatChat.read(screen) ?: return null
        return if (norm(d.text) == norm(task.message)) true to "聊天「${d.to}」的输入框里已写好消息"
        else false to "输入框里的内容与要求不一致：「${d.text}」"
    }
}

/** 发送之后核对：消息出现在聊天记录里，且没有发送失败的标记（红色感叹号）。 */
class VerifyWechatSentTask(private val draft: WechatDraft) : LoopTask {
    override val taskText = "核对微信聊天「${draft.to}」里刚发的消息已经出现在记录里，且没有发送失败的标记"
    override val confirmed = "聊天：${draft.to}\n消息：${draft.text}"
    override val leftovers = listOf("不确定消息是否发出，请在微信里查看与「${draft.to}」的聊天")
    override val stepSystem = Prompts.stepSystem(Prompts.WECHAT_SENT_SECTION)
    override val verifySystem = Prompts.VERIFY_WECHAT_SENT_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = ActionOutcome(true, "ok")
    override val prelaunchOkNote = "当前应该就在聊天页，刚点过发送"
    override val prelaunchFailNote = ""
    override fun alreadyDone(screen: ScreenState) = WechatChat.sentOk(screen, draft.text)
    override val fastClaim = "消息已出现在聊天记录里"
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? {
        if (WechatChat.sendFailed(screen)) return false to "聊天里有发送失败的标记（红色感叹号）"
        if (WechatChat.sentOk(screen, draft.text)) return true to "消息出现在与「${draft.to}」的聊天记录里，没有发送失败的标记"
        return null   // 界面树读不全：看截图自查
    }
}

interface WechatHost : LoopHost {
    fun say(text: String)
}

sealed interface WechatResult {
    data class Sent(val draft: WechatDraft, val evidence: String) : WechatResult
    data class Failed(val reason: String, val leftovers: List<String>) : WechatResult
}

/**
 * 微信：写进输入框 → 从聊天页实际读取 → 发送（用户已决定微信不需要确认；特权侧内容绑定放行一次，发送按钮平时仍被硬拦截）→ 核对聊天记录。
 * 微信的界面树常常读不到：读不到时用截图转写（[WechatVision]）读聊天页，用截图上的位置点发送；特权侧改按像素区域拦截。
 */
class WechatFlow(
    private val model: ModelClient,
    private val phone: PhoneController,
    private val host: WechatHost,
    private val cfg: LoopConfig = LoopConfig(),
    private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val log: RunLog = NoopRunLog,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val maxRevisions: Int = 5,
) {
    private fun loop() = AgentLoop(model, phone, host, cfg, clock, log, sleep)

    /** 聊天页现在的样子：界面树读得到就用树，读不到用截图转写。 */
    private class Snap(
        val screen: com.android.phoneagent.perception.PerceptionResult,
        val draft: WechatDraft?,          // 聊天标题 + 输入框文字（输入框为空时 text 为空）
        val sendAction: Action?,          // 点发送的动作
        val sentOk: Boolean, val failed: Boolean,
        val viaVision: Boolean,
    )

    private suspend fun snap(message: String? = null): Snap {
        val screen = phone.perceive()
        val tree = WechatChat.read(screen.state)
        if (tree != null) {
            return Snap(screen, tree, WechatChat.sendIndex(screen.state)?.let { Action.Tap(it) },
                message != null && WechatChat.sentOk(screen.state, message), WechatChat.sendFailed(screen.state), false)
        }
        var v = WechatVision.read(model, screen, log)
        if (v == null || !v.isChat || v.title.isEmpty()) return Snap(screen, null, null, false, v?.failedMark == true, true)
        // The model sometimes cannot locate the Send button: read once more, then fall back to where WeChat puts it
        // (the green button at the right end of the input bar, which replaces the "+" once there is text)
        if (v.inputText.isNotEmpty() && (v.sendX == null || v.sendY == null)) v = WechatVision.read(model, screen, log) ?: v
        val send = if (v.sendX != null && v.sendY != null) Action.TapXY(v.sendX, v.sendY)
        else if (v.inputText.isNotEmpty()) Action.TapXY(DEFAULT_SEND_X, DEFAULT_SEND_Y) else null
        val sent = message != null && v.inputText.isEmpty() && norm(v.lastSent).contains(norm(message)) && !v.failedMark
        return Snap(screen, WechatDraft(v.title, v.inputText), send, sent, v.failedMark, true)
    }

    private suspend fun readDraft(): WechatDraft? = snap().draft?.takeIf { it.text.isNotEmpty() }

    suspend fun run(task: WechatTask): WechatResult {
        var prep = loop().run(ComposeWechatTask(task))
        // WeChat sometimes comes up dimmed / frozen after being moved onto the virtual display: relaunching it (the prelaunch of the task) usually fixes that
        if (prep is LoopResult.Failure && prep.kind != StopKind.BLOCKED) prep = loop().run(ComposeWechatTask(task))
        if (prep is LoopResult.Failure) return WechatResult.Failed(prep.reason, prep.leftovers)
        val draft = readDraft() ?: return WechatResult.Failed("没能从微信聊天页读取到输入框里的消息", ComposeWechatTask(task).leftovers)
        try {
            // 不再让用户确认：由流程自己布防。特权侧的发送拦截仍然有效——只有这里布防过的一次点击能通过，模型自己点发送会被拦
            val shown = ConfirmToken.hashWechat(draft)
            val now = snap()
            val current = now.draft
            if (current == null || ConfirmToken.hashWechat(current) != shown) return WechatResult.Failed("发送前消息内容有变化，没有发送", ComposeWechatTask(task).leftovers)
            if (!phone.armWechatSend(shown)) return WechatResult.Failed("发送前的核对没有通过，没有发送", ComposeWechatTask(task).leftovers)
            val sendAction = now.sendAction ?: return WechatResult.Failed("找不到发送按钮，没有发送", ComposeWechatTask(task).leftovers)
            host.onStep(StepInfo(null, "聊天「${draft.to}」输入框里是要发的消息", "点击「发送」", now.screen))
            val out = phone.execute(sendAction, now.screen.state)
            if (!out.ok) return WechatResult.Failed(if (out.blocked) "发送被安全规则拦截：${out.message}" else "点击发送失败：${out.message}", listOf("消息可能还在输入框里"))
            sleep(1500)
            val after = snap(draft.text)
            if (after.failed) return WechatResult.Failed("消息旁边出现了发送失败的标记（红色感叹号）", listOf("请在微信里查看与「${draft.to}」的聊天"))
            if (after.sentOk) return WechatResult.Sent(draft, "消息出现在与「${draft.to}」的聊天记录里，没有发送失败的标记")
            return when (val v = loop().run(VerifyWechatSentTask(draft))) {
                is LoopResult.Success -> WechatResult.Sent(draft, v.evidence)
                is LoopResult.Failure -> WechatResult.Failed("已点击发送，但没能确认消息发出：${v.reason}", v.leftovers)
            }
        } finally {
            runCatching { phone.disarmWechatSend() }
        }
    }
}

/** Fallback position (0-1000 of the screenshot) of WeChat's Send button when the model cannot find it. */
private const val DEFAULT_SEND_X = 930
private const val DEFAULT_SEND_Y = 965

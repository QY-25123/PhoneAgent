package com.android.phoneagent.perception

/** 微信聊天页上实际读到的内容：对方（聊天标题）和输入框里的文字。 */
data class WechatDraft(val to: String, val text: String)

/**
 * 从微信聊天页读标题、输入框、发送按钮，并判断消息是否已出现在聊天记录里。纯函数。
 * 微信的界面树信息可能不全：读不到时返回 null，调用方退回截图 + 坐标。
 */
object WechatChat {
    const val PKG = "com.tencent.mm"
    private val SEND = Regex("^(send|发送)(\\s+(send|发送))*$", RegexOption.IGNORE_CASE)
    private val FAILED = Regex("send failed|failed to send|resend|re-send|发送失败|重发|重新发送", RegexOption.IGNORE_CASE)

    private fun label(e: UiElement) = listOfNotNull(e.text, e.desc, e.mergedText).joinToString(" ").trim()

    /** 输入框：屏幕上最靠下的可编辑元素。 */
    fun inputElement(s: ScreenState): UiElement? =
        s.elements.filter { ElementAction.EDIT in it.actions }.maxByOrNull { it.bounds.top }

    fun isChat(s: ScreenState) = s.packageName == PKG && inputElement(s) != null

    fun sendIndex(s: ScreenState): Int? =
        s.elements.firstOrNull { ElementAction.CLICK in it.actions && SEND.matches(label(it)) }?.index

    private fun title(s: ScreenState, input: UiElement): String? {
        val topLimit = s.screenHeight * 0.16
        val c = s.elements.filter { it !== input && it.bounds.bottom <= topLimit && ElementAction.EDIT !in it.actions }
            .mapNotNull { e -> (e.text ?: e.desc ?: e.mergedText)?.trim()?.takeIf { it.isNotEmpty() }?.let { e to it } }
            .filter { (e, t) -> t.length > 1 && !t.equals("Back", true) && !t.equals("Navigate up", true) && e.bounds.centerX in (s.screenWidth / 5)..(s.screenWidth * 4 / 5) }
        return c.minByOrNull { (e, _) -> kotlin.math.abs(e.bounds.centerX - s.screenWidth / 2) }?.second
            ?: s.pageTitle?.takeIf { it.isNotBlank() }
    }

    fun read(s: ScreenState): WechatDraft? {
        if (s.packageName != PKG) return null
        val input = inputElement(s) ?: return null
        val to = title(s, input) ?: return null
        val raw = input.text?.trim().orEmpty()
        val hint = input.hint?.trim()
        val text = if (raw.isEmpty() || raw == hint) "" else raw
        return WechatDraft(to, text)
    }

    private fun norm(t: String) = t.replace(Regex("\\s+"), " ").trim()

    /** 聊天记录里有这条消息（输入框之外的元素里能找到消息文字）。 */
    fun messageVisible(s: ScreenState, text: String): Boolean {
        val input = inputElement(s)
        val t = norm(text)
        return s.elements.any { it !== input && ElementAction.EDIT !in it.actions && listOfNotNull(it.text, it.desc, it.mergedText).any { x -> norm(x).contains(t) } }
    }

    fun sendFailed(s: ScreenState) = s.elements.any { FAILED.containsMatchIn(label(it)) }

    /** 发送成功：输入框已清空、消息出现在记录里、没有发送失败的标记。 */
    fun sentOk(s: ScreenState, text: String): Boolean {
        val d = read(s) ?: return false
        return d.text.isEmpty() && messageVisible(s, text) && !sendFailed(s)
    }
}

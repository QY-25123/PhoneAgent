package com.android.phoneagent.perception

/** Gmail 写邮件页上实际读到的内容（以界面为准，不是模型「打算写」的）。 */
data class Draft(val to: List<String>, val subject: String, val body: String)

/**
 * 从 Gmail 写邮件页的元素里读出收件人、主题、正文，并找到「发送」按钮。纯函数，样本和设备上都用它。
 * 只认英文界面的固定提示（To / Subject / Compose email / Send / From）和中文的常见对应。
 */
object GmailDraft {
    const val GMAIL = "com.google.android.gm"
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val SUBJECT = setOf("subject", "主题")
    private val BODY_PLACEHOLDER = setOf("compose email", "撰写电子邮件", "撰写邮件")
    private val TO = setOf("to", "收件人")
    private val FROM = setOf("from", "发件人")
    private val SEND = setOf("send", "发送")

    private fun labelOf(e: UiElement) = (e.hint ?: e.desc ?: e.text ?: e.mergedText)?.trim()?.lowercase()

    private fun subjectField(els: List<UiElement>) =
        els.firstOrNull { ElementAction.EDIT in it.actions && (e(it.hint) in SUBJECT || e(it.desc) in SUBJECT || e(it.text) in SUBJECT && it.hint == null) }

    private fun e(s: String?) = s?.trim()?.lowercase() ?: ""

    fun isCompose(s: ScreenState): Boolean =
        s.packageName == GMAIL && subjectField(s.elements) != null && sendIndex(s) != null

    /** 「发送」按钮的元素编号（Gmail 撰写页右上角那个）。 */
    fun sendIndex(s: ScreenState): Int? =
        s.elements.firstOrNull { ElementAction.CLICK in it.actions && (e(it.desc ?: it.text) in SEND || it.resourceId?.endsWith(":id/send") == true) }?.index

    fun read(s: ScreenState): Draft? {
        if (s.packageName != GMAIL) return null
        val els = s.elements
        val subjectEl = subjectField(els) ?: return null
        val subject = subjectEl.text?.takeIf { e(it) !in SUBJECT || subjectEl.hint != null && it != subjectEl.hint }?.trim() ?: ""
        // 正文：主题框下面的第一个输入框；内容是占位文字时当作空
        val bodyEl = els.filter { ElementAction.EDIT in it.actions && it.bounds.top > subjectEl.bounds.top }.minByOrNull { it.bounds.top }
        val bodyRaw = bodyEl?.text?.trim() ?: ""
        val body = if (e(bodyRaw) in BODY_PLACEHOLDER) "" else bodyRaw
        // 收件人：「To」那一行到主题框之间出现的邮箱地址（跳过「From」那一行显示的发件账号）
        val toLabel = els.firstOrNull { labelOf(it) in TO && it.bounds.top < subjectEl.bounds.top }
        val fromLabel = els.firstOrNull { labelOf(it) in FROM && it.bounds.top < subjectEl.bounds.top }
        val top = (toLabel?.bounds?.top ?: (fromLabel?.bounds?.bottom ?: 0)) - 8
        val emails = LinkedHashSet<String>()
        for (it in els) {
            if (it.bounds.top < top || it.bounds.top >= subjectEl.bounds.top) continue
            if (fromLabel != null && it.bounds.centerY in fromLabel.bounds.top..fromLabel.bounds.bottom) continue
            for (t in listOfNotNull(it.text, it.desc, it.mergedText, it.hint)) EMAIL.findAll(t).forEach { m -> emails += m.value.lowercase() }
        }
        return Draft(emails.toList(), subject, body)
    }
}

package com.android.phoneagent.priv

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 不依赖模型的硬拦截（PRD FR-SAFE-1/2/7 的原型）。
 * - 控件级：文案/描述匹配 [HARD] 的控件（含它所在的可点击父控件）区域内的任何点击一律拦截。
 * - 页面级：页面上出现 [LOCK]（提交订单页）时，不允许任何点击/输入，只允许读取、返回和交接。
 * 注意：「Buy Now」会出现在商品页上，所以它只做控件级拦截，不做页面级锁定，否则商品页上的「Add to Cart」也点不了。
 */
object SafetyGuard {
    private val HARD = Regex("buy now|立即购买|place your order|提交订单|1-click|one-click|pay now|complete purchase", RegexOption.IGNORE_CASE)
    private val LOCK = Regex("place your order|提交订单", RegexOption.IGNORE_CASE)
    private const val MARGIN = 24

    private const val GMAIL = "com.google.android.gm"
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** 一次性放行：只对包含该收件人的这一封 Gmail 草稿放行一次发送点击（FR-SAFE-12 的原型）。 */
    @Volatile private var armedRecipient: String? = null
    @Volatile private var armedUntil = 0L

    data class Hard(val rect: Rect, val label: String, val gmailSend: Boolean)

    private fun isGmailSend(n: AccessibilityNodeInfo): Boolean {
        if (n.packageName?.toString() != GMAIL) return false
        val id = n.viewIdResourceName ?: ""
        val l = (n.contentDescription ?: n.text ?: "").toString().trim()
        return id.endsWith(":id/send") || l.equals("send", true) || l == "发送"
    }

    /** Gmail 撰写界面里出现的邮箱地址（收件人芯片、建议等）。 */
    fun composeRecipients(roots: List<AccessibilityNodeInfo>): Set<String> {
        val out = HashSet<String>()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.packageName?.toString() == GMAIL) EMAIL.findAll("${n.text ?: ""} ${n.contentDescription ?: ""}").forEach { out += it.value.lowercase() }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return out
    }

    /** 调试/测试用：草稿里确实有该收件人才布防，布防 120 秒，只放行一次。 */
    fun armGmailSend(roots: List<AccessibilityNodeInfo>, expected: String): Boolean {
        val ok = expected.lowercase() in composeRecipients(roots)
        armedRecipient = if (ok) expected.lowercase() else null
        armedUntil = if (ok) System.currentTimeMillis() + 120_000 else 0
        return ok
    }

    private fun label(n: AccessibilityNodeInfo) = "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()

    // 网页内容（WebView）里控件的可见文案可能是「Submit」，DOM id 才有语义（实测 Amazon：id=buy-now-button 的文案是 Submit），所以也匹配资源 id
    private val HARD_ID = Regex("buy-?now|place-?(your-?)?order|submit-?order|one-?click|1-?click", RegexOption.IGNORE_CASE)
    private val LOCK_ID = Regex("place-?(your-?)?order|submit-?order", RegexOption.IGNORE_CASE)

    fun isHard(n: AccessibilityNodeInfo) = HARD.containsMatchIn(label(n)) || HARD_ID.containsMatchIn(n.viewIdResourceName ?: "")
    fun isLock(n: AccessibilityNodeInfo) = LOCK.containsMatchIn(label(n)) || LOCK_ID.containsMatchIn(n.viewIdResourceName ?: "")

    /** 命中硬拦截的控件：区域为控件自身与最近的可点击祖先的并集。 */
    fun hardControls(roots: List<AccessibilityNodeInfo>): List<Hard> {
        val out = ArrayList<Hard>()
        fun walk(n: AccessibilityNodeInfo) {
            val gm = isGmailSend(n)
            // 只看屏幕上可见的控件：网页里被隐藏的 buy-now 节点高度为 0，不应该拦住它附近的正常点击
            if (n.isVisibleToUser && (gm || isHard(n))) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                var p: AccessibilityNodeInfo? = n
                var i = 0
                while (p != null && !p.isClickable && i++ < 6) p = p.parent
                if (p != null && p.isClickable) r.union(Rect().also { p.getBoundsInScreen(it) })
                out += Hard(r, label(n), gm)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return out
    }

    /** 点击 (x,y) 是否被拦截。consume=true 时，放行 Gmail 发送会消耗一次性布防。 */
    fun blockedAt(roots: List<AccessibilityNodeInfo>, x: Int, y: Int, consume: Boolean = true): String? {
        for (h in hardControls(roots)) {
            if (!Rect(h.rect).apply { inset(-MARGIN, -MARGIN) }.contains(x, y)) continue
            if (h.gmailSend) {
                val ok = armedRecipient != null && System.currentTimeMillis() < armedUntil && armedRecipient in composeRecipients(roots)
                if (ok) { if (consume) { armedRecipient = null; armedUntil = 0 }; continue }
                return "命中 Gmail 发送按钮「${h.label}」：未经确认不允许发送"
            }
            return "命中硬拦截控件「${h.label}」"
        }
        return null
    }

    fun pageLock(roots: List<AccessibilityNodeInfo>): String? {
        var found: String? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            if (n.isVisibleToUser && isLock(n)) { found = label(n); return }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return found
    }
}

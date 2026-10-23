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

    private fun label(n: AccessibilityNodeInfo) = "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()

    fun isHard(n: AccessibilityNodeInfo) = HARD.containsMatchIn(label(n))
    fun isLock(n: AccessibilityNodeInfo) = LOCK.containsMatchIn(label(n))

    /** 命中硬拦截的控件：区域为控件自身与最近的可点击祖先的并集。 */
    fun hardControls(roots: List<AccessibilityNodeInfo>): List<Pair<Rect, String>> {
        val out = ArrayList<Pair<Rect, String>>()
        fun walk(n: AccessibilityNodeInfo) {
            if (isHard(n)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                var p: AccessibilityNodeInfo? = n
                var i = 0
                while (p != null && !p.isClickable && i++ < 6) p = p.parent
                if (p != null && p.isClickable) r.union(Rect().also { p.getBoundsInScreen(it) })
                out += r to label(n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return out
    }

    fun blockedAt(roots: List<AccessibilityNodeInfo>, x: Int, y: Int): String? =
        hardControls(roots).firstOrNull { (r, _) -> Rect(r).apply { inset(-MARGIN, -MARGIN) }.contains(x, y) }?.let { "命中硬拦截控件「${it.second}」" }

    fun pageLock(roots: List<AccessibilityNodeInfo>): String? {
        var found: String? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            if (isLock(n)) { found = label(n); return }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return found
    }
}

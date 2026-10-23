package com.android.phoneagent.safety

/** Amazon 购物车上会改变购物车内容的控件。 */
enum class CartControlKind { DELETE, INCREASE, DECREASE, SAVE_FOR_LATER }

class CartControl(val kind: CartControlKind, val title: String, val currentQty: Int?)

/** 从控件的文字/描述里认出购物车改动控件（只在 Amazon 里用）。认不出的返回 null（不是购物车改动控件）。 */
object CartControls {
    private val DELETE = Regex("(?:^|\\s)Delete (.+)$", RegexOption.DOT_MATCHES_ALL)
    private val QTY = Regex("(Increase|Decrease) quantity by one, Quantity is (\\d+), (.+)$", RegexOption.DOT_MATCHES_ALL)
    private val SAVE = Regex("(?:^|\\s)Save (?!\\$)(.+)$|save for later|move to (cart|wish ?list|list)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val MIN_QTY_DELETE = Regex("Minimum quantity reached, Delete item|Increase item quantity", RegexOption.IGNORE_CASE)

    fun parse(label: String): CartControl? {
        val l = label.trim()
        QTY.find(l)?.let { return CartControl(if (it.groupValues[1] == "Increase") CartControlKind.INCREASE else CartControlKind.DECREASE, it.groupValues[3], it.groupValues[2].toIntOrNull()) }
        MIN_QTY_DELETE.find(l)?.let { return CartControl(CartControlKind.DELETE, "", null) }   // 结算页上的数量控件：没有商品名，永远不在放行清单里
        DELETE.find(l)?.let { return CartControl(CartControlKind.DELETE, it.groupValues[1], null) }
        SAVE.find(l)?.let { return CartControl(CartControlKind.SAVE_FOR_LATER, it.groupValues.getOrNull(1) ?: "", null) }
        return null
    }
}

/**
 * 购物车改动的一次性放行闸门（PRD FR-SAFE-14，与 Gmail 发送确认同一思路）：
 * 用户在确认卡片上确认一组改动后 [arm]；只有**这组改动里的商品**对应的控件才放行——删除只放行一次，
 * 数量加减只在朝着目标数量的方向放行；清单之外的商品、Save for later / Move to cart 一律拦截；没布防或过期一律拦截。
 */
class CartGate(private val now: () -> Long = { System.currentTimeMillis() }, private val ttlMs: Long = 600_000) {
    class Change(val item: String, val action: String, val quantity: Int?)

    private val changes = ArrayList<Change>()
    private val deleted = HashSet<Int>()
    private var armedAt = 0L

    @Synchronized fun arm(list: List<Change>) { changes.clear(); changes += list; deleted.clear(); armedAt = now() }
    @Synchronized fun disarm() { changes.clear(); deleted.clear() }
    @Synchronized fun isArmed() = changes.isNotEmpty() && now() - armedAt <= ttlMs

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun matches(item: String, title: String): Boolean {
        val i = norm(item); val t = norm(title)
        return i.length >= 4 && t.contains(i)
    }

    /** [label] 是被点击控件的文字/描述。[consume] 为 false 时只看看会不会放行。 */
    @Synchronized fun check(label: String, consume: Boolean = true): Boolean {
        val c = CartControls.parse(label) ?: return false
        if (!isArmed()) return false
        return when (c.kind) {
            CartControlKind.SAVE_FOR_LATER -> false
            CartControlKind.DELETE -> {
                val idx = changes.indexOfFirst { it.action == "delete" && matches(it.item, c.title) }
                if (idx < 0 || idx in deleted) false else { if (consume) deleted += idx; true }
            }
            CartControlKind.INCREASE, CartControlKind.DECREASE -> {
                val ch = changes.firstOrNull { it.action == "set_quantity" && it.quantity != null && matches(it.item, c.title) } ?: return false
                val cur = c.currentQty ?: return false
                if (c.kind == CartControlKind.INCREASE) ch.quantity!! > cur else ch.quantity!! < cur
            }
        }
    }
}

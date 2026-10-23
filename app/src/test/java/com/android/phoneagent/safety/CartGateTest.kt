package com.android.phoneagent.safety

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CartGateTest {
    private var t = 0L
    private fun gate() = CartGate(now = { t }, ttlMs = 1000)
    private val del = { title: String -> "Delete $title" }
    private fun inc(n: Int, title: String) = "Increase quantity by one, Quantity is $n, $title"
    private fun dec(n: Int, title: String) = "Decrease quantity by one, Quantity is $n, $title"
    private val a = "QGeeM USB C to HDMI Adapter, 4K Type C to HDMI Cable for Laptop Phone"
    private val b = "Sony WH-CH520 Wireless On-Ear Bluetooth Headphones with Microphone, Black"

    private fun CartGate.deleting(item: String) = arm(listOf(CartGate.Change(item, "delete", null)))

    @Test fun controlsAreRecognized() {
        assertThat(CartControls.parse(del(a))!!.kind).isEqualTo(CartControlKind.DELETE)
        assertThat(CartControls.parse(inc(1, a))!!.currentQty).isEqualTo(1)
        assertThat(CartControls.parse(dec(3, a))!!.kind).isEqualTo(CartControlKind.DECREASE)
        assertThat(CartControls.parse("Save $a")!!.kind).isEqualTo(CartControlKind.SAVE_FOR_LATER)
        assertThat(CartControls.parse("Save for later")!!.kind).isEqualTo(CartControlKind.SAVE_FOR_LATER)
        assertThat(CartControls.parse("Move to cart $b")!!.kind).isEqualTo(CartControlKind.SAVE_FOR_LATER)
        assertThat(CartControls.parse("Minimum quantity reached, Delete item")!!.kind).isEqualTo(CartControlKind.DELETE)
    }

    @Test fun otherControlsAreNotCartEdits() {
        for (l in listOf("Proceed to checkout", "Add to cart", "Share $a", "Compare $b", "Save \$5.00 with coupon", "Subtotal (1 item): \$7.99", "Deselect all items", "Quantity: 1", "Back to cart")) {
            assertThat(CartControls.parse(l)).isNull()
        }
        assertThat(SafetyRules.isCartEdit("com.google.android.calendar", del(a))).isFalse()   // 只在 Amazon 里
        assertThat(SafetyRules.isCartEdit("com.amazon.mShop.android.shopping", del(a))).isTrue()
    }

    @Test fun everythingIsBlockedWhenNotArmed() {
        val g = gate()
        for (l in listOf(del(a), inc(1, a), dec(2, a), "Save $a", "Move to cart $b")) assertThat(g.check(l)).isFalse()
    }

    @Test fun onlyTheConfirmedItemCanBeDeletedAndOnlyOnce() {
        val g = gate(); g.deleting("QGeeM USB C to HDMI Adapter")
        assertThat(g.check(del(b))).isFalse()                // 清单之外的商品
        assertThat(g.check(del(a))).isTrue()
        assertThat(g.check(del(a))).isFalse()                // 一次性
    }

    @Test fun savedForLaterAndMoveToCartAreNeverAllowedEvenForConfirmedItems() {
        val g = gate(); g.deleting("QGeeM USB C to HDMI Adapter")
        assertThat(g.check("Save $a")).isFalse()
        assertThat(g.check("Save for later")).isFalse()
        assertThat(g.check("Move to cart $a")).isFalse()
    }

    @Test fun quantityIsOnlyAllowedTowardsTheTarget() {
        val g = gate(); g.arm(listOf(CartGate.Change("QGeeM USB C to HDMI Adapter", "set_quantity", 3)))
        assertThat(g.check(inc(1, a))).isTrue()
        assertThat(g.check(inc(2, a))).isTrue()
        assertThat(g.check(inc(3, a))).isFalse()             // 到目标了不能再加
        assertThat(g.check(dec(2, a))).isFalse()             // 方向反了
        assertThat(g.check(inc(1, b))).isFalse()             // 别的商品
    }

    @Test fun decreasingTowardsALowerTargetIsAllowed() {
        val g = gate(); g.arm(listOf(CartGate.Change("QGeeM USB C to HDMI Adapter", "set_quantity", 1)))
        assertThat(g.check(dec(3, a))).isTrue()
        assertThat(g.check(dec(1, a))).isFalse()
    }

    @Test fun aSetQuantityChangeDoesNotAllowDeleting() {
        val g = gate(); g.arm(listOf(CartGate.Change("QGeeM USB C to HDMI Adapter", "set_quantity", 2)))
        assertThat(g.check(del(a))).isFalse()
    }

    @Test fun expiredOrDisarmedGateBlocks() {
        val g = gate(); g.deleting("QGeeM USB C to HDMI Adapter")
        t = 1001
        assertThat(g.check(del(a))).isFalse()
        val h = gate(); h.deleting("QGeeM USB C to HDMI Adapter"); h.disarm()
        assertThat(h.check(del(a))).isFalse()
    }

    @Test fun peekDoesNotConsume() {
        val g = gate(); g.deleting("QGeeM USB C to HDMI Adapter")
        assertThat(g.check(del(a), consume = false)).isTrue()
        assertThat(g.check(del(a))).isTrue()
        assertThat(g.check(del(a))).isFalse()
    }

    @Test fun tooShortItemNamesCannotMatchEverything() {
        val g = gate(); g.deleting("a")
        assertThat(g.check(del(a))).isFalse()
    }

    @Test fun checkoutPageQuantityControlsAreNeverAllowed() {
        val g = gate(); g.deleting("QGeeM USB C to HDMI Adapter")
        assertThat(g.check("Minimum quantity reached, Delete item")).isFalse()
    }
}

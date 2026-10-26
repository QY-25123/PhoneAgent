package com.android.phoneagent.agent

import com.android.phoneagent.perception.Bounds
import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.perception.PerceptionTimings
import com.android.phoneagent.perception.ScreenState
import com.android.phoneagent.perception.UiElement
import com.android.phoneagent.safety.SafetyRules
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DoordashTest {
    private val now = ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, ZoneId.of("America/Los_Angeles"))
    private fun clarifier(vararg replies: Any) = Clarifier(FakeModel(replies.toList()), clock = { now })
    private fun user(t: String) = listOf(DialogTurn(true, t))

    @Test fun placeOrderAndDashPassAreHardBlocked() {
        for (l in listOf("Place Order", "Place order · \$24.50", "PLACE ORDER")) assertThat(SafetyRules.isHardLabel(l)).isTrue()
        for (l in listOf("Try DashPass free", "Start DashPass", "Join DashPass", "Get DashPass", "DashPass 30-day free trial")) assertThat(SafetyRules.isHardLabel(l)).isTrue()
        assertThat(SafetyRules.isLockLabel("Place Order")).isTrue()
    }

    @Test fun ordinaryDoordashControlsAreNotBlocked() {
        for (l in listOf("Add to cart", "Add 1 to order", "View Cart", "Go to checkout", "Not now", "No thanks", "Search DoorDash", "Save with DashPass")) assertThat(SafetyRules.isHardLabel(l)).isFalse()
    }

    @Test fun appCatalogKnowsDoordash() {
        assertThat(AppCatalog.resolve("DoorDash")).isEqualTo("com.dd.doordash")
        assertThat(AppCatalog.resolve("com.dd.doordash")).isEqualTo("com.dd.doordash")
    }

    @Test fun clarifierReadsRestaurantItemAndDefaultsToOneOrder() = runBlocking {
        val o = clarifier(clarifyJson(type = "doordash", doordashRestaurant = "Wingstop", doordashItem = "Lemon Pepper boneless wings, 10 pieces", doordashQuantity = "1"))
            .clarify(user("在 DoorDash 点 10 个 Wingstop 的 Lemon Pepper 无骨鸡翅"))
        val t = (o as ClarifyOutcome.ReadyDoordash).task
        assertThat(t.restaurant).isEqualTo("Wingstop")
        assertThat(t.item).contains("10 pieces")
        assertThat(t.quantity).isEqualTo(1)
    }

    @Test fun clarifierAsksWhenItemMissing() = runBlocking {
        val o = clarifier(clarifyJson(type = "doordash", doordashRestaurant = "Wingstop", missing = listOf("菜品"), question = "想点 Wingstop 的什么？"))
            .clarify(user("帮我点份 Wingstop"))
        assertThat((o as ClarifyOutcome.Ask).question).contains("Wingstop")
    }

    private class Host : DoordashHost {
        override fun onStep(info: StepInfo) {}
        override suspend fun askUser(question: String, options: List<String>): String = ""
        override fun say(text: String) {}
    }

    private fun dd(tag: String, vararg labels: String, pkg: String = AppCatalog.DOORDASH): PerceptionResult {
        val els = (listOf(tag) + labels).mapIndexed { i, l ->
            UiElement(i + 1, "id$i$l".take(4).padEnd(4, 'x'), "Button", l, null, null, null, null, "android.widget.Button",
                Bounds(0, i * 100, 500, i * 100 + 80), setOf(ElementAction.CLICK), false, null, true)
        }
        val st = ScreenState(2, pkg, tag, 1080, 2400, 0, els, screenshotJpeg = byteArrayOf(1, 2, 3))
        return PerceptionResult(st, "App: doordash  标题: $tag\n" + els.joinToString("\n") { "[${it.index}] Button \"${it.text}\"" }, PerceptionTimings(0, 1, 1, 1, 1, 1))
    }

    @Test fun orderPageIsRecognizedAndSummarised() {
        val s = dd("Checkout", "Total \$18.09", "Place Order").state
        assertThat(DoordashReader.isOrderPage(s)).isTrue()
        assertThat(DoordashReader.orderSummary(s)).contains("18.09")
        assertThat(DoordashReader.isOrderPage(dd("Cart", "Checkout").state)).isFalse()
        assertThat(DoordashReader.isOrderPage(dd("Checkout", "Place Order", pkg = "com.other").state)).isFalse()
    }

    @Test fun flowHandsOffAtTheCheckoutPageAndNeverClicksPlaceOrder() = runBlocking {
        val cart = dd("Cart", "Wingstop", "10 Boneless Wings")
        val checkout = dd("Checkout", "Total \$18.09", "Place Order")
        lateinit var model: FakeModel
        val phone = FakePhone(screens = { if (model.requests.size < 2) cart else checkout })   // the add-to-cart loop uses two model calls (step + self-check)
        model = FakeModel(listOf(stepJson("done", success = true, result = "购物车里有 10 Boneless Wings"), verifyJson(true)))
        val r = DoordashFlow(model, phone, Host(), LoopConfig(), { now }, NoopRunLog, sleep = {})
            .run(DoordashTask("Wingstop", "Lemon Pepper boneless wings, 10 pieces", 1))
        assertThat(r).isInstanceOf(DoordashResult.Handoff::class.java)
        assertThat((r as DoordashResult.Handoff).summary).contains("18.09")
        assertThat(phone.opened).contains("doordash")
        assertThat(phone.executed.none { it is Action.Tap && it.index == 3 }).isTrue()
    }
}

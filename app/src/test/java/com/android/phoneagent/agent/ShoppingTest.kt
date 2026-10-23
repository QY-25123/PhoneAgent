package com.android.phoneagent.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingTest {
    private fun clarifier(vararg r: Any) = Clarifier(FakeModel(r.toList()), clock = { NOW })
    private fun user(t: String) = listOf(DialogTurn(true, t))

    // ---- 澄清 ----
    @Test fun productRequestIsReadyWithDefaultQuantity() = runTest {
        val r = clarifier(clarifyJson(type = "shopping", product = "USB-C 数据线")).clarify(user("买个 USB-C 数据线")) as ClarifyOutcome.ReadyShopping
        assertThat(r.task).isEqualTo(ShoppingTask("USB-C 数据线", 1, false, null))
        assertThat(r.restatement).contains("提交订单页")
        assertThat(r.restatement).contains("不会点下单")
    }

    @Test fun existingCartNeedsNoProduct() = runTest {
        val r = clarifier(clarifyJson(type = "shopping", useCart = true)).clarify(user("把购物车带到结算页")) as ClarifyOutcome.ReadyShopping
        assertThat(r.task.useCart).isTrue()
        assertThat(r.task.product).isNull()
    }

    @Test fun missingProductAsks() = runTest {
        val r = clarifier(clarifyJson(type = "shopping", missing = listOf("商品"), question = "想买什么？")).clarify(user("帮我买点东西")) as ClarifyOutcome.Ask
        assertThat(r.question).isEqualTo("想买什么？")
        // 模型漏填 missing 时代码兜底
        assertThat(clarifier(clarifyJson(type = "shopping")).clarify(user("x"))).isInstanceOf(ClarifyOutcome.Ask::class.java)
    }

    @Test fun quantityAndExplicitCartRequestAreKept() = runTest {
        val r = clarifier(clarifyJson(type = "shopping", useCart = true, quantity = 2, cartRequest = "把 B 删掉")).clarify(user("x")) as ClarifyOutcome.ReadyShopping
        assertThat(r.task.quantity).isEqualTo(2)
        assertThat(r.task.cartRequest).isEqualTo("把 B 删掉")
    }

    // ---- 感知辅助 ----
    @Test fun cartReaderCountsItemsAndNamesThem() {
        val c = CartReader.read(cartScreen(3).state)
        assertThat(c.itemCount).isEqualTo(3)
        assertThat(c.titles).hasSize(3)
        assertThat(CartReader.read(cartScreen(1).state).itemCount).isEqualTo(1)
    }

    @Test fun orderPageIsRecognizedAndSummarized() {
        assertThat(CartReader.isOrderPage(orderScreen().state)).isTrue()
        assertThat(CartReader.isOrderPage(cartScreen().state)).isFalse()
        assertThat(CartReader.orderSummary(orderScreen().state)).isEqualTo("商品小计 \$7.99，订单总价 \$16.44")
    }

    // ---- 流程 ----
    private class Host(private val answers: List<String> = emptyList(), private val confirm: Boolean = true) : ShoppingHost {
        val asked = ArrayList<Pair<String, List<String>>>()
        val confirmed = ArrayList<List<String>>()
        val said = ArrayList<String>()
        override fun onStep(info: StepInfo) {}
        override suspend fun askUser(question: String, options: List<String>): String { asked += question to options; return answers.getOrElse(asked.size - 1) { "好" } }
        override suspend fun confirmCartChanges(lines: List<String>): Boolean { confirmed += lines; return confirm }
        override fun say(text: String) { said += text }
    }

    private fun flow(model: ModelClient, phone: FakePhone, host: Host) = ShoppingFlow(model, phone, host, LoopConfig(), { NOW }, NoopRunLog, sleep = {})
    private val proceed = stepJson("tap", index = 2)
    private val doneOk = stepJson("done", success = true, result = "到了提交订单页")

    @Test fun existingSingleItemCartGoesStraightToCheckoutAndHandsOff() = runTest {
        // 页面：开购物车(1) 读购物车(2) 结算循环快速检查(3) 点结算后(4)→ 提交订单页
        val phone = FakePhone({ if (it < 3) cartScreen(1) else orderScreen() })
        val host = Host()
        val r = flow(FakeModel(listOf(proceed, doneOk)), phone, host).run(ShoppingTask(null, 1, true, null)) as ShoppingResult.Handoff
        assertThat(r.summary).contains("\$16.44")
        assertThat(host.asked).isEmpty()                                  // 只有一件：不用提示
        assertThat(phone.amazonUrls.first()).isEqualTo(AmazonIntent.CART_URL)
        assertThat(phone.executed).containsExactly(Action.Tap(2))         // 只点了「Proceed to checkout」，没有任何下单动作
    }

    @Test fun multipleItemsAreAnnouncedAndTheUserPicksCheckoutAll() = runTest {
        val phone = FakePhone({ if (it < 3) cartScreen(3) else orderScreen() })
        val host = Host(listOf("全部结算"))
        val r = flow(FakeModel(listOf(proceed, doneOk)), phone, host).run(ShoppingTask(null, 1, true, null))
        assertThat(r).isInstanceOf(ShoppingResult.Handoff::class.java)
        assertThat(host.asked.single().first).contains("全部 3 件商品")
        assertThat(host.asked.single().second).containsExactly("全部结算", "调整购物车")
        assertThat(host.confirmed).isEmpty()                               // 没有要求改购物车：不改
    }

    @Test fun cartChangesAreConfirmedBeforeAnythingIsEdited() = runTest {
        val plan = cartPlanJson(Triple("商品2", "delete", null), Triple("商品1", "set_quantity", 2))
        var confirmedAtEdit = -1
        val phone = FakePhone({ if (it < 6) cartScreen(2) else orderScreen() })
        val host = object : ShoppingHost {
            val lines = ArrayList<String>()
            override fun onStep(info: StepInfo) {}
            override suspend fun askUser(question: String, options: List<String>) = if (options.isNotEmpty()) "调整购物车" else "把商品2删掉，商品1改成2件"
            override suspend fun confirmCartChanges(lines: List<String>): Boolean { this.lines += lines; confirmedAtEdit = phone.executed.size; return true }
            override fun say(text: String) {}
        }
        val model = FakeModel(listOf(plan, stepJson("tap", index = 3), stepJson("done", success = true, result = "改好了"), verifyJson(true), proceed, doneOk))
        val r = ShoppingFlow(model, phone, host, LoopConfig(), { NOW }, NoopRunLog, sleep = {}).run(ShoppingTask(null, 1, true, null))
        assertThat(r).isInstanceOf(ShoppingResult.Handoff::class.java)
        assertThat(host.lines).containsExactly("删除：商品2", "「商品1」数量改为 2").inOrder()
        assertThat(confirmedAtEdit).isEqualTo(0)                           // 确认的那一刻：还没有任何点击
    }

    @Test fun cancellingTheCartCardLeavesTheCartUntouched() = runTest {
        val phone = FakePhone({ cartScreen(1) })
        val host = Host(confirm = false)
        val plan = cartPlanJson(Triple("商品1", "delete", null))
        val r = flow(FakeModel(listOf(plan)), phone, host).run(ShoppingTask(null, 1, true, "把商品1删掉"))
        assertThat(r).isInstanceOf(ShoppingResult.Cancelled::class.java)
        assertThat(phone.executed).isEmpty()
    }

    @Test fun anEmptyPlanSkipsEditingAndChecksOut() = runTest {
        val phone = FakePhone({ if (it < 3) cartScreen(1) else orderScreen() })
        val host = Host()
        val r = flow(FakeModel(listOf(cartPlanJson(), proceed, doneOk)), phone, host).run(ShoppingTask(null, 1, true, "随便改改"))
        assertThat(r).isInstanceOf(ShoppingResult.Handoff::class.java)
        assertThat(host.confirmed).isEmpty()
    }

    @Test fun productFlowSearchesByLinkThenChecksOut() = runTest {
        // 加购循环：搜索页 → 点加购 → 购物车页（自查通过）；之后结算
        val phone = FakePhone({ if (it < 2) cartScreen(1) else if (it < 4) cartScreen(1) else orderScreen() })
        val model = FakeModel(listOf(stepJson("tap", index = 3), stepJson("done", success = true, result = "已在购物车"), verifyJson(true), proceed, doneOk))
        val r = flow(model, phone, Host()).run(ShoppingTask("USB-C 数据线", 2, false, null))
        assertThat(r).isInstanceOf(ShoppingResult.Handoff::class.java)
        assertThat(phone.amazonUrls.first()).isEqualTo("https://www.amazon.com/s?k=USB-C%20%E6%95%B0%E6%8D%AE%E7%BA%BF")
    }

    @Test fun checkoutLoopNeverProceedsPastTheOrderPage() = runTest {
        // 模型想点 Place your order：页面一到提交订单页，循环本地核对通过就结束，不会再有点击
        val phone = FakePhone({ orderScreen() })
        val r = flow(FakeModel(listOf(stepJson("tap", index = 1))), phone, Host()).run(ShoppingTask(null, 1, true, null))
        assertThat(r).isInstanceOf(ShoppingResult.Handoff::class.java)
        assertThat(phone.executed).isEmpty()
    }

    @Test fun amazonLinksAreOnlyAmazonDotCom() {
        assertThat(AmazonIntent.args(AmazonIntent.CART_URL).toList()).containsAtLeast("-p", AppCatalog.AMAZON, "-d", "https://www.amazon.com/gp/cart/view.html")
    }
}

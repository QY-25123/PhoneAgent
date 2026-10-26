package com.android.phoneagent.agent

import com.android.phoneagent.perception.ScreenState

/** DoorDash 任务：哪家店、点什么、几份。agent 永不下单，停在结账页（能看到「Place Order」）交给用户。 */
data class DoordashTask(val restaurant: String, val item: String, val quantity: Int) {
    fun restatement(): String = "DoorDash：在「$restaurant」点 ${if (quantity > 1) "$quantity 份" else ""}$item，到结账页，下单和付款由你来"
}

object DoordashReader {
    private val TOTAL = Regex("Total\\s*\\$\\s*([\\d,]+\\.\\d\\d)", RegexOption.IGNORE_CASE)

    private fun blob(s: ScreenState) = s.elements.joinToString("\n") { listOfNotNull(it.text, it.desc, it.mergedText).joinToString(" ") }

    /** 结账页：能看到「Place Order」按钮。感知只读，绝不点它（硬拦截 + 页面级锁定）。 */
    fun isOrderPage(s: ScreenState): Boolean =
        s.packageName == AppCatalog.DOORDASH && s.elements.any { (it.text ?: it.desc ?: it.mergedText)?.contains("place order", ignoreCase = true) == true }

    /** 结账页上的总价（「Total $xx.xx」）。 */
    fun orderSummary(s: ScreenState): String =
        TOTAL.findAll(blob(s)).lastOrNull()?.groupValues?.get(1)?.let { "订单总价 \$$it" } ?: ""
}

private const val DOORDASH_RULES = "绝不点击「Place Order」或任何下单/付款按钮，绝不点 DashPass 的「Try / Start / Join」按钮（会订阅并扣费，被拦截就换别的路，不要反复尝试）；弹出 DashPass 推广时点「Not now / 关闭」。"

/** 找到餐厅、选好菜品和规格，加入购物车，停在购物车。 */
class DoordashAddTask(private val t: DoordashTask) : LoopTask {
    override val taskText = "在 DoorDash 的「${t.restaurant}」点「${t.item}」（${t.quantity} 份）加入购物车，然后打开购物车确认"
    override val confirmed = "餐厅：${t.restaurant}；菜品：${t.item}；份数：${t.quantity}；$DOORDASH_RULES"
    override val leftovers = listOf("DoorDash 购物车里可能已经加入了「${t.item}」，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.DOORDASH_ADD_SECTION)
    override val verifySystem = Prompts.VERIFY_DOORDASH_CART_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.openApp("doordash")
    override val prelaunchOkNote = "在虚拟屏打开了 DoorDash"
    override val prelaunchFailNote = "尝试打开 DoorDash"
}

/** 从购物车走到结账页并停下。 */
class DoordashCheckoutTask(private val t: DoordashTask) : LoopTask {
    override val taskText = "在 DoorDash 里从购物车一路走到结账页（能看到「Place Order」按钮），停在那里（不下单）"
    override val confirmed = "餐厅：${t.restaurant}；菜品：${t.item}；份数：${t.quantity}；$DOORDASH_RULES"
    override val leftovers = listOf("DoorDash 购物车里有商品，页面可能停在结账流程中，没有下单")
    override val stepSystem = Prompts.stepSystem(Prompts.DOORDASH_CHECKOUT_SECTION)
    override val verifySystem = Prompts.VERIFY_DOORDASH_ORDER_PAGE_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = ActionOutcome(true, "ok")
    override val prelaunchOkNote = "当前应该就在 DoorDash 购物车页"
    override val prelaunchFailNote = ""
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? =
        if (DoordashReader.isOrderPage(screen)) true to ("已到结账页，${DoordashReader.orderSummary(screen)}".trimEnd('，', ' ')) else false to "还没有到结账页"
    override fun alreadyDone(screen: ScreenState) = DoordashReader.isOrderPage(screen)
    override val fastClaim = "已到结账页"
}

interface DoordashHost : LoopHost {
    fun say(text: String)
}

sealed interface DoordashResult {
    /** 已停在结账页，交给用户付款 */
    data class Handoff(val summary: String) : DoordashResult
    data class Failed(val reason: String, val leftovers: List<String>) : DoordashResult
}

/** DoorDash：加购 → 去结账 → 停在结账页交接。不下单、不订阅由特权侧硬拦截保证（SafetyGuard）；提示词只是第二道。 */
class DoordashFlow(
    private val model: ModelClient,
    private val phone: PhoneController,
    private val host: DoordashHost,
    private val cfg: LoopConfig = LoopConfig(),
    private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val log: RunLog = NoopRunLog,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private fun loop() = AgentLoop(model, phone, host, cfg, clock, log, sleep)

    suspend fun run(task: DoordashTask): DoordashResult {
        val add = loop().run(DoordashAddTask(task))
        if (add is LoopResult.Failure) return DoordashResult.Failed(add.reason, add.leftovers)
        return when (val r = loop().run(DoordashCheckoutTask(task))) {
            is LoopResult.Success -> DoordashResult.Handoff(r.evidence.ifBlank { "已到结账页" })
            is LoopResult.Failure -> DoordashResult.Failed(r.reason, r.leftovers)
        }
    }
}

package com.android.phoneagent.agent

import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.ScreenState
import org.json.JSONObject
import java.net.URLEncoder

/** Amazon 任务：买什么（或「用购物车里现有的」）、数量、用户明确要求的购物车改动。agent 永不付款，停在提交订单页。 */
data class ShoppingTask(val product: String?, val quantity: Int, val useCart: Boolean, val cartRequest: String?) {
    fun restatement(): String = if (useCart) "Amazon：购物车结算，到提交订单页，付款由你来"
    else "Amazon：搜「$product」加购（×$quantity），到提交订单页，付款由你来"
}

object AmazonIntent {
    fun searchUrl(q: String) = "https://www.amazon.com/s?k=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
    const val CART_URL = "https://www.amazon.com/gp/cart/view.html"
    fun args(url: String) = arrayOf("-a", "android.intent.action.VIEW", "-p", AppCatalog.AMAZON, "-d", url)
}

/** 从购物车页读出的信息。 */
class CartInfo(val itemCount: Int?, val titles: List<String>)

object CartReader {
    private val SUBTOTAL = Regex("Subtotal \\((\\d+) items?\\)")
    fun read(s: ScreenState): CartInfo {
        if (s.packageName != AppCatalog.AMAZON) return CartInfo(null, emptyList())
        val blob = s.elements.joinToString("\n") { listOfNotNull(it.text, it.desc, it.mergedText).joinToString(" ") }
        val n = SUBTOTAL.find(blob)?.groupValues?.get(1)?.toIntOrNull() ?: if (blob.contains("Your Amazon Cart is empty", ignoreCase = true)) 0 else null
        val titles = s.elements.mapNotNull { e -> (e.desc ?: e.text)?.takeIf { it.startsWith("Delete ") && ElementAction.CLICK in e.actions }?.removePrefix("Delete ")?.take(50) }.distinct()
        return CartInfo(n, titles)
    }

    /** 提交订单页：能看到「Place your order」按钮。感知只读，绝不点它（硬拦截 + 页面级锁定）。 */
    fun isOrderPage(s: ScreenState): Boolean =
        s.packageName == AppCatalog.AMAZON && s.elements.any { (it.text ?: it.desc ?: it.mergedText)?.contains("place your order", ignoreCase = true) == true }

    /** 提交订单页上的商品小计和总价（「Items:」「Order total:」后面紧跟的金额）。 */
    fun orderSummary(s: ScreenState): String {
        fun after(label: String): String? {
            val i = s.elements.indexOfFirst { (it.text ?: "").trim().equals(label, ignoreCase = true) }
            return if (i >= 0) s.elements.drop(i + 1).firstOrNull { (it.text ?: "").startsWith("$") }?.text else null
        }
        return listOfNotNull(after("Items:")?.let { "商品小计 $it" }, after("Order total:")?.let { "订单总价 $it" }).joinToString("，")
    }
}

class CartChange(val item: String, val action: String, val quantity: Int?) {
    fun describe() = if (action == "delete") "删除：$item" else "「$item」数量改为 ${quantity ?: "?"}"
}

private const val AMAZON_RULES = "绝不点击「Buy now / Place your order / 1-Click」，绝不点 Prime 的「Try / Join / Submit」订阅按钮（被拦截就换别的路，不要反复尝试）；遇到 Prime 推广页点「No thanks」。"

/** 搜索商品并加入购物车，停在购物车页。 */
class AddToCartTask(private val t: ShoppingTask) : LoopTask {
    override val taskText = "在 Amazon 搜索「${t.product}」并加入购物车（数量 ${t.quantity}），然后停在购物车页"
    override val confirmed = "商品：${t.product}；数量：${t.quantity}"
    override val leftovers = listOf("Amazon 购物车里可能已经加入了「${t.product}」，请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.AMAZON_ADD_SECTION)
    override val verifySystem = Prompts.VERIFY_CART_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.launchAmazon(AmazonIntent.searchUrl(t.product ?: ""))
    override val prelaunchOkNote = "用链接直接打开了 Amazon 的「${t.product}」搜索结果页"
    override val prelaunchFailNote = "尝试直接打开 Amazon 搜索结果页"
}

/** 从购物车页走到提交订单页并停下。 */
class CheckoutTask(private val fromCart: Boolean, private val t: ShoppingTask) : LoopTask {
    override val taskText = "在 Amazon 里从购物车一路走到提交订单页，停在那里（不下单）"
    override val confirmed = "数量应为 ${t.quantity}（用户要求的商品）；$AMAZON_RULES"
    override val leftovers = listOf("Amazon 购物车里有商品，页面可能停在结算流程中，没有下单")
    override val stepSystem = Prompts.stepSystem(Prompts.AMAZON_CHECKOUT_SECTION)
    override val verifySystem = Prompts.VERIFY_ORDER_PAGE_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = if (fromCart) phone.launchAmazon(AmazonIntent.CART_URL) else ActionOutcome(true, "ok")
    override val prelaunchOkNote = "用链接直接打开了 Amazon 购物车页"
    override val prelaunchFailNote = "尝试直接打开 Amazon 购物车页"
    // 到了提交订单页（看得到 Place your order）就算完成：本地核对，不调用模型
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? =
        if (CartReader.isOrderPage(screen)) true to ("已到提交订单页，${CartReader.orderSummary(screen)}".trimEnd('，', ' ')) else false to "还没有到提交订单页"
    override fun alreadyDone(screen: ScreenState) = CartReader.isOrderPage(screen)
    override val fastClaim = "已到提交订单页"
}

/** 按用户确认过的改动清单修改购物车。 */
class CartEditTask(private val changes: List<CartChange>) : LoopTask {
    private val list = changes.joinToString("\n") { "- " + it.describe() }
    override val taskText = "按用户确认的清单修改 Amazon 购物车"
    override val confirmed = "用户已确认的改动清单（只做这些，别的商品和数量一律不动）：\n$list"
    override val leftovers = listOf("Amazon 购物车可能已被部分修改（清单：${changes.joinToString("；") { it.describe() }}），请检查")
    override val stepSystem = Prompts.stepSystem(Prompts.AMAZON_CART_EDIT_SECTION)
    override val verifySystem = Prompts.VERIFY_CART_EDIT_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.launchAmazon(AmazonIntent.CART_URL)
    override val prelaunchOkNote = "用链接直接打开了 Amazon 购物车页"
    override val prelaunchFailNote = "尝试直接打开 Amazon 购物车页"
}

interface ShoppingHost : LoopHost {
    /** 购物车改动确认卡片：逐项列出改动，等用户确认或取消（true＝确认） */
    suspend fun confirmCartChanges(lines: List<String>): Boolean
    fun say(text: String)
}

sealed interface ShoppingResult {
    /** 已停在提交订单页，交给用户付款 */
    data class Handoff(val summary: String) : ShoppingResult
    data class Cancelled(val note: String) : ShoppingResult
    data class Failed(val reason: String, val leftovers: List<String>) : ShoppingResult
}

/**
 * T3：（搜索并加入购物车）→ 购物车有多件时告知并让用户选择 →（用户明确要求时）改购物车，先确认 → 去结算 → 停在提交订单页交接。
 * 不下单、不订阅由特权侧硬拦截保证（SafetyGuard）；这里的提示词只是第二道。
 */
class ShoppingFlow(
    private val model: ModelClient,
    private val phone: PhoneController,
    private val host: ShoppingHost,
    private val cfg: LoopConfig = LoopConfig(),
    private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val log: RunLog = NoopRunLog,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private fun loop() = AgentLoop(model, phone, host, cfg, clock, log, sleep)

    suspend fun run(task: ShoppingTask): ShoppingResult {
        var request = task.cartRequest
        if (!task.useCart) {
            val r = loop().run(AddToCartTask(task))
            if (r is LoopResult.Failure) return ShoppingResult.Failed(r.reason, r.leftovers)
        } else {
            val open = phone.launchAmazon(AmazonIntent.CART_URL)
            if (!open.ok) phone.openApp("amazon")
        }
        // 购物车里有多件商品：告诉用户结算会包含全部商品，让用户选
        val cart = CartReader.read(phone.perceive().state)
        val n = cart.itemCount
        if (task.useCart && n == 0) return ShoppingResult.Failed("购物车是空的。告诉我想买什么。", emptyList())
        if (request == null && n != null && n >= 2) {
            val names = cart.titles.take(4).joinToString("；")
            val a = host.askUser("结算会包含购物车里的全部 $n 件商品${if (names.isNotEmpty()) "：$names" else ""}。全部结算，还是先调整购物车？", listOf("全部结算", "调整购物车"))
            if (a.contains("调整")) request = host.askUser("想怎么调整？例如「把 XX 删掉」「XX 数量改成 2」。", emptyList())
        }
        if (request != null) {
            val plan = planCartChanges(request) ?: return ShoppingResult.Failed("没看明白要怎么改，购物车没有改动", emptyList())
            if (plan.isEmpty()) host.say("无需改动，去结算。")
            else {
                if (!host.confirmCartChanges(plan.map { it.describe() })) return ShoppingResult.Cancelled("已取消，购物车没有改动。")
                val r = try {
                    phone.armCartChanges(plan)   // 特权侧只放行这组改动
                    loop().run(CartEditTask(plan))
                } finally { runCatching { phone.disarmCartChanges() } }
                if (r is LoopResult.Failure) return ShoppingResult.Failed("修改购物车没能完成：${r.reason}", r.leftovers)
                host.say("购物车已修改：" + plan.joinToString("；") { it.describe() })   // 直接告诉用户做了什么
            }
        }
        val r = loop().run(CheckoutTask(fromCart = true, t = task))
        return when (r) {
            is LoopResult.Success -> ShoppingResult.Handoff(r.evidence.ifBlank { "已到提交订单页" })
            is LoopResult.Failure -> ShoppingResult.Failed(r.reason, r.leftovers)
        }
    }

    /** 让模型把用户的要求落实成逐项改动清单；不确定哪件商品时返回 null 并先问用户（最多问一次）。 */
    private suspend fun planCartChanges(request: String): List<CartChange>? {
        var req = request
        repeat(2) {
            val screen = phone.perceive()
            val r = ModelRequest(
                "cart_plan", Prompts.CART_PLAN_SYSTEM,
                listOf(ModelMessage("user", "用户的要求：$req\n\n当前购物车页面（附截图）：\n${screen.text}", listOfNotNull(screen.state.screenshotJpeg))),
                Schemas.CART_PLAN, 800, "low", Models.step,
            )
            val resp = try { model.complete(r).also { log.modelCall(r, it, null) } } catch (e: ModelException) { log.modelCall(r, null, e.message); return null }
            val o = try { JSONObject(resp.json) } catch (e: Exception) { return null }
            val arr = o.optJSONArray("changes")
            val changes = if (arr == null) emptyList() else List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                CartChange(c.optString("item"), c.optString("action"), if (c.isNull("quantity")) null else c.optInt("quantity"))
            }
            val q = if (o.isNull("question")) null else o.optString("question").takeIf { it.isNotBlank() }
            if (q != null && changes.isEmpty()) { req = req + "；补充：" + host.askUser(q, emptyList()); return@repeat }
            return changes
        }
        return null
    }
}

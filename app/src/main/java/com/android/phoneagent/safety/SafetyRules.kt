package com.android.phoneagent.safety

/**
 * 硬拦截的匹配规则（纯字符串，可以脱离设备单元测试）。[SafetyGuard] 用它判断控件，几何和一次性放行逻辑在 SafetyGuard 里。
 * PRD FR-SAFE-1（下单）、FR-SAFE-13（订阅/会员）。宁可错杀（FR-SAFE-4）。
 */
object SafetyRules {
    private val HARD_LABEL = Regex(
        "buy now|立即购买|place your order|提交订单|1-click|one-click|pay now|complete purchase|" +
            // 订阅/会员（FR-SAFE-13）：Amazon 结算途中会弹「Try Prime for Young Adults」，点了会订阅并扣费
            "try prime|join prime|start (your )?(\\d+-day )?(free )?trial|free trial|subscribe|订阅|开通会员|开通 ?prime",
        RegexOption.IGNORE_CASE,
    )
    private val LOCK_LABEL = Regex("place your order|提交订单", RegexOption.IGNORE_CASE)

    // 网页（WebView）里控件的可见文字常常只是「Submit」，真正的含义在 DOM id 上：
    // 实测 Amazon：buy-now-button 的文字是 Submit；Prime 订阅按钮的文字也是 Submit，它的父节点 id 是 prime-signup-button / stripe-cta
    private val HARD_ID = Regex(
        "buy-?now|place-?(your-?)?order|submit-?order|one-?click|1-?click|prime-?sign-?up|stripe-?cta|prime-?upsell",
        RegexOption.IGNORE_CASE,
    )
    private val LOCK_ID = Regex("place-?(your-?)?order|submit-?order", RegexOption.IGNORE_CASE)

    /** Gmail 写邮件页的「发送」按钮：包名是 Gmail，且 id 以 :id/send 结尾或文字/描述是 Send/发送（FR-SAFE-12）。 */
    fun isGmailSend(pkg: String?, id: String?, label: String): Boolean {
        if (pkg != "com.google.android.gm") return false
        val l = label.trim()
        return id?.endsWith(":id/send") == true || l.equals("send", ignoreCase = true) || l == "发送"
    }

    private val SEND_LABEL = Regex("^(send|发送)(\\s+(send|发送))*$", RegexOption.IGNORE_CASE)

    /** 微信聊天页的「发送」按钮（文字/描述是 Send / 发送）。点它之前必须有内容绑定的确认令牌。 */
    fun isWechatSend(pkg: String?, label: String) = pkg == "com.tencent.mm" && SEND_LABEL.matches(label.trim())

    // Google 地图是只读任务：开始导航、保存地点/行程一律不允许
    private val MAPS_FORBIDDEN = Regex("^((start|navigate|start navigation|begin)(\\s+(start|navigate|start navigation|begin))*|save\\b.*|开始|导航)$", RegexOption.IGNORE_CASE)
    fun isMapsForbidden(pkg: String?, label: String) = pkg == "com.google.android.apps.maps" && MAPS_FORBIDDEN.matches(label.trim())

    /** Amazon 购物车上改变购物车内容的控件（删除、数量加减、Save for later、Move to cart）。 */
    fun isCartEdit(pkg: String?, label: String) =
        pkg == "com.amazon.mShop.android.shopping" && CartControls.parse(label) != null

    fun isHardLabel(label: String) = HARD_LABEL.containsMatchIn(label)
    fun isHardId(id: String?) = id != null && HARD_ID.containsMatchIn(id)
    fun isLockLabel(label: String) = LOCK_LABEL.containsMatchIn(label)
    fun isLockId(id: String?) = id != null && LOCK_ID.containsMatchIn(id)
}

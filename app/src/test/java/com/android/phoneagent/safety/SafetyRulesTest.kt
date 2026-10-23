package com.android.phoneagent.safety

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafetyRulesTest {
    @Test fun orderButtonsAreHardByLabelAndId() {
        for (l in listOf("Buy Now", "Buy now with 1-Click", "立即购买", "Place your order", "Place your order and pay", "提交订单")) assertThat(SafetyRules.isHardLabel(l)).isTrue()
        for (id in listOf("buy-now-button", "placeOrder", "submitOrderButton", "one-click-button")) assertThat(SafetyRules.isHardId(id)).isTrue()
    }

    @Test fun subscriptionButtonsAreHardByLabel() {
        for (l in listOf("Try Prime for Young Adults", "Join Prime", "Start your free trial", "Start 7-day free trial", "Subscribe", "订阅", "开通会员")) {
            assertThat(SafetyRules.isHardLabel(l)).isTrue()
        }
    }

    /** M1 发现：Amazon 的 Prime 订阅按钮文字只是「Submit」，真正的含义在父节点 id 上 */
    @Test fun primeSignupButtonsAreHardByResourceId() {
        for (id in listOf("prime-signup-button", "prime-signup-form", "stripe-cta", "com.amazon.mShop.android.shopping:id/primeSignup")) {
            assertThat(SafetyRules.isHardId(id)).isTrue()
        }
        assertThat(SafetyRules.isHardLabel("Submit")).isFalse()   // 光看文字拦不住，所以必须按 id
    }

    @Test fun harmlessControlsAreNotHard() {
        for (l in listOf("No thanks", "Proceed to checkout", "Add to Cart", "Quantity is 1", "Subtotal (1 item): \$7.99", "Save", "Send", "Cancel", "Back to cart")) {
            assertThat(SafetyRules.isHardLabel(l)).isFalse()
        }
        for (id in listOf("prime-decline-button", "quantity", "add-to-cart-button", "com.google.android.gm:id/send", null)) assertThat(SafetyRules.isHardId(id)).isFalse()
    }

    @Test fun onlyTheOrderPageIsLocked() {
        assertThat(SafetyRules.isLockLabel("Place your order")).isTrue()
        assertThat(SafetyRules.isLockId("placeOrder")).isTrue()
        // 订阅按钮只做控件级拦截，不做页面级锁定（页面上还有「No thanks」要点）
        assertThat(SafetyRules.isLockLabel("Try Prime")).isFalse()
        assertThat(SafetyRules.isLockId("prime-signup-button")).isFalse()
    }

    // ---- Gmail 发送按钮的硬拦截（FR-SAFE-12）：按资源 id、文字、描述任一匹配 ----
    @Test fun gmailSendButtonIsRecognizedByIdLabelOrDescription() {
        val gm = "com.google.android.gm"
        assertThat(SafetyRules.isGmailSend(gm, "com.google.android.gm:id/send", "")).isTrue()
        assertThat(SafetyRules.isGmailSend(gm, null, "Send")).isTrue()
        assertThat(SafetyRules.isGmailSend(gm, null, " send ")).isTrue()
        assertThat(SafetyRules.isGmailSend(gm, null, "发送")).isTrue()
        assertThat(SafetyRules.isGmailSend(gm, "x:id/Send", "Anything")).isFalse()   // id 要精确以 :id/send 结尾（大小写敏感）
    }

    @Test fun sendButtonRuleIsLimitedToGmail() {
        assertThat(SafetyRules.isGmailSend("com.google.android.calendar", null, "Send")).isFalse()
        assertThat(SafetyRules.isGmailSend(null, "a:id/send", "Send")).isFalse()
        assertThat(SafetyRules.isGmailSend("com.google.android.gm", null, "Send money")).isFalse()
        assertThat(SafetyRules.isGmailSend("com.google.android.gm", null, "Attach file")).isFalse()
    }
}

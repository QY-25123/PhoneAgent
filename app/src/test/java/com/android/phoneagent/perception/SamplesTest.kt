package com.android.phoneagent.perception

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 基于真机录制、脱敏后的样本（src/test/resources/perception，原始数据只在本机 local-samples/，不进 Git）。
 * 验收：每个页面的关键元素都必须出现在结果里。
 */
class SamplesTest {
    private fun load(name: String): Extraction {
        val json = javaClass.classLoader!!.getResourceAsStream("perception/$name.snap.json")!!.readBytes().decodeToString()
        return ElementExtractor.extract(RawSnapshot.parse(json))
    }

    private fun label(e: UiElement) = listOfNotNull(e.text, e.desc, e.hint, e.mergedText).joinToString(" | ")

    private fun List<UiElement>.find(type: String? = null, contains: String? = null, res: String? = null, action: ElementAction? = null): UiElement? =
        firstOrNull { e ->
            (type == null || e.type == type) && (contains == null || label(e).contains(contains, ignoreCase = true)) &&
                (res == null || e.resourceId?.endsWith(res) == true) && (action == null || action in e.actions)
        }

    private fun List<UiElement>.require(type: String? = null, contains: String? = null, res: String? = null, action: ElementAction? = null): UiElement =
        find(type, contains, res, action) ?: error("缺少元素 type=$type contains=$contains res=$res action=$action\n" +
            joinToString("\n") { ScreenSerializer.line(it) })

    // ---- 日历 ----
    @Test fun calendarMain() {
        val r = load("cal_main")
        assertThat(r.packageName).isEqualTo("com.google.android.calendar")
        r.elements.require("ImageButton", "Create new event", action = ElementAction.CLICK)   // 新建入口
        r.elements.require("Button", "Jump to Today")
        r.elements.require("Button", "Show date picker")
        r.elements.require("Button", "Search")
        r.elements.require("Scrollable")
    }

    @Test fun calendarNewEvent() {
        val r = load("cal_new_event")
        val title = r.elements.require("EditText", "Add title", action = ElementAction.EDIT)
        assertThat(title.focused).isTrue()
        r.elements.require("Button", "Save", action = ElementAction.CLICK)
        r.elements.require("Button", "Cancel")
        r.elements.require("Button", "Start date")
        r.elements.require("Button", "Start time")
        r.elements.require("Button", "End time")
        r.elements.require("Switch", "")   // 全天开关
    }

    // ---- Gmail ----
    @Test fun gmailInbox() {
        val r = load("gm_inbox")
        r.elements.require("Button", "Compose", action = ElementAction.CLICK)
        r.elements.require("Button", "Search in mail")
        r.elements.require("Scrollable", res = "thread_list_view")
        val rows = r.elements.filter { it.type == "Button" && ElementAction.LONG_CLICK in it.actions }
        assertThat(rows.size).isAtLeast(5)   // 邮件行
    }

    @Test fun gmailCompose() {
        val r = load("gm_compose")
        r.elements.require("EditText", "To", action = ElementAction.EDIT)        // 收件人框（同一行左边的「To」借来当标签）
        r.elements.require("EditText", "Subject", action = ElementAction.EDIT)   // 主题框
        val body = r.elements.require("EditText", "Compose email", action = ElementAction.EDIT)   // 正文框
        assertThat(body.text).isEqualTo("Compose email")
        r.elements.require("Button", "Send", action = ElementAction.CLICK)       // 发送按钮
        r.elements.require("Button", "Attach file")
    }

    // ---- Amazon ----
    @Test fun amazonCart() {
        val r = load("amz_cart")
        r.elements.require("Button", "Proceed to checkout", action = ElementAction.CLICK)
        // 数量：只有一个控件的描述里有「Quantity is 1」，agent 和 M4 验证都靠它读数量
        r.elements.require(contains = "Quantity is 1")
        r.elements.require(contains = "Subtotal (1 item): \$7.99")
        r.elements.require("Button", "Delete", action = ElementAction.CLICK)
    }

    @Test fun amazonPrimePopupAfterProceed() {
        val r = load("amz_after_proceed")
        r.elements.require("Button", "No thanks", action = ElementAction.CLICK)
        r.elements.require("Button", "Submit")   // 订阅按钮：感知照常列出，拦截是 safety 的事
    }

    /** M1 修复：两个文字只是「Submit」的 Prime 订阅按钮，必须带上能看出真实含义的线索（资源 id） */
    @Test fun primeSubscribeButtonsExposeTheirMeaning() {
        val popup = load("amz_after_proceed").elements.filter { it.text == "Submit" }
        assertThat(popup).isNotEmpty()
        for (e in popup) assertThat(e.idHint).contains("prime-signup")
        val cart = load("amz_cart").elements.filter { it.text == "Submit" }
        assertThat(cart).isNotEmpty()
        for (e in cart) assertThat(e.idHint).contains("stripe-cta")
        for (e in popup + cart) assertThat(ScreenSerializer.line(e)).contains("#${e.idHint}")
    }

    /** M1 修复：新建事项面板盖住了下面的日历工具栏，被盖住的元素不应该出现 */
    @Test fun elementsCoveredByTheNewEventSheetAreNotListed() {
        val labels = load("cal_new_event").elements.map { label(it) }
        assertThat(labels.none { it.contains("Jump to Today") || it.contains("Show date picker") }).isTrue()
    }

    @Test fun clickableLabelsAreAtMostEightyCharsPlusMarker() {
        for (name in all) for (e in load(name).elements) {
            if (ElementAction.CLICK in e.actions) {
                for (t in listOfNotNull(e.text, e.desc)) assertThat(t.length).isAtMost(80 + "…(共1000字)".length)
            }
        }
    }

    @Test fun amazonCheckout() {
        val r = load("amz_checkout")
        r.elements.require("Button", "Place your order", action = ElementAction.CLICK)   // 感知不过滤它
        r.elements.require("Text", "Order total:")
        r.elements.require("Text", "\$16.44")
        r.elements.require("Text", "Items:")
        r.elements.require("Button", "Change payment method")
    }

    // ---- 所有样本的共同性质 ----
    private val all = listOf("cal_main", "cal_new_event", "gm_inbox", "gm_compose", "amz_cart", "amz_after_proceed", "amz_checkout")

    @Test fun numberingIsContiguousSortedAndIdsUnique() {
        for (name in all) {
            val els = load(name).elements
            assertThat(els).isNotEmpty()
            assertThat(els.map { it.index }).containsExactlyElementsIn((1..els.size).toList()).inOrder()
            assertThat(els.map { it.stableId }.toSet()).hasSize(els.size)
            assertThat(els.map { it.bounds.top }).isInOrder()
        }
    }

    @Test fun elementsStayOnScreenAndHaveSomethingToSay() {
        for (name in all) for (e in load(name).elements) {
            assertThat(e.bounds.left).isAtLeast(0); assertThat(e.bounds.top).isAtLeast(0)
            assertThat(e.bounds.right).isAtMost(1080); assertThat(e.bounds.bottom).isAtMost(2400)
            assertThat(e.actions.isNotEmpty() || label(e).isNotEmpty()).isTrue()
        }
    }

    @Test fun labelsRespectLengthLimits() {
        for (name in all) for (e in load(name).elements) {
            assertThat(e.mergedText?.length ?: 0).isAtMost(80 + "…(共1000字)".length)
            assertThat(e.text?.length ?: 0).isAtMost(200 + "…(共1000字)".length)
        }
    }

    @Test fun serializationIsStableAndOneLinePerElement() {
        for (name in all) {
            val x = load(name)
            val s = ScreenState(2, x.packageName, x.pageTitle, 1080, 2400, 0, x.elements)
            val text = ScreenSerializer.toText(s)
            assertThat(text.trim().lines()).hasSize(x.elements.size + 1)
            assertThat(ScreenSerializer.toText(s)).isEqualTo(text)
        }
    }

    @Test fun extractionIsFastOnTheJvm() {
        load("amz_cart")   // 预热
        val t0 = System.nanoTime()
        repeat(10) { load("amz_cart") }
        val perRunMs = (System.nanoTime() - t0) / 10 / 1_000_000
        assertThat(perRunMs).isLessThan(200)   // 最大的样本（434 个节点）；设备上的实测见 docs/perception-samples.md
    }
}

package com.android.phoneagent.perception

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElementExtractorTest {
    private fun extract(vararg ws: RawWindow) = ElementExtractor.extract(snap(*ws))

    @Test
    fun keepsOnlyVisibleOnScreenInterestingNodes() {
        val r = extract(window(listOf(
            node(0, "android.widget.FrameLayout", FULL),                                  // 无标签、无操作的容器
            node(1, b = listOf(10, 100, 500, 160), text = "可见文字"),
            node(1, b = listOf(10, 200, 500, 260), text = "不可见", vis = false),
            node(1, b = listOf(10, 2500, 500, 2560), text = "屏幕外"),                    // 与屏幕无交集
            node(1, b = listOf(10, 300, 10, 360), text = "零宽"),
            node(1, "android.widget.Button", b = listOf(10, 400, 300, 480), clk = true),   // 无文字的可点击：保留
            node(1, b = listOf(10, 500, 500, 560), text = "   "),                         // 空白文字：丢弃
        )))
        assertThat(r.elements.map { it.text }).containsExactly("可见文字", null).inOrder()
        assertThat(r.elements[1].type).isEqualTo("Button")
    }

    @Test
    fun clipsBoundsToScreenAndKeepsPartiallyVisible() {
        val r = extract(window(listOf(node(0, b = listOf(900, 2300, 1300, 2700), text = "半截", clk = true))))
        assertThat(r.elements.single().bounds).isEqualTo(Bounds(900, 2300, 1080, 2400))
    }

    @Test
    fun scrollableContainerIsKeptWithDirections() {
        val r = extract(window(listOf(node(0, "androidx.recyclerview.widget.RecyclerView", FULL, scr = true, sf = true))))
        val e = r.elements.single()
        assertThat(e.type).isEqualTo("Scrollable")
        assertThat(e.actions).containsExactly(ElementAction.SCROLL_FORWARD)
    }

    @Test
    fun clickableParentAbsorbsPlainTextChildrenAsLabel() {
        val r = extract(window(listOf(
            node(0, "android.view.ViewGroup", listOf(0, 100, 1080, 300), res = "app:id/row", clk = true),
            node(1, b = listOf(20, 110, 500, 160), text = "Alice"),
            node(1, b = listOf(20, 170, 900, 220), text = "周五评审改到下午三点"),
        )))
        val e = r.elements.single()
        assertThat(e.mergedText).isEqualTo("Alice 周五评审改到下午三点")
        assertThat(e.text).isNull()
        assertThat(e.actions).containsExactly(ElementAction.CLICK)
    }

    @Test
    fun parentWithOwnLabelDropsChildrenWithoutMerging() {
        val r = extract(window(listOf(
            node(0, "android.view.ViewGroup", listOf(0, 100, 1080, 300), desc = "来自 Alice 的邮件", clk = true),
            node(1, b = listOf(20, 110, 500, 160), text = "Alice"),
        )))
        val e = r.elements.single()
        assertThat(e.desc).isEqualTo("来自 Alice 的邮件")
        assertThat(e.mergedText).isNull()
    }

    @Test
    fun mergedLabelIsTruncatedAndMarked() {
        val long = "字".repeat(120)
        val r = extract(window(listOf(
            node(0, "android.view.ViewGroup", listOf(0, 100, 1080, 300), clk = true),
            node(1, b = listOf(20, 110, 500, 160), text = long),
        )))
        val m = r.elements.single().mergedText!!
        assertThat(m).startsWith("字".repeat(80))
        assertThat(m).endsWith("…(共120字)")
    }

    @Test
    fun ownTextIsTruncatedAtLimit() {
        val r = ElementExtractor.extract(
            snap(window(listOf(node(0, b = listOf(0, 0, 500, 100), text = "x".repeat(300))))),
            ExtractOptions(maxOwnTextChars = 200),
        )
        assertThat(r.elements.single().text).endsWith("…(共300字)")
    }

    @Test
    fun hugeClickableContainerDoesNotSwallowTheWholePage() {
        val r = extract(window(listOf(
            node(0, "android.view.View", FULL, clk = true),
            node(1, b = listOf(20, 110, 500, 160), text = "标题"),
            node(1, "android.widget.Button", b = listOf(20, 300, 500, 400), text = "继续", clk = true),
        )))
        assertThat(r.elements.map { it.text }).containsExactly(null, "标题", "继续")
    }

    @Test
    fun childWithOwnActionIsNotAbsorbed() {
        val r = extract(window(listOf(
            node(0, "android.view.ViewGroup", listOf(0, 100, 1080, 300), clk = true),
            node(1, "android.widget.Button", b = listOf(20, 110, 300, 200), text = "删除", clk = true),
        )))
        assertThat(r.elements.map { it.text }).contains("删除")
    }

    @Test
    fun upperWindowOccludesLowerElements() {
        val r = extract(
            window(listOf(
                node(0, "android.widget.Button", b = listOf(100, 1000, 500, 1100), text = "被盖住", clk = true),
                node(0, "android.widget.Button", b = listOf(100, 200, 500, 300), text = "没被盖住", clk = true),
            ), layer = 1),
            window(listOf(
                node(0, "android.widget.Button", b = listOf(300, 1000, 700, 1200), text = "弹窗按钮", clk = true),
            ), layer = 2, type = 2, b = listOf(200, 900, 900, 1300)),
        )
        assertThat(r.elements.map { it.text }).containsExactly("没被盖住", "弹窗按钮")
    }

    @Test
    fun duplicateNodesMergeActionsAndKeepResourceId() {
        val r = extract(window(listOf(
            node(0, "android.view.ViewGroup", listOf(0, 0, 500, 100), text = "保存", clk = true),
            node(1, "android.widget.Button", listOf(0, 0, 500, 100), res = "app:id/save", text = "保存", clk = true),
        )))
        val e = r.elements.single()
        assertThat(e.resourceId).isEqualTo("app:id/save")
    }

    @Test
    fun numberingIsTopToBottomThenLeftToRight() {
        val r = extract(window(listOf(
            node(0, b = listOf(600, 100, 1000, 160), text = "右上"),
            node(0, b = listOf(10, 100, 500, 160), text = "左上"),
            node(0, b = listOf(10, 50, 500, 90), text = "最上"),
            node(0, b = listOf(10, 800, 500, 860), text = "下面"),
        )))
        assertThat(r.elements.map { it.text }).containsExactly("最上", "左上", "右上", "下面").inOrder()
        assertThat(r.elements.map { it.index }).containsExactly(1, 2, 3, 4).inOrder()
    }

    @Test
    fun typeMapping() {
        val r = extract(window(listOf(
            node(0, "android.widget.EditText", listOf(0, 0, 500, 80), hint = "主题", edit = true),
            node(0, "android.widget.CheckBox", listOf(0, 100, 500, 180), text = "同意", chkable = true, clk = true),
            node(0, "android.widget.ImageButton", listOf(0, 200, 100, 300), desc = "更多", clk = true),
            node(0, "android.widget.ImageView", listOf(0, 400, 100, 500), desc = "头像"),
            node(0, "android.widget.TextView", listOf(0, 600, 500, 680), text = "说明"),
        )))
        assertThat(r.elements.map { it.type }).containsExactly("EditText", "Checkbox", "ImageButton", "Image", "Text").inOrder()
        assertThat(r.elements[1].checked).isFalse()
    }

    @Test
    fun packageAndTitleComeFromTopApplicationWindow() {
        val r = extract(
            window(listOf(node(0, b = listOf(0, 0, 100, 100), text = "a")), layer = 1, pkg = "com.google.android.gm", title = "Mail"),
            window(listOf(node(0, b = listOf(0, 0, 100, 100), text = "b")), layer = 2, type = 4, pkg = "android", title = null),
        )
        assertThat(r.packageName).isEqualTo("com.google.android.gm")
        assertThat(r.pageTitle).isEqualTo("Mail")
    }

    @Test
    fun emptyWebViewIsDetected() {
        val shell = extract(window(listOf(node(0, "android.webkit.WebView", FULL))))
        assertThat(shell.webViewEmpty).isTrue()
        val loaded = extract(window(listOf(
            node(0, "android.webkit.WebView", FULL),
            node(1, "android.view.View", listOf(0, 0, 500, 100), text = "Cart", clk = true),
        )))
        assertThat(loaded.webViewEmpty).isFalse()
    }

    @Test
    fun emptySnapshotGivesNoElements() {
        val r = extract()
        assertThat(r.elements).isEmpty()
        assertThat(r.packageName).isNull()
    }

    @Test
    fun parsesJsonFromUserService() {
        val json = """{"version":1,"display":2,"time":5,"truncated":true,"windows":[{"id":7,"type":1,"layer":3,"title":null,
            "focused":true,"active":true,"b":[0,0,1080,2400],"pkg":"com.example","nodeCount":2,"nodes":[
            {"d":0,"cls":"android.widget.FrameLayout","b":[0,0,1080,2400],"vis":true},
            {"d":1,"cls":"android.widget.Button","b":[10,20,300,120],"res":"app:id/ok","text":"确定","vis":true,"clk":true,"foc":true}]}]}"""
        val s = RawSnapshot.parse(json)
        assertThat(s.truncated).isTrue()
        val w = s.windows.single()
        assertThat(w.title).isNull()
        assertThat(w.nodes[1].text).isEqualTo("确定")
        assertThat(w.nodes[1].clickable).isTrue()
        assertThat(w.nodes[1].bounds).isEqualTo(Bounds(10, 20, 300, 120))
        val r = ElementExtractor.extract(s)
        assertThat(r.truncatedTree).isTrue()
        assertThat(r.elements.single().focused).isTrue()
    }

    @Test
    fun emptyCommaRunsInDescriptionsAreCollapsed() {
        val r = extract(window(listOf(node(0, "android.view.ViewGroup", listOf(0, 0, 1080, 200), desc = "Unread, , , Telegram, , Your Code", clk = true))))
        assertThat(r.elements.single().desc).isEqualTo("Unread, Telegram, Your Code")
    }

    @Test
    fun labelledNonActionableViewIsText() {
        val r = extract(window(listOf(node(0, "android.view.View", listOf(0, 0, 500, 100), desc = "Mail, 99 new"))))
        assertThat(r.elements.single().type).isEqualTo("Text")
    }

    @Test
    fun placeholderTextIsAHintNotContent() {
        val r = extract(window(listOf(node(0, "android.widget.EditText", listOf(0, 0, 900, 100), text = "Subject", sh = true, edit = true))))
        val e = r.elements.single()
        assertThat(e.text).isNull()
        assertThat(e.hint).isEqualTo("Subject")
    }

    @Test
    fun unlabeledEditableBorrowsLabelFromSameRow() {
        val r = extract(window(listOf(
            node(0, "android.widget.Button", listOf(0, 300, 120, 400), text = "To", clk = true),
            node(0, "android.widget.EditText", listOf(130, 300, 900, 400), edit = true),
            node(0, "android.widget.Button", listOf(0, 600, 120, 700), text = "离得很远", clk = true),
        )))
        assertThat(r.elements.first { it.type == "EditText" }.hint).isEqualTo("To")
    }
}

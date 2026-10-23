package com.android.phoneagent.perception

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenSerializerTest {
    private fun state(vararg nodes: RawNode, notes: List<String> = emptyList()): ScreenState {
        val x = ElementExtractor.extract(snap(window(nodes.toList(), pkg = "com.google.android.gm", title = "写邮件")))
        return ScreenState(2, x.packageName, x.pageTitle, 1080, 2400, 0, x.elements, notes = notes)
    }

    @Test
    fun headerAndOneLinePerElement() {
        val text = ScreenSerializer.toText(state(
            node(0, "android.widget.EditText", listOf(0, 100, 900, 200), hint = "收件人", edit = true, foc = true),
            node(0, "android.widget.Button", listOf(0, 300, 400, 400), text = "发送", clk = true),
        ))
        val lines = text.trim().lines()
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).isEqualTo("App: com.google.android.gm  标题: 写邮件  屏幕: 1080x2400  元素: 2")
        assertThat(lines[1]).matches("""\[1\|[0-9a-z]{4}\] EditText "收件人" \[edit\] focused""")
        assertThat(lines[2]).matches("""\[2\|[0-9a-z]{4}\] Button "发送" \[click\]""")
    }

    @Test
    fun editableShowsHintAndValue() {
        val l = ScreenSerializer.line(state(
            node(0, "android.widget.EditText", listOf(0, 100, 900, 200), hint = "主题", text = "周五评审", edit = true),
        ).elements.single())
        assertThat(l).contains("\"主题\" = \"周五评审\"")
    }

    @Test
    fun descriptionDifferentFromTextIsShownInParentheses() {
        val l = ScreenSerializer.line(state(
            node(0, "android.widget.ImageButton", listOf(0, 100, 200, 200), text = "更多", desc = "更多选项", clk = true),
        ).elements.single())
        assertThat(l).contains("\"更多\" (更多选项)")
    }

    @Test
    fun unlabeledButtonShowsResourceIdHint() {
        val l = ScreenSerializer.line(state(
            node(0, "android.widget.ImageButton", listOf(0, 100, 200, 200), res = "com.google.android.gm:id/compose_button", clk = true),
        ).elements.single())
        assertThat(l).contains("(无标签)")
        assertThat(l).contains("#compose_button")
    }

    @Test
    fun newlinesAndQuotesAreFlattened() {
        val l = ScreenSerializer.line(state(node(0, b = listOf(0, 0, 500, 100), text = "第一行\n第二行 \"引用\"")).elements.single())
        assertThat(l).doesNotContain("\n")
        assertThat(l).contains("\"第一行 第二行 '引用'\"")
    }

    @Test
    fun checkedStateAndNotesAreIncluded() {
        val text = ScreenSerializer.toText(state(
            node(0, "android.widget.CheckBox", listOf(0, 100, 500, 200), text = "同意", chkable = true, chk = true, clk = true),
            notes = listOf("页面仍在变化"),
        ))
        assertThat(text).contains("提示: 页面仍在变化")
        assertThat(text).contains("checked")
    }

    @Test
    fun emptyTreeStillProducesAHeader() {
        val s = ScreenState(2, null, null, 1080, 2400, 0, emptyList(), treeEmpty = true, notes = listOf("界面树为空"))
        val text = ScreenSerializer.toText(s)
        assertThat(text).startsWith("App: 未知")
        assertThat(text).contains("元素: 0")
        assertThat(text).contains("界面树为空")
    }

    @Test
    fun unlabeledElementWithoutAnyIdIsStillMarked() {
        val l = ScreenSerializer.line(state(node(0, "android.widget.ImageButton", listOf(0, 100, 200, 200), clk = true)).elements.single())
        assertThat(l).contains("(无标签)")
        assertThat(l).doesNotContain("#")
    }

    @Test
    fun overlappingTextIsMarkedAsNotNecessarilyTheButtonsOwn() {
        val l = ScreenSerializer.line(state(
            node(0, "android.widget.Button", listOf(0, 1000, 1000, 1100), text = "Submit", clk = true, res = "app:id/prime-signup-button"),
            node(0, b = listOf(100, 1020, 900, 1080), text = "Continue with Prime"),
        ).elements.first { it.type == "Button" })
        assertThat(l).contains("\"Submit\" (覆盖文字: Continue with Prime)")
        assertThat(l).contains("#prime-signup-button")
    }
}

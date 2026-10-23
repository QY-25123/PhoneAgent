package com.android.phoneagent.perception

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StableIdsTest {
    private fun ids(vararg nodes: RawNode) =
        ElementExtractor.extract(snap(window(nodes.toList()))).elements

    @Test
    fun idSurvivesScrollingAndLayoutShift() {
        val a = ids(node(0, "android.widget.Button", listOf(0, 100, 400, 200), res = "app:id/send", text = "发送", clk = true))
        val b = ids(node(0, "android.widget.Button", listOf(0, 900, 400, 1000), res = "app:id/send", text = "发送", clk = true))
        assertThat(a.single().stableId).isEqualTo(b.single().stableId)
        assertThat(a.single().stableId).hasLength(4)
    }

    @Test
    fun editableIdDoesNotDependOnCurrentContent() {
        val empty = ids(node(0, "android.widget.EditText", listOf(0, 100, 900, 200), hint = "主题", edit = true))
        val typed = ids(node(0, "android.widget.EditText", listOf(0, 100, 900, 200), hint = "主题", text = "周五评审", edit = true))
        assertThat(empty.single().stableId).isEqualTo(typed.single().stableId)
    }

    @Test
    fun identicalElementsGetDistinctIdsInOrder() {
        val r = ids(
            node(0, "android.widget.Button", listOf(0, 100, 400, 200), text = "+", clk = true),
            node(0, "android.widget.Button", listOf(0, 300, 400, 400), text = "+", clk = true),
            node(0, "android.widget.Button", listOf(0, 500, 400, 600), text = "+", clk = true),
        )
        assertThat(r.map { it.stableId }.toSet()).hasSize(3)
    }

    @Test
    fun idsAreUniqueOnALargeScreen() {
        val nodes = (0 until 300).map { i ->
            node(0, "android.widget.TextView", listOf(0, i * 8, 100, i * 8 + 6), text = "项目 $i")
        }
        val r = ElementExtractor.extract(snap(window(nodes)), ExtractOptions(minSizePx = 2)).elements
        assertThat(r.map { it.stableId }.toSet()).hasSize(r.size)
    }

    @Test
    fun idIsDeterministicAcrossRuns() {
        val n = node(0, "android.widget.Button", listOf(0, 100, 400, 200), text = "发送", clk = true)
        assertThat(ids(n).single().stableId).isEqualTo(ids(n).single().stableId)
    }
}

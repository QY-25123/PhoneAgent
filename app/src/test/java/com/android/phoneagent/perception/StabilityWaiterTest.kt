package com.android.phoneagent.perception

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StabilityWaiterTest {
    private fun page(vararg texts: String) = snap(window(
        texts.mapIndexed { i, t -> node(0, b = listOf(0, i * 100, 500, i * 100 + 60), text = t) }
    ))

    private val empty = snap()
    private val webShell = snap(window(listOf(node(0, "android.webkit.WebView", FULL))))

    /** 依次返回 [seq] 里的快照，用完后一直返回最后一个。 */
    private class Seq(val seq: List<RawSnapshot?>) : SnapshotSource {
        var calls = 0
        override suspend fun snapshot(): RawSnapshot? = seq[minOf(calls++, seq.size - 1)]
    }

    private fun kotlinx.coroutines.test.TestScope.waiter() = StabilityWaiter(clock = { currentTime }, sleep = { kotlinx.coroutines.delay(it) })

    @Test fun returnsAfterTwoIdenticalReads() = runTest {
        val src = Seq(listOf(page("a"), page("a", "b"), page("a", "b"), page("zzz")))
        val r = waiter().awaitStable(src)
        assertThat(r.stable).isTrue()
        assertThat(r.reads).isEqualTo(3)
        assertThat(r.extraction.elements.map { it.text }).containsExactly("a", "b").inOrder()
        assertThat(r.notes).isEmpty()
    }

    @Test fun alreadyStablePageReturnsFast() = runTest {
        val r = waiter().awaitStable(Seq(listOf(page("a"))))
        assertThat(r.reads).isEqualTo(2)
        assertThat(r.waitedMs).isAtMost(200)
    }

    @Test fun neverStablePageTimesOutWithNote() = runTest {
        var n = 0
        val src = SnapshotSource { page("计数 ${n++}") }
        val r = waiter().awaitStable(src, opts = StabilityOptions(timeoutMs = 1000))
        assertThat(r.stable).isFalse()
        assertThat(r.waitedMs).isAtMost(1000)
        assertThat(r.notes.single()).contains("仍在变化")
    }

    @Test fun emptyTreeRetriesUntilContentAppears() = runTest {
        val src = Seq(listOf(empty, empty, empty, page("loaded"), page("loaded"), page("loaded")))
        val r = waiter().awaitStable(src)
        assertThat(r.stable).isTrue()
        assertThat(r.extraction.elements.single().text).isEqualTo("loaded")
        assertThat(r.reads).isEqualTo(5)
    }

    @Test fun webViewShellRetriesLongerThanNormalTimeout() = runTest {
        val loaded = snap(window(listOf(
            node(0, "android.webkit.WebView", FULL),
            node(1, b = listOf(0, 0, 500, 60), text = "Cart", clk = true),
            node(1, b = listOf(0, 100, 500, 160), text = "Quantity is 1", clk = true),
            node(1, b = listOf(0, 200, 500, 260), text = "Proceed to checkout", clk = true),
        )))
        // 前 25 次读取（约 3.75 秒，超过普通 3 秒上限）都是空壳，之后内容出现
        val src = Seq(List(25) { webShell } + List(3) { loaded })
        val r = waiter().awaitStable(src)
        assertThat(r.stable).isTrue()
        assertThat(r.extraction.elements).hasSize(3)
    }

    @Test fun permanentWebViewShellGivesUpAtTotalLimitWithNote() = runTest {
        val r = waiter().awaitStable(Seq(listOf(webShell)))
        assertThat(r.extraction.elements).isEmpty()
        assertThat(r.waitedMs).isAtMost(6000)
        assertThat(r.waitedMs).isAtLeast(5800)
        assertThat(r.treeEmpty).isTrue()
    }

    @Test fun emptyWindowListExplainsWhy() = runTest {
        val r = waiter().awaitStable(Seq(listOf(empty)))
        assertThat(r.treeEmpty).isTrue()
        assertThat(r.notes.first()).contains("界面树为空")
    }

    @Test fun failedReadIsReportedNotThrown() = runTest {
        val r = waiter().awaitStable(Seq(listOf(null)), opts = StabilityOptions(emptyRetryTotalMs = 500))
        assertThat(r.snapshot).isNull()
        assertThat(r.notes.first()).contains("读取界面树失败")
    }

    @Test fun truncatedTreeIsNoted() = runTest {
        val r = waiter().awaitStable(Seq(listOf(snap(window(listOf(node(0, b = listOf(0, 0, 500, 60), text = "a"))), truncated = true))))
        assertThat(r.notes).contains("界面节点数量超过上限，已截断")
    }
}

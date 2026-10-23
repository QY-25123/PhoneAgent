package com.android.phoneagent.perception

import kotlinx.coroutines.delay

/** 快照来源（真实实现走 Agent → UserService；测试用假的）。返回 null 表示读取失败/未连接。 */
fun interface SnapshotSource {
    suspend fun snapshot(): RawSnapshot?
}

class StabilityOptions(
    val pollMs: Long = 150,
    /** 连续多少次读取的元素签名一致才算稳定 */
    val stableRounds: Int = 2,
    /** 普通页面等待稳定的上限 */
    val timeoutMs: Long = 3000,
    /** 页面是空壳（界面树为空、WebView 没内容）时继续重试的总上限（从开始算起） */
    val emptyRetryTotalMs: Long = 6000,
    /** 有 WebView 且元素少于这个数时当作空壳 */
    val minElementsWithWebView: Int = 3,
)

class StableResult(
    val snapshot: RawSnapshot?,
    val extraction: Extraction,
    val stable: Boolean,
    val reads: Int,
    val waitedMs: Long,
    val notes: List<String>,
    val treeEmpty: Boolean,
    /** 最后一次读取界面树（含跨进程传输）和筛选的耗时，用来衡量「单次感知」成本（不含等待） */
    val lastReadMs: Long = 0,
    val lastExtractMs: Long = 0,
)

/**
 * 页面稳定检测：每隔 [StabilityOptions.pollMs] 读一次界面树，元素签名连续一致才返回。
 * 只用界面树判断，不用画面哈希：输入框的光标闪烁会让画面永远「在变」。
 * 空壳页面（界面树为空 / WebView 没内容）会用更长的总时限重试。
 */
class StabilityWaiter(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun awaitStable(
        source: SnapshotSource,
        extract: ExtractOptions = ExtractOptions(),
        opts: StabilityOptions = StabilityOptions(),
    ): StableResult {
        val start = clock()
        var reads = 0
        var equalRun = 0
        var prevSig: String? = null
        var last: Pair<RawSnapshot?, Extraction>? = null
        var readMs = 0L
        var extractMs = 0L
        var settleStart = start   // 空壳阶段不计入「等页面稳定」的时限：内容出现后重新开始计时
        while (true) {
            val t0 = clock()
            val snap = source.snapshot()
            val t1 = clock()
            reads++
            val ext = if (snap != null) ElementExtractor.extract(snap, extract) else EMPTY
            readMs = t1 - t0
            extractMs = clock() - t1
            last = snap to ext
            val sig = signature(ext.elements)
            equalRun = if (sig == prevSig) equalRun + 1 else 0
            prevSig = sig
            val elapsed = clock() - start
            val shell = isShell(snap, ext, opts)
            val stableNow = equalRun >= opts.stableRounds - 1 && reads >= opts.stableRounds
            if (stableNow && !shell) return finish(last, true, reads, elapsed, opts, readMs, extractMs)
            if (shell) settleStart = clock()
            val over = if (shell) elapsed + opts.pollMs > opts.emptyRetryTotalMs else clock() - settleStart + opts.pollMs > opts.timeoutMs
            if (over) return finish(last, stableNow, reads, elapsed, opts, readMs, extractMs)
            sleep(opts.pollMs)
        }
    }

    private fun finish(last: Pair<RawSnapshot?, Extraction>, stable: Boolean, reads: Int, waited: Long, opts: StabilityOptions, readMs: Long, extractMs: Long): StableResult {
        val (snap, ext) = last
        val treeEmpty = ext.elements.isEmpty()
        val notes = ArrayList<String>()
        when {
            snap == null -> notes.add("读取界面树失败（虚拟屏未连接？）")
            treeEmpty && snap.windows.isEmpty() -> notes.add("界面树为空：虚拟屏上没有窗口，可能 App 还没启动或正在转场")
            treeEmpty -> notes.add("界面树里没有可见的可操作元素，可能页面还没加载出来")
            ext.webViewEmpty -> notes.add("页面内容可能还没加载出来（WebView 内没有内容）")
            isShell(snap, ext, opts) -> notes.add("页面上的元素很少，可能还在加载")
            !stable -> notes.add("页面仍在变化，结果可能不完整")
        }
        if (ext.truncatedTree) notes.add("界面节点数量超过上限，已截断")
        return StableResult(snap, ext, stable, reads, waited, notes, treeEmpty, readMs, extractMs)
    }

    private fun isShell(snap: RawSnapshot?, ext: Extraction, opts: StabilityOptions): Boolean {
        if (snap == null || ext.elements.isEmpty() || ext.webViewEmpty) return true
        val hasWebView = snap.windows.any { w -> w.nodes.any { it.visible && it.className.contains("WebView") } }
        return hasWebView && ext.elements.size < opts.minElementsWithWebView
    }

    private fun signature(elements: List<UiElement>) = signatureOf(elements)

    companion object {
        /** 元素签名：标识、位置、文字、状态。不含编号（编号随排序变化）。 */
        fun signatureOf(elements: List<UiElement>): String = buildString {
            for (e in elements) {
                append(e.stableId).append('@').append(e.bounds.left).append(',').append(e.bounds.top).append(',')
                    .append(e.bounds.right).append(',').append(e.bounds.bottom).append('|')
                    .append(e.text).append('|').append(e.desc).append('|').append(e.mergedText).append('|')
                    .append(e.focused).append(e.checked).append(e.enabled).append('\n')
            }
        }

        private val EMPTY = Extraction(emptyList(), null, null, webViewEmpty = false, truncatedTree = false)
    }
}

package com.android.phoneagent.perception

import android.graphics.Bitmap

/** 感知的外部来源：界面树快照 + 最新一帧画面。 */
interface PerceptionSource : SnapshotSource {
    /** 虚拟屏最新一帧的全分辨率位图；没有则 null（不会被回收，调用方只读） */
    fun screenshot(): Bitmap?
}

class PerceiveOptions(
    val waitForStable: Boolean = true,
    val annotate: Boolean = true,
    val jpegWidth: Int = 540,   // 给模型的图小一点：更少 token、更快
    val jpegQuality: Int = 65,
    val extract: ExtractOptions = ExtractOptions(),
    val stability: StabilityOptions = StabilityOptions(),
)

/** 各环节耗时（毫秒）。[singlePassMs] ＝ 一次读取 + 筛选 + 截图处理 + 序列化，不含等待页面稳定。 */
class PerceptionTimings(
    val waitMs: Long, val reads: Int, val readMs: Long, val extractMs: Long, val screenshotMs: Long, val serializeMs: Long,
) {
    val singlePassMs get() = readMs + extractMs + screenshotMs + serializeMs
    override fun toString() =
        "wait=${waitMs}ms reads=$reads read=${readMs}ms extract=${extractMs}ms screenshot=${screenshotMs}ms serialize=${serializeMs}ms single-pass=${singlePassMs}ms"
}

class PerceptionResult(val state: ScreenState, val text: String, val timings: PerceptionTimings)

/** 对外入口：给定虚拟屏当前页面，输出 [ScreenState] 和给模型的文本（M2 的执行循环每步调用）。 */
class Perception(
    private val source: PerceptionSource,
    private val waiter: StabilityWaiter = StabilityWaiter(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun perceive(displayId: Int, opts: PerceiveOptions = PerceiveOptions()): PerceptionResult {
        val stab = if (opts.waitForStable) waiter.awaitStable(source, opts.extract, opts.stability)
        else waiter.awaitStable(source, opts.extract, StabilityOptions(stableRounds = 1, timeoutMs = 0, emptyRetryTotalMs = 0))
        val ext = stab.extraction
        val notes = stab.notes.toMutableList()

        val t0 = nowMs()
        var secure = false
        var jpeg: ByteArray? = null
        val frame = source.screenshot()
        if (frame != null) {
            secure = ScreenshotAnnotator.isMostlyBlack(frame)
            if (secure) notes.add("截图全黑，可能是安全窗口（禁止截屏）；界面树仍然有效，请以下面的元素列表为准")
            if (opts.annotate) jpeg = ScreenshotAnnotator.annotate(frame, ext.elements, opts.extract.screenWidth, opts.jpegWidth, opts.jpegQuality, grid = ext.elements.size <= 2).jpeg
        } else if (opts.annotate) {
            notes.add("没有可用的截图")
        }
        val shotMs = nowMs() - t0

        val state = ScreenState(
            displayId = displayId, packageName = ext.packageName, pageTitle = ext.pageTitle,
            screenWidth = opts.extract.screenWidth, screenHeight = opts.extract.screenHeight,
            timestampMs = System.currentTimeMillis(), elements = ext.elements,
            secureWindow = secure, treeEmpty = stab.treeEmpty, notes = notes, screenshotJpeg = jpeg,
        )
        val t1 = nowMs()
        val text = ScreenSerializer.toText(state)
        val timings = PerceptionTimings(stab.waitedMs, stab.reads, stab.lastReadMs, stab.lastExtractMs, shotMs, nowMs() - t1)
        return PerceptionResult(state, text, timings)
    }
}

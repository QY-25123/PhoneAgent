package com.android.phoneagent.perception

/** 截图异常识别（纯函数，输入是网格采样得到的 ARGB 像素）。 */
object ScreenAnomaly {
    /**
     * 几乎全黑：安全窗口（FLAG_SECURE）在虚拟屏上会黑屏。亮度低于 [lumaMax] 的采样点占比 ≥ [ratio] 就判定。
     * 注意纯黑的深色页面也会命中，所以只用来标记「截图不可信」，界面树照常输出。
     */
    fun isMostlyBlack(samples: IntArray, lumaMax: Int = 6, ratio: Double = 0.995): Boolean {
        if (samples.isEmpty()) return false
        var dark = 0
        for (p in samples) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if ((r * 299 + g * 587 + b * 114) / 1000 <= lumaMax) dark++
        }
        return dark.toDouble() / samples.size >= ratio
    }

    /** 从 [pixels]（宽 [w] 高 [h]，行优先）按 [step] 像素间隔取网格采样点。 */
    fun gridSample(pixels: IntArray, w: Int, h: Int, step: Int = 24): IntArray {
        val out = ArrayList<Int>()
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) { out.add(pixels[y * w + x]); x += step }
            y += step
        }
        return out.toIntArray()
    }
}

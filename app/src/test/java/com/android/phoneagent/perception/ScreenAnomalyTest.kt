package com.android.phoneagent.perception

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenAnomalyTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun allBlackIsDetected() {
        assertThat(ScreenAnomaly.isMostlyBlack(IntArray(1000) { black })).isTrue()
    }

    @Test fun normalPageIsNotBlack() {
        assertThat(ScreenAnomaly.isMostlyBlack(IntArray(1000) { if (it % 3 == 0) white else 0xFF202020.toInt() })).isFalse()
    }

    @Test fun darkThemeBackgroundIsNotBlack() {
        assertThat(ScreenAnomaly.isMostlyBlack(IntArray(1000) { 0xFF121212.toInt() })).isFalse()
    }

    @Test fun smallBrightRegionPreventsFalsePositive() {
        // 99% 黑 + 1% 白（例如纯黑页面上的一小块内容）：超过 0.5% 的亮点，不算全黑
        val px = IntArray(1000) { if (it < 10) white else black }
        assertThat(ScreenAnomaly.isMostlyBlack(px)).isFalse()
    }

    @Test fun emptySampleIsNotBlack() {
        assertThat(ScreenAnomaly.isMostlyBlack(IntArray(0))).isFalse()
    }

    @Test fun gridSampleCoversTheImage() {
        val px = IntArray(100 * 50) { white }
        val s = ScreenAnomaly.gridSample(px, 100, 50, step = 10)
        assertThat(s).hasLength(10 * 5)
    }
}

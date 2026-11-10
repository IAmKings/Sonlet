package com.sonlet.app.feature.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/** 计时器上限必须来自采集参数,而不是硬编码(P1-8) */
class FormatTimerTest {

    @Test
    fun zeroDurationFormatsAsZeroOverLimit() {
        assertEquals("00:00 / 00:30", formatTimer(durationMs = 0, limitMs = 30_000))
    }

    @Test
    fun formatsElapsedSecondsWithinLimit() {
        assertEquals("00:12 / 00:30", formatTimer(durationMs = 12_345, limitMs = 30_000))
    }

    @Test
    fun limitFollowsInjectedCaptureOptions() {
        assertEquals("00:05 / 01:00", formatTimer(durationMs = 5_000, limitMs = 60_000))
    }

    @Test
    fun capsDurationVisualsAtOrAboveLimit() {
        assertEquals("01:00 / 01:00", formatTimer(durationMs = 60_000, limitMs = 60_000))
    }
}

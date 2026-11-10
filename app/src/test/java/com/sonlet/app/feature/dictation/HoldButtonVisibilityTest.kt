package com.sonlet.app.feature.dictation

import com.sonlet.app.dictation.core.DictationPhase
import com.sonlet.app.dictation.core.holdButtonVisible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按住说话按钮可见性不变量(issue-11-10-hold-to-talk-release)。
 *
 * 这条谓词是"按钮不得在录音生命周期内被移出组合"的可执行文档:
 * 一旦 STARTING 从集合里漏掉,松手事件就会丢失,录音只能等 30s 上限。
 */
class HoldButtonVisibilityTest {

    @Test
    fun startingPhaseKeepsHoldButtonMounted() {
        assertTrue(
            "STARTING 必须保持挂载,否则按下后立刻进入 STARTING 会丢掉松手事件",
            holdButtonVisible(DictationPhase.STARTING, holdMode = true, gestureInProgress = false),
        )
    }

    @Test
    fun readyAndRecordingShowHoldButton() {
        assertTrue(holdButtonVisible(DictationPhase.READY, holdMode = true, gestureInProgress = false))
        assertTrue(holdButtonVisible(DictationPhase.RECORDING, holdMode = true, gestureInProgress = false))
    }

    @Test
    fun phasesOutsideRecordingLifecycleHideHoldButton() {
        listOf(
            DictationPhase.PREPARING,
            DictationPhase.TRANSCRIBING,
            DictationPhase.CANCELLING,
            DictationPhase.ERROR,
        ).forEach { phase ->
            assertFalse(
                "$phase 不属于录音生命周期,不应呈现按住按钮",
                holdButtonVisible(phase, holdMode = true, gestureInProgress = true),
            )
        }
    }

    @Test
    fun tapModeHidesHoldButtonWhenNoGestureInFlight() {
        assertFalse(holdButtonVisible(DictationPhase.READY, holdMode = false, gestureInProgress = false))
    }

    @Test
    fun activeGestureSurvivesModeSwitch() {
        assertTrue(
            "按住中途切到点击模式时,手势仍在进行,按钮不得被摘除",
            holdButtonVisible(DictationPhase.RECORDING, holdMode = false, gestureInProgress = true),
        )
    }
}

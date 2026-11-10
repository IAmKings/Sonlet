package com.sonlet.app.feature.dictation

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.sonlet.app.dictation.core.DictationPhase
import com.sonlet.app.dictation.core.DictationUiState
import com.sonlet.app.ui.theme.SonletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 按住说话手势回归测试(issue: STARTING 阶段丢失松手事件)。
 *
 * 为什么用原始 [MotionEvent] 注入而不是 Compose 的 performTouchInput:
 * [performTouchInput] 每次调用都会新建一个 InputDispatcher,block 结束时把仍按下的指针自动抬起,
 * 因此「down 一次 → 等重组 → up 一次」这种跨调用的手势拿不到真实的抬手事件(实测事件序列只有 start)。
 * 这里直接走 UiAutomation 注入 DOWN / MOVE / UP,事件按真实时间顺序进入应用输入队列,
 * 中间可以 waitForIdle 让「按下 → Start 命令 → 状态机进入 STARTING → 重组」真正发生。
 */
class VoiceActionRowGestureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun releaseAfterPhaseBecomesStartingStillFiresFinish() {
        val phase = mutableStateOf(DictationPhase.READY)
        val events = mutableListOf<String>()
        setRow(phase, events, advanceOnStart = true)

        press()
        assertEquals(
            "按下后应进入 STARTING(按住按钮此时仍须留在组合树中)",
            DictationPhase.STARTING,
            phase.value,
        )
        release()

        assertEquals(
            "STARTING 阶段抬手后必须送达一次 finish",
            listOf(EVENT_START, EVENT_FINISH),
            events,
        )
    }

    @Test
    fun holdButtonStaysComposedDuringStarting() {
        val phase = mutableStateOf(DictationPhase.READY)
        setRow(phase, mutableListOf(), advanceOnStart = false)

        compose.onNodeWithTag(HOLD_TO_TALK_TEST_TAG).assertExists()

        compose.runOnIdle { phase.value = DictationPhase.STARTING }
        compose.waitForIdle()

        compose.onNodeWithTag(HOLD_TO_TALK_TEST_TAG)
            .assertExists()
    }

    @Test
    fun draggingUpBeyondThresholdCancelsWithoutFinishing() {
        val phase = mutableStateOf(DictationPhase.READY)
        val events = mutableListOf<String>()
        setRow(phase, events, advanceOnStart = true)

        val start = screenPoint()
        val dragUp = with(compose.density) { 200.dp.toPx() }

        injectTouch(MotionEvent.ACTION_DOWN, start)
        // 分几步移动,模拟真实手指轨迹
        for (step in 1..3) {
            injectTouch(MotionEvent.ACTION_MOVE, start + Offset(0f, -dragUp * step / 3f))
        }
        injectTouch(MotionEvent.ACTION_UP, start + Offset(0f, -dragUp))

        assertTrue("上滑超过阈值后应触发取消,实际: $events", events.contains(EVENT_CANCEL))
        assertFalse("上滑取消后不得再触发 finish,实际: $events", events.contains(EVENT_FINISH))
    }

    @Test
    fun quickReleaseInReadyStillFiresFinish() {
        val phase = mutableStateOf(DictationPhase.READY)
        val events = mutableListOf<String>()
        setRow(phase, events, advanceOnStart = false)

        press()
        release()

        assertEquals(listOf(EVENT_START, EVENT_FINISH), events)
    }

    // ---- 手势注入 ----

    private var downTime = 0L

    /** 在按住按钮中心按下并等待一帧,让状态机与重组完成。 */
    private fun press() {
        injectTouch(MotionEvent.ACTION_DOWN, screenPoint())
    }

    /** 在按下的位置抬手(手指不移动)。 */
    private fun release() {
        injectTouch(MotionEvent.ACTION_UP, lastPoint)
    }

    private var lastPoint: Offset = Offset.Zero

    private fun injectTouch(action: Int, point: Offset) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now
        }
        lastPoint = point
        val event = MotionEvent.obtain(downTime, now, action, point.x, point.y, 0)
        try {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
        compose.waitForIdle()
    }

    /** 按住按钮中心在屏幕坐标系下的位置(窗口坐标 + 窗口原点)。 */
    private fun screenPoint(): Offset {
        val bounds = compose.onNodeWithTag(HOLD_TO_TALK_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInWindow
        val location = IntArray(2)
        compose.activity.window.decorView.getLocationOnScreen(location)
        return Offset(
            x = bounds.center.x + location[0],
            y = bounds.center.y + location[1],
        )
    }

    private fun setRow(
        phase: MutableState<DictationPhase>,
        events: MutableList<String>,
        advanceOnStart: Boolean,
    ) {
        compose.setContent {
            SonletTheme {
                ActionRow(
                    state = DictationUiState(phase = phase.value),
                    holdMode = true,
                    onStart = {
                        events += EVENT_START
                        if (advanceOnStart) {
                            phase.value = DictationPhase.STARTING
                        }
                    },
                    onFinish = { events += EVENT_FINISH },
                    onCancel = { events += EVENT_CANCEL },
                    onRetryModel = {},
                    onDismissError = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private companion object {
        const val EVENT_START = "start"
        const val EVENT_FINISH = "finish"
        const val EVENT_CANCEL = "cancel"
    }
}

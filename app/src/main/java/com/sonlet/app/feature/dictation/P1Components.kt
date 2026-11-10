package com.sonlet.app.feature.dictation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sonlet.app.R
import com.sonlet.app.dictation.core.DictationPhase
import com.sonlet.app.dictation.core.DictationStyle
import com.sonlet.app.dictation.core.HOLD_PHASES
import com.sonlet.app.dictation.core.DictationUiState

/**
 * P1 交互组件(DESIGN.md §1 P1 行):按住说话/滑动取消、真实采样波形、风格选择。
 * 波形仅基于真实采样 RMS,不充当"正在说话"的唯一判定(DESIGN.md §8)。
 */

/**
 * 语音主按钮的按住说话模式(FR-101):
 * - READY 按下 → 开始;RECORDING 松开 → 结束转写
 * - 按住期间上滑超过 [cancelDragDistance] → 取消(松开不再触发结束)
 * - 按下时启动未完成即松开 → Controller pendingFinish(CC-02)
 * - 快速点击由 <300ms 误触路径兜底
 *
 * 不变量:手势 key 不得依赖状态机阶段。按下会推动状态机进入 STARTING,
 * 若 key 含 phase 就会在按住的同一帧重启协程、丢掉在途手势(松手永远不到达)。
 * 是否挂载本手势由调用方按 [HOLD_PHASES] 决定。
 */
fun Modifier.voiceHoldGesture(
    cancelDragDistance: Float,
    onPressStart: () -> Unit,
    onPressRelease: () -> Unit,
    onPressCancel: () -> Unit,
): Modifier = pointerInput(cancelDragDistance) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPressStart()
        var cancelled = false
        val startY = down.position.y
        while (true) {
            val event = awaitPointerEvent()
            // 多指时只跟随发起手势的那根手指
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!cancelled && (startY - change.position.y) > cancelDragDistance) {
                cancelled = true
                onPressCancel()
            }
            change.consume()
            if (!change.pressed) break
        }
        // 取消路径只回调一次 cancel:释放时不再重复通知
        if (!cancelled) {
            onPressRelease()
        }
    }
}

/** 按住说话主按钮:按下即录、松开转写、上滑取消 */
@Composable
fun HoldToTalkButton(
    state: DictationUiState,
    modifier: Modifier = Modifier,
    onPressStart: () -> Unit,
    onPressRelease: () -> Unit,
    onPressCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val cancelDrag = with(density) { 80.dp.toPx() }
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.96f else 1f,
        animationSpec = tween(120),
        label = "hold-press-scale",
    )
    // STARTING 也属于可交互态:按钮在该阶段仍挂载,松手必须能结束会话
    val interactive = state.phase in HOLD_PHASES
    val label = when {
        state.phase == DictationPhase.READY -> stringResource(R.string.btn_hold_to_talk)
        state.phase == DictationPhase.RECORDING -> stringResource(R.string.btn_release_to_transcribe)
        state.phase == DictationPhase.STARTING -> stringResource(R.string.btn_state_starting)
        state.phase == DictationPhase.TRANSCRIBING -> stringResource(R.string.btn_state_transcribing)
        state.phase == DictationPhase.CANCELLING -> stringResource(R.string.btn_state_stopping)
        else -> stringResource(R.string.btn_state_preparing)
    }
    val a11y = stringResource(
        if (state.phase == DictationPhase.RECORDING) {
            R.string.a11y_voice_button_finish
        } else {
            R.string.a11y_voice_button_start
        },
    )
    Box(
        modifier = modifier
            .scale(scale)
            .testTag(HOLD_TO_TALK_TEST_TAG)
            .then(
                if (interactive) {
                    Modifier.voiceHoldGesture(
                        cancelDragDistance = cancelDrag,
                        onPressStart = {
                            pressed.value = true
                            onPressStart()
                        },
                        onPressRelease = {
                            pressed.value = false
                            onPressRelease()
                        },
                        onPressCancel = {
                            pressed.value = false
                            onPressCancel()
                        },
                    )
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = a11y },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (interactive) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 16.dp),
        )
    }
}

/** 录音波形(真实 RMS 历史;RECORDING 之外保持静止,DESIGN.md §8) */
@Composable
fun RecordingSurface(
    volumeLevel: Float,
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateOf(FloatArray(WAVE_BARS)) }
    if (recording) {
        val next = history.value.copyOf()
        System.arraycopy(next, 1, next, 0, WAVE_BARS - 1)
        next[WAVE_BARS - 1] = volumeLevel
        history.value = next
    } else if (history.value.any { it > 0.01f }) {
        history.value = FloatArray(WAVE_BARS)
    }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.fillMaxWidth().height(36.dp)) {
        val barWidth = size.width / (WAVE_BARS * 2f)
        val step = barWidth * 2f
        val centerY = size.height / 2f
        history.value.forEachIndexed { index, level ->
            val h = (level.coerceIn(0f, 1f) * (size.height - 4.dp.toPx()))
                .coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = if (h > 2.dp.toPx()) barColor else trackColor,
                topLeft = Offset(index * step + barWidth / 2, centerY - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

/** 风格选择(P1 FR-104):仅 READY 展示;规则处理,非语义润色(DESIGN.md P1) */
@Composable
fun StyleSelector(
    selected: DictationStyle,
    onSelect: (DictationStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StyleChip(DictationStyle.VERBATIM, selected, onSelect)
        StyleChip(DictationStyle.NATURAL, selected, onSelect)
        StyleChip(DictationStyle.CONCISE, selected, onSelect)
        StyleChip(DictationStyle.NOTES, selected, onSelect)
    }
}

@Composable
private fun StyleChip(
    style: DictationStyle,
    selected: DictationStyle,
    onSelect: (DictationStyle) -> Unit,
) {
    FilterChip(
        selected = selected == style,
        onClick = { onSelect(style) },
        label = {
            Text(
                when (style) {
                    DictationStyle.VERBATIM -> stringResource(R.string.style_verbatim)
                    DictationStyle.NATURAL -> stringResource(R.string.style_natural)
                    DictationStyle.CONCISE -> stringResource(R.string.style_concise)
                    DictationStyle.NOTES -> stringResource(R.string.style_notes)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

private const val WAVE_BARS = 40

/** 按住按钮的测试标记:UI 测试据此断言"节点在 STARTING 阶段未被移出组合" */
internal const val HOLD_TO_TALK_TEST_TAG = "hold-to-talk-button"

package com.sonlet.app.feature.dictation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonlet.app.R
import com.sonlet.app.dictation.core.DictationErrorCode
import com.sonlet.app.dictation.core.DictationPhase
import com.sonlet.app.dictation.core.HOLD_PHASES
import com.sonlet.app.dictation.core.holdButtonVisible
import com.sonlet.app.dictation.core.DictationStyle
import com.sonlet.app.dictation.core.DictationUiState
import java.util.Locale

/**
 * P0 主页面 DictationEditorScreen(PRD §5.1、DESIGN.md §5)。
 * 从上到下:页头 → 可伸缩编辑器 → 状态与语音操作区;所有阶段编辑器可编辑(AC-P0-021)。
 */
@Composable
fun DictationScreen(viewModel: DictationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberMicPermissionState()

    // 编辑器状态:以组合内为最新真值,写回 VM 供交付时使用(CC-04)
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(viewModel) {
        viewModel.onEditorChanged = { editorValue = it }
    }
    viewModel.updateEditor(editorValue)

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.toastFlow.collect { toast ->
            val message = when (toast) {
                is DictationViewModel.Toast.Res -> context.getString(toast.id)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onAppBackgrounded()
    }

    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permission.onGranted()
                if (permission.pendingStart) {
                    permission.pendingStart = false
                    viewModel.startDictation()
                }
            } else {
                val activity = context as? android.app.Activity
                val showRationale = activity?.shouldShowRequestPermissionRationale(
                    Manifest.permission.RECORD_AUDIO,
                ) ?: true
                permission.onDenied(showRationale)
            }
        }

    val onStartRequested: () -> Unit = {
        if (permission.granted) {
            viewModel.startDictation()
        } else {
            permission.pendingStart = true
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // P1:交互模式与风格(DESIGN.md P1 边界:经验证的能力才展示入口)
    var holdMode by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(DictationStyle.VERBATIM) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val horizontalPadding = if (compact) 16.dp else 24.dp
        val contentMaxWidth = if (compact) null else 720.dp

        val contentModifier = if (contentMaxWidth != null) {
            Modifier.fillMaxWidth().widthIn(max = contentMaxWidth)
        } else {
            Modifier.fillMaxWidth()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = contentModifier) {
                Header()
                Editor(
                    value = editorValue,
                    onValueChange = {
                        editorValue = it
                        viewModel.updateEditor(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                if (permission.deniedOnce && !permission.granted) {
                    Spacer(Modifier.height(4.dp))
                    PermissionRationaleSurface(
                        state = permission,
                        onRequest = {
                            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onOpenSettings = { openAppSettings(context) },
                        onKeepEditing = { permission.dismissRationale() },
                    )
                }
                if (state.phase == DictationPhase.READY) {
                    StyleSelector(
                        selected = style,
                        onSelect = {
                            style = it
                            viewModel.setStyle(it)
                        },
                    )
                }
                ControlPanel(
                    state = state,
                    holdMode = holdMode,
                    onHoldModeChange = { holdMode = it },
                    modifier = Modifier.fillMaxWidth(),
                    onStart = onStartRequested,
                    onFinish = viewModel::finishDictation,
                    onCancel = viewModel::cancelDictation,
                    onRetryModel = viewModel::retryModel,
                    onDismissError = viewModel::dismissError,
                    onOpenSettings = { openAppSettings(context) },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "· " + stringResource(R.string.header_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 编辑面(DESIGN.md §7):surface 底 16dp 圆角;正常 outline、聚焦 primary 边界 */
@Composable
private fun Editor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .onFocusChanged { isFocused = it.isFocused },
            textStyle = TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.editor_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** 语音控制面(DESIGN.md §7):文字在前,操作在后;按钮位置稳定 */
@Composable
private fun ControlPanel(
    state: DictationUiState,
    holdMode: Boolean,
    onHoldModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetryModel: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = modifier.padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(state)
            if (state.phase == DictationPhase.RECORDING) {
                val waveformDescription = stringResource(R.string.a11y_waveform)
                RecordingSurface(
                    volumeLevel = state.volumeLevel,
                    recording = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = waveformDescription
                        },
                )
            }
            ActionRow(
                state = state,
                holdMode = holdMode,
                onStart = onStart,
                onFinish = onFinish,
                onCancel = onCancel,
                onRetryModel = onRetryModel,
                onDismissError = onDismissError,
                onOpenSettings = onOpenSettings,
            )
            ModeToggleRow(
                state = state,
                holdMode = holdMode,
                onHoldModeChange = onHoldModeChange,
            )
        }
    }
}

/** 中性文字按钮(DESIGN.md §7:取消不用品牌主色,避免与主动作混淆) */
@Composable
private fun NeutralTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusRow(state: DictationUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.phase) {
            DictationPhase.PREPARING ->
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            DictationPhase.RECORDING ->
                Icon(
                    imageVector = SonletIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            else ->
                Icon(
                    imageVector = SonletIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Text(
            text = statusText(state),
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.phase == DictationPhase.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        // 计时仅在录音中展示;STARTING 不显示已录时长(DESIGN.md §6)
        if (state.phase == DictationPhase.RECORDING) {
            Text(
                text = formatTimer(state.durationMs),
                style = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ActionRow(
    state: DictationUiState,
    holdMode: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetryModel: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 手势存活态:按住期间即使交互模式被切换,也必须保住按钮节点,否则松手事件丢失
    var gestureInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (state.phase !in HOLD_PHASES) gestureInProgress = false
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 按住说话模式(FR-101):READY/STARTING/RECORDING 阶段由手势组件接管主按钮。
        // STARTING 必须保留——按下会立刻推进到 STARTING,此刻移出组合会丢掉松手事件。
        if (holdButtonVisible(state.phase, holdMode, gestureInProgress)) {
            if (state.phase != DictationPhase.READY) {
                NeutralTextButton(text = stringResource(R.string.btn_cancel), onClick = onCancel)
            }
            HoldToTalkButton(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                onPressStart = {
                    gestureInProgress = true
                    onStart()
                },
                onPressRelease = {
                    gestureInProgress = false
                    onFinish()
                },
                onPressCancel = {
                    gestureInProgress = false
                    onCancel()
                },
            )
            return@Row
        }
        when (state.phase) {
            DictationPhase.READY -> PrimaryButton(
                text = stringResource(R.string.btn_start),
                icon = SonletIcons.Mic,
                enabled = true,
                onClick = onStart,
                modifier = Modifier.weight(1f),
            )
            DictationPhase.RECORDING -> {
                NeutralTextButton(text = stringResource(R.string.btn_cancel), onClick = onCancel)
                PrimaryButton(
                    text = stringResource(R.string.btn_finish),
                    icon = SonletIcons.Stop,
                    enabled = true,
                    onClick = onFinish,
                    modifier = Modifier.weight(1f),
                )
            }
            DictationPhase.STARTING -> {
                NeutralTextButton(text = stringResource(R.string.btn_cancel), onClick = onCancel)
                PrimaryButton(
                    text = stringResource(R.string.btn_state_starting),
                    icon = null,
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
            DictationPhase.TRANSCRIBING -> {
                NeutralTextButton(text = stringResource(R.string.btn_cancel), onClick = onCancel)
                PrimaryButton(
                    text = stringResource(R.string.btn_state_transcribing),
                    icon = null,
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
            DictationPhase.PREPARING -> PrimaryButton(
                text = stringResource(R.string.btn_state_preparing),
                icon = null,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            DictationPhase.CANCELLING -> PrimaryButton(
                text = stringResource(R.string.btn_state_stopping),
                icon = null,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            DictationPhase.ERROR -> {
                NeutralTextButton(
                    text = stringResource(R.string.btn_back_to_edit),
                    onClick = onDismissError,
                )
                val isPermissionError =
                    state.errorCode == DictationErrorCode.MIC_PERMISSION_DENIED ||
                        state.errorCode == DictationErrorCode.MIC_PERMISSION_REVOKED
                if (isPermissionError) {
                    PrimaryButton(
                        text = stringResource(R.string.btn_open_settings),
                        icon = null,
                        enabled = true,
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val retryLabel = when (state.errorCode) {
                        DictationErrorCode.MODEL_NOT_FOUND,
                        DictationErrorCode.MODEL_CORRUPTED,
                        DictationErrorCode.MODEL_LOAD_FAILED,
                        -> stringResource(R.string.btn_retry)
                        else -> stringResource(R.string.btn_start)
                    }
                    PrimaryButton(
                        text = retryLabel,
                        icon = SonletIcons.Mic,
                        enabled = true,
                        onClick = if (state.errorCode.isModelError()) onRetryModel else onStart,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun DictationErrorCode?.isModelError(): Boolean = this == DictationErrorCode.MODEL_NOT_FOUND ||
    this == DictationErrorCode.MODEL_CORRUPTED ||
    this == DictationErrorCode.MODEL_LOAD_FAILED

/** 主按钮(DESIGN.md §7):primary 底、最小高 56dp;禁用时保留语义文案 */
@Composable
private fun PrimaryButton(
    text: String,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun statusText(state: DictationUiState): String = when (state.phase) {
    DictationPhase.PREPARING -> stringResource(R.string.status_preparing)
    DictationPhase.READY -> stringResource(R.string.status_ready)
    DictationPhase.STARTING -> stringResource(R.string.status_starting)
    DictationPhase.RECORDING -> stringResource(R.string.status_recording)
    DictationPhase.TRANSCRIBING -> stringResource(R.string.status_transcribing)
    DictationPhase.CANCELLING -> stringResource(R.string.status_cancelling)
    DictationPhase.ERROR -> stringResource(
        R.string.status_error,
        stringResource(errorTextRes(state.errorCode)),
    )
}

fun errorTextRes(code: DictationErrorCode?): Int = when (code) {
    DictationErrorCode.MIC_PERMISSION_DENIED -> R.string.err_MIC_PERMISSION_DENIED
    DictationErrorCode.MIC_PERMISSION_REVOKED -> R.string.err_MIC_PERMISSION_REVOKED
    DictationErrorCode.MIC_BUSY -> R.string.err_MIC_BUSY
    DictationErrorCode.AUDIO_INIT_FAILED -> R.string.err_AUDIO_INIT_FAILED
    DictationErrorCode.AUDIO_READ_FAILED -> R.string.err_AUDIO_READ_FAILED
    DictationErrorCode.AUDIO_ROUTE_CHANGED -> R.string.err_AUDIO_ROUTE_CHANGED
    DictationErrorCode.MODEL_NOT_FOUND -> R.string.err_MODEL_NOT_FOUND
    DictationErrorCode.MODEL_CORRUPTED -> R.string.err_MODEL_CORRUPTED
    DictationErrorCode.MODEL_LOAD_FAILED -> R.string.err_MODEL_LOAD_FAILED
    DictationErrorCode.ASR_EMPTY_RESULT -> R.string.err_ASR_EMPTY_RESULT
    DictationErrorCode.ASR_DECODE_FAILED -> R.string.err_ASR_DECODE_FAILED
    DictationErrorCode.AUDIO_TOO_SHORT -> R.string.err_AUDIO_TOO_SHORT
    DictationErrorCode.AUDIO_TOO_LONG -> R.string.err_AUDIO_TOO_LONG
    DictationErrorCode.SESSION_CANCELLED -> R.string.err_SESSION_CANCELLED
    DictationErrorCode.UNSUPPORTED_AUDIO_FORMAT -> R.string.err_UNSUPPORTED_AUDIO_FORMAT
    null -> R.string.err_ASR_DECODE_FAILED
}

/** 等宽数字计时(DESIGN.md §4):不每秒改变布局宽度 */
internal fun formatTimer(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d / 00:30", minutes, seconds)
}

/** P1 交互模式切换:仅 READY/RECORDING 展示;随时可切,行为对当前会话安全 */
@Composable
private fun ModeToggleRow(
    state: DictationUiState,
    holdMode: Boolean,
    onHoldModeChange: (Boolean) -> Unit,
) {
    val toggleVisible = state.phase == DictationPhase.READY ||
        state.phase == DictationPhase.RECORDING
    if (!toggleVisible) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(
            onClick = { onHoldModeChange(false) },
            enabled = holdMode,
        ) {
            Text(
                stringResource(R.string.mode_toggle_tap),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(
            onClick = { onHoldModeChange(true) },
            enabled = !holdMode,
        ) {
            Text(
                stringResource(R.string.mode_toggle_hold),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 界面图标:不把 Split S 当作功能图标(DESIGN.md §2);标准麦克风/停止形状 */
object SonletIcons {

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "SonletMic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = androidx.compose.ui.graphics.vector.PathData {
                    moveTo(12f, 14f)
                    arcToRelative(3f, 3f, 0f, false, false, 3f, -3f)
                    verticalLineTo(5f)
                    arcToRelative(3f, 3f, 0f, false, false, -6f, 0f)
                    verticalLineTo(11f)
                    arcToRelative(3f, 3f, 0f, false, false, 3f, 3f)
                    close()
                    moveTo(19f, 11f)
                    arcTo(7f, 7f, 0f, false, true, 5f, 11f)
                    horizontalLineTo(3f)
                    arcTo(9f, 9f, 0f, false, false, 11f, 19.94f)
                    verticalLineTo(22f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2.06f)
                    arcTo(9f, 9f, 0f, false, false, 21f, 11f)
                    close()
                },
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "SonletStop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = androidx.compose.ui.graphics.vector.PathData {
                    moveTo(6f, 6f)
                    horizontalLineToRelative(12f)
                    verticalLineToRelative(12f)
                    horizontalLineTo(6f)
                    close()
                },
                fill = SolidColor(Color.Black),
            )
        }.build()
    }
}

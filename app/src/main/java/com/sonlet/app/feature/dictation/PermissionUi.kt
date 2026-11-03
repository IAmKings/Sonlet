package com.sonlet.app.feature.dictation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sonlet.app.R

/**
 * 麦克风权限交互(PRD FR-003、AC-P0-003/004、DESIGN.md §7):
 * - 点击开始触发系统授权;拒绝后继续编辑,不循环弹窗;
 * - 永久拒绝提供"打开设置"路径;授权恢复后自动回到可用状态。
 */
class MicPermissionState(private val context: Context) {

    internal val appContext: Context = context.applicationContext

    var granted: Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        private set

    /** 拒绝过一次:显示就近说明面(DESIGN.md §7 权限说明) */
    var deniedOnce by mutableStateOf(false)
        private set

    /** 用户选择继续编辑:收起说明面,不改变授权事实 */
    internal fun dismissRationale() {
        deniedOnce = false
    }

    /** 永久拒绝(系统不再弹框):显示设置路径 */
    var permanentlyDenied by mutableStateOf(false)
        private set

    /** 授权成功后自动开始录音的意图(点击开始 → 授权 → 直接开录) */
    var pendingStart by mutableStateOf(false)

    fun onGranted() {
        granted = true
        deniedOnce = false
        permanentlyDenied = false
    }

    fun onDenied(shouldShowRationale: Boolean) {
        granted = false
        deniedOnce = true
        permanentlyDenied = !shouldShowRationale
    }

    /** 从系统设置返回后刷新(AC-P0-004 恢复路径) */
    fun refresh() {
        val nowGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (nowGranted) {
            onGranted()
        } else if (granted) {
            // 权限被收回:回到未授权状态(PRD MIC_PERMISSION_REVOKED 前置)
            granted = false
        }
    }
}

@Composable
fun rememberMicPermissionState(): MicPermissionState {
    val context = LocalContext.current
    val state = remember { MicPermissionState(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** 请求授权:由 Screen 持有 launcher,结果回灌状态 */

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * 就近权限说明面(DESIGN.md §7):解释用途;拒绝后可继续编辑;
 * 永久拒绝提供系统设置入口。
 */
@Composable
fun PermissionRationaleSurface(
    state: MicPermissionState,
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.permission_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (state.permanentlyDenied) {
                    stringResource(R.string.permission_denied_permanent)
                } else {
                    stringResource(R.string.permission_denied_once)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onKeepEditing) {
                    Text(stringResource(R.string.btn_back_to_edit))
                }
                if (state.permanentlyDenied) {
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.btn_open_settings))
                    }
                } else {
                    Button(onClick = onRequest) {
                        Text(stringResource(R.string.btn_start))
                    }
                }
            }
        }
    }
}

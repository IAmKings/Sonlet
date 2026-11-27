package com.sonlet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonlet.app.feature.update.UpdateDialog
import com.sonlet.app.feature.dictation.DictationScreen
import com.sonlet.app.feature.dictation.DictationViewModel
import com.sonlet.app.ui.theme.SonletTheme
import io.github.releasekite.ReleaseKite

/**
 * 应用入口:承载 DictationEditorScreen(PRD §5.1)。
 */
class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        if (ReleaseKite.isInitialized()) ReleaseKite.onForeground()
    }

    override fun onStop() {
        // 引用计数:后台完成下载时据此改发通知而不是直接拉起安装器
        if (ReleaseKite.isInitialized()) ReleaseKite.onBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonletTheme {
                val viewModel: DictationViewModel = viewModel()
                // 页面底色走主题 background:此前没人铺底,深色模式下会露出
                // Activity 主题的浅色 window background,整页变成浅灰配深色卡片。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DictationScreen(viewModel, onCheckUpdate = { ReleaseKite.manager.checkNow() })
                    UpdateHost()
                }
            }
        }
    }
}

/**
 * 更新检查的宿主:进入页面时按 24h 节流检查一次,回到前台时续装已下载的包。
 *
 * 弹窗由 [UpdateDialog] 自绘,不用 ReleaseKite 自带 UI,以对齐 DESIGN.md 的平面版式。
 */
@androidx.compose.runtime.Composable
private fun UpdateHost() {
    val manager = remember { ReleaseKite.manager }
    val state by manager.state.collectAsStateWithLifecycle()

    // 记录状态迁移，失败原因只留在本机日志，便于用户反馈时定位
    LaunchedEffect(state) {
        android.util.Log.d("SonletUpdate", "phase=${state.phase::class.simpleName}")
    }

    LaunchedEffect(manager) { manager.onAppEntered() }
    LifecycleResumeEffect(manager) {
        manager.onAppResumed()
        onPauseOrDispose { }
    }

    UpdateDialog(
        state = state,
        onUpdate = { manager.startDownload() },
        onLater = { manager.dismiss() },
        onRetry = { manager.retry() },
        onOpenInstallPermission = { manager.openInstallPermissionSettings() },
    )
}

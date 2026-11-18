package com.sonlet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonlet.app.feature.dictation.DictationScreen
import com.sonlet.app.feature.dictation.DictationViewModel
import com.sonlet.app.ui.theme.SonletTheme

/**
 * 应用入口:承载 DictationEditorScreen(PRD §5.1)。
 */
class MainActivity : ComponentActivity() {

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
                    DictationScreen(viewModel)
                }
            }
        }
    }
}

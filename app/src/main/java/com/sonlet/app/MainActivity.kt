package com.sonlet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                DictationScreen(viewModel)
            }
        }
    }
}

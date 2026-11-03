package com.sonlet.app

import android.content.res.AssetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sonlet.app.ui.theme.SonletTheme
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M0 工程基线占位页:验证构建、主题与模型随包完整性检查。
 * M2 将替换为 DictationEditorScreen(PRD §5.1)。
 */
class MainActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val mainScope: CoroutineScope = MainScope()

    private var modelStatus by mutableStateOf<ModelStatus>(ModelStatus.Checking)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonletTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = stringResource(R.string.m0_baseline_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.app_name) + " · 本地转写",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val status = modelStatus
                        val text = when (status) {
                            ModelStatus.Checking -> stringResource(R.string.m0_model_check_running)
                            is ModelStatus.Ready -> stringResource(
                                R.string.m0_model_check_ok,
                                formatModelSize(status.modelBytes),
                            )
                            ModelStatus.Missing -> stringResource(R.string.m0_model_check_missing)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                        ) {
                            Text(
                                text = text,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (status is ModelStatus.Missing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // FR-001:后台线程做模型可用性快检(存在性 + 体积);完整哈希校验由 ModelRepository 承担
        modelStatus = ModelStatus.Checking
        val assets = assets
        ioScope.launch {
            val status = checkModelAssets(assets)
            withContext(Dispatchers.Main) { modelStatus = status }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    private fun checkModelAssets(assets: AssetManager): ModelStatus {
        val modelSize = assetLength(assets, MODEL_ASSET)
        val tokensSize = assetLength(assets, TOKENS_ASSET)
        if (modelSize == null || tokensSize == null) return ModelStatus.Missing
        // 模型体积应显著大于 200MB(PRD §6.1)
        return if (modelSize > MODEL_MIN_BYTES && tokensSize > 0) {
            ModelStatus.Ready(modelSize)
        } else {
            ModelStatus.Missing
        }
    }

    /** 未压缩资产用 openFd 精确长度;被压缩资产回退为流长度估计 */
    private fun assetLength(assets: AssetManager, path: String): Long? =
        runCatching { assets.openFd(path).use { fd -> fd.length.takeIf { it > 0 } } }
            .recoverCatching { assets.open(path).use { it.available().toLong() } }
            .getOrNull()

    private fun formatModelSize(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))

    private sealed interface ModelStatus {
        data object Checking : ModelStatus
        data object Missing : ModelStatus
        data class Ready(val modelBytes: Long) : ModelStatus
    }

    private companion object {
        const val MODEL_ASSET = "sensevoice/model.int8.onnx"
        const val TOKENS_ASSET = "sensevoice/tokens.txt"
        const val MODEL_MIN_BYTES = 200L * 1024 * 1024
    }
}

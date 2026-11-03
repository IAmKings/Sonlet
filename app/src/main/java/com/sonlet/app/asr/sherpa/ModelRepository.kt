package com.sonlet.app.asr.sherpa

import android.content.res.AssetManager
import com.sonlet.app.asr.api.AsrEngineException
import com.sonlet.app.asr.api.AsrErrorCode
import com.sonlet.app.asr.api.AsrModelDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模型安装状态(PRD §11.2 status)。
 */
sealed interface ModelInstallStatus {
    data object NotInstalled : ModelInstallStatus
    data object Verifying : ModelInstallStatus
    data class Ready(val modelBytes: Long, val tokensBytes: Long) : ModelInstallStatus
    data object Corrupted : ModelInstallStatus
    data class Failed(val message: String) : ModelInstallStatus
}

/**
 * P0 随包模型仓库(PRD §11):负责 verify/prepare/status/describe。
 * P0 只读 assets;P2 的 install()/下载/回滚为扩展点,不在此实现。
 *
 * 校验策略:fastCheck(存在性 + 体积)用于快速路径;verify(流式 SHA-256)
 * 在模型准备阶段执行一次,结果与错误不写入持久化历史。
 */
class ModelRepository(private val assets: AssetManager) {

    val descriptor: AsrModelDescriptor = MODEL_DESCRIPTOR

    suspend fun fastCheck(): ModelInstallStatus = withContext(Dispatchers.IO) {
        val modelSize = assetSize(MODEL_ASSET)
        val tokensSize = assetSize(TOKENS_ASSET)
        if (modelSize == null || tokensSize == null) {
            ModelInstallStatus.NotInstalled
        } else if (modelSize < MIN_MODEL_BYTES || tokensSize < MIN_TOKENS_BYTES) {
            ModelInstallStatus.Corrupted
        } else {
            ModelInstallStatus.Ready(modelSize, tokensSize)
        }
    }

    /** 完整校验:存在性 + 体积 + SHA-256(防止损坏/替换文件进入推理) */
    suspend fun verify(): ModelInstallStatus = withContext(Dispatchers.IO) {
        when (val fast = fastCheck()) {
            is ModelInstallStatus.NotInstalled, ModelInstallStatus.Corrupted -> fast
            is ModelInstallStatus.Ready -> {
                val modelOk = runCatching { sha256Of(MODEL_ASSET) == MODEL_SHA256 }
                    .getOrDefault(false)
                val tokensOk = runCatching { sha256Of(TOKENS_ASSET) == TOKENS_SHA256 }
                    .getOrDefault(false)
                when {
                    modelOk && tokensOk -> fast
                    else -> ModelInstallStatus.Corrupted
                }
            }
            else -> fast
        }
    }

    /** 提供可读的模型入口;P0 校验通过后由引擎直接经 AssetManager 加载 */
    fun requireVerifiedOrNull(status: ModelInstallStatus): Boolean =
        status is ModelInstallStatus.Ready

    private fun assetSize(path: String): Long? =
        runCatching {
            assets.openFd(path).use { fd -> fd.length.takeIf { it > 0 } }
        }.recoverCatching {
            // 被压缩的资产无法 openFd;回退为流长度估计(verify 会做完整哈希兜底)
            assets.open(path).use { it.available().toLong() }
        }.getOrNull()

    private fun sha256Of(path: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        assets.open(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_ASSET = "sensevoice/model.int8.onnx"
        const val TOKENS_ASSET = "sensevoice/tokens.txt"

        // 与 models/manifest.json 固定值一致;修改模型必须同步更新
        const val MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
        const val TOKENS_SHA256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
        const val MIN_MODEL_BYTES = 200L * 1024 * 1024
        const val MIN_TOKENS_BYTES = 128L * 1024

        val MODEL_DESCRIPTOR = AsrModelDescriptor(
            id = "sensevoice-small-int8-2024-07-17",
            name = "SenseVoice Small (INT8)",
            sampleRate = 16_000,
            languages = setOf("zh", "en", "ja", "ko", "yue"),
            supportsStreaming = false,
        )
    }
}

/** 将仓库校验状态映射为引擎错误(PRD §10.4) */
fun ModelInstallStatus.toAsrErrorOrNull(): AsrEngineException? = when (this) {
    is ModelInstallStatus.NotInstalled ->
        AsrEngineException(
            AsrErrorCode.MODEL_NOT_FOUND,
            "model assets missing: ${ModelRepository.MODEL_ASSET}",
        )
    ModelInstallStatus.Corrupted ->
        AsrEngineException(AsrErrorCode.MODEL_CORRUPTED, "model integrity check failed")
    is ModelInstallStatus.Failed ->
        AsrEngineException(AsrErrorCode.MODEL_LOAD_FAILED, message)
    else -> null
}

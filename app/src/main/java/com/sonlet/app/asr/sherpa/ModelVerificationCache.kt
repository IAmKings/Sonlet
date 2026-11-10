package com.sonlet.app.asr.sherpa

import android.content.Context
import android.content.SharedPreferences

/**
 * 全量校验结果的缓存(P1 性能)。
 *
 * 一次 SHA-256 要读完整个 239MB 模型,而只要包没变,结论就不会变;每次冷启动
 * 都重算等于白烧 1~2 秒和一份电量。
 *
 * 信任边界:key 由「应用 lastUpdateTime + 模型哈希常量」构成 —— 升级 apk 或换模型
 * 必然改变 key,从而强制重新校验;体积异常仍在 [ModelRepository.fastCheck] 阶段拦下。
 * 也就是说,缓存省掉的是"同一份已装包内资源被重复哈希",不是校验本身。
 */
interface ModelVerificationCache {
    fun isVerified(key: String): Boolean
    fun markVerified(key: String)
}

/** SharedPreferences 实现:单条记录,只存最近的 key */
class SharedPreferencesModelVerificationCache(context: Context) : ModelVerificationCache {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isVerified(key: String): Boolean = prefs.getString(KEY_VERIFIED, null) == key

    override fun markVerified(key: String) {
        prefs.edit().putString(KEY_VERIFIED, key).apply()
    }

    private companion object {
        const val PREFS_NAME = "model_verification"
        const val KEY_VERIFIED = "verified_key"
    }
}

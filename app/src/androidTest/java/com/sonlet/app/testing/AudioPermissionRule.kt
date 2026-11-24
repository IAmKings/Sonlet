package com.sonlet.app.testing

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 需要麦克风的用例用的权限规则。
 *
 * 和 [androidx.test.rule.GrantPermissionRule] 的区别:Android 17(API 37)起 shell 不再持有
 * `GRANT_RUNTIME_PERMISSIONS`,`uiAutomation.grantRuntimePermission` 必定抛 SecurityException。
 * 这里把授予降级为"尽力而为":已经授予就跳过;没授予先尝试一次,失败只记日志,
 * 让用例自己因为拿不到音频而失败,而不是在规则阶段统一报错、把失败原因藏起来。
 */
class AudioPermissionRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                ensureGranted()
                base.evaluate()
            }
        }

    private fun ensureGranted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val result = runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO,
            )
        }
        result.onFailure { error ->
            Log.w(
                TAG,
                "无法自动授予麦克风权限(Android 17 起 shell 不再持有 GRANT_RUNTIME_PERMISSIONS)," +
                    "请先在应用界面上允许一次再跑本用例",
                error,
            )
        }
    }

    private companion object {
        const val TAG = "SonletTestPermission"
    }
}

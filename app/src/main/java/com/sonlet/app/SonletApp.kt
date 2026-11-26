package com.sonlet.app

import android.app.Application
import io.github.releasekite.ReleaseKite
import io.github.releasekite.core.ReleaseKiteConfig

/**
 * 应用入口:初始化应用内更新(ReleaseKite)。
 *
 * 更新检查走 GitHub Releases,是全应用唯一需要联网的功能;识别与模型下载仍完全离线
 * (PRD §14 已同步说明)。
 */
class SonletApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(this, UPDATE_CONFIG)
    }

    private companion object {
        val UPDATE_CONFIG = ReleaseKiteConfig(
            repository = "IAmKings/Sonlet",
            // 发布资产固定形如 Sonlet-0.1.6-arm64-v8a.apk,避免误匹配其他附件
            apkAssetPattern = Regex("""Sonlet-.*-arm64-v8a\.apk$""", RegexOption.IGNORE_CASE),
            // 仓库不发布 -update.json(灰度/强制元数据),关掉以免每次多一次 404
            metadataAssetSuffix = null,
            // 只发 stable,UI 不展示通道切换
            betaChannelEnabled = false,
            downloadFilePrefix = "sonlet",
        )
    }
}

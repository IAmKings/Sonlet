package com.sonlet.app.feature.update

import io.github.releasekite.core.UpdatePhase

/**
 * 更新阶段的稳定名字。
 *
 * 不能用 `::class.simpleName`:release 包经过 R8 混淆后会打出 `a12`/`z02` 这类名字,
 * 排查用户反馈时等于没有信息。这里的 when 分支穷举还兼作编译期检查——
 * 库升级新增阶段时会强制我们补一条。
 */
internal fun UpdatePhase.logName(): String = when (this) {
    is UpdatePhase.Idle -> "Idle"
    is UpdatePhase.Checking -> "Checking"
    is UpdatePhase.UpToDate -> "UpToDate"
    is UpdatePhase.Available -> "Available"
    is UpdatePhase.RolloutPending -> "RolloutPending"
    is UpdatePhase.Downloading -> "Downloading"
    is UpdatePhase.Verifying -> "Verifying"
    is UpdatePhase.NeedInstallPermission -> "NeedInstallPermission"
    is UpdatePhase.CheckFailed -> "CheckFailed"
    is UpdatePhase.DownloadFailed -> "DownloadFailed"
}

/** 失败原因;只用于日志,不展示给用户(弹窗文案保持中性)。 */
internal fun UpdatePhase.failureOrNull() = when (this) {
    is UpdatePhase.CheckFailed -> failure
    is UpdatePhase.DownloadFailed -> failure
    else -> null
}

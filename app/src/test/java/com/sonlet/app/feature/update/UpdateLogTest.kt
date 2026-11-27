package com.sonlet.app.feature.update

import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateFailure
import io.github.releasekite.core.UpdateInfo
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val INFO = UpdateInfo(
    versionName = "0.1.10",
    tagName = "v0.1.10",
    title = "Sonlet 0.1.10",
    notes = "",
    publishedAt = null,
    htmlUrl = "https://example.invalid",
    apkUrl = "https://example.invalid/x.apk",
    apkSizeBytes = 1L,
    sha256 = null,
    sha256Url = null,
    channel = UpdateChannel.STABLE,
)

class UpdateLogTest {

    @Test
    fun phaseNamesStayStable() {
        // 覆盖全部分支:混淆后 ::class.simpleName 不可读,日志必须用这里的名字
        assertEquals("Idle", UpdatePhase.Idle.logName())
        assertEquals("Checking", UpdatePhase.Checking(UpdateTrigger.AUTO).logName())
        assertEquals("UpToDate", UpdatePhase.UpToDate(UpdateTrigger.AUTO, "0.1.10").logName())
        assertEquals("Available", UpdatePhase.Available(UpdateTrigger.AUTO, INFO).logName())
        assertEquals("Downloading", UpdatePhase.Downloading(INFO, 1L, 2L).logName())
        assertEquals("Verifying", UpdatePhase.Verifying(INFO).logName())
        assertEquals("NeedInstallPermission", UpdatePhase.NeedInstallPermission(INFO).logName())
        assertEquals("DownloadFailed", UpdatePhase.DownloadFailed(INFO, UpdateFailure.Timeout).logName())
        assertEquals("CheckFailed", UpdatePhase.CheckFailed(UpdateTrigger.AUTO, UpdateFailure.Timeout).logName())
    }

    @Test
    fun onlyFailedPhasesExposeFailure() {
        assertNull(UpdatePhase.Idle.failureOrNull())
        assertNull(UpdatePhase.UpToDate(UpdateTrigger.AUTO, "0.1.10").failureOrNull())
        assertEquals(
            UpdateFailure.Timeout,
            UpdatePhase.DownloadFailed(INFO, UpdateFailure.Timeout).failureOrNull(),
        )
    }
}

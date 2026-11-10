package com.sonlet.app.feature.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 权限状态机:granted 必须是可观察的 Compose state,否则从系统设置授权返回后
 * 界面不会刷新(P1-2)。
 */
@RunWith(AndroidJUnit4::class)
class MicPermissionStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun grantedFlipsWithOnGrantedAndOnDenied() {
        val state = MicPermissionState(context)

        state.onGranted()
        assertTrue("授权成功后 granted 必须为真", state.granted)
        assertFalse(state.deniedOnce)

        state.onDenied(shouldShowRationale = true)
        assertFalse("拒绝后 granted 必须为假", state.granted)
        assertTrue(state.deniedOnce)
        assertFalse("还能再弹框时不算永久拒绝", state.permanentlyDenied)
    }

    @Test
    fun permanentDenialIsRecorded() {
        val state = MicPermissionState(context)

        state.onDenied(shouldShowRationale = false)

        assertTrue("系统不再弹框即永久拒绝", state.permanentlyDenied)
    }

    @Test
    fun dismissRationaleOnlyHidesSurface() {
        val state = MicPermissionState(context)
        state.onDenied(shouldShowRationale = true)

        state.dismissRationale()

        assertFalse(state.deniedOnce)
        assertFalse("收起说明面不代表已授权", state.granted)
    }

    @Test
    fun refreshSyncsWithActualPermission() {
        val state = MicPermissionState(context)
        val actuallyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        state.refresh()

        assertEquals("refresh 后必须与系统事实一致", actuallyGranted, state.granted)
    }
}

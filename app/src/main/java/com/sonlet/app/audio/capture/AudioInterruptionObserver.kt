package com.sonlet.app.audio.capture

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * 音频设备路由变化观察(PRD AC-P0-015、CC-08)。
 *
 * P0 策略:任何输入设备被移除(蓝牙断连/有线拔出)即通知宿主,由 Controller
 * 决定是否终止当前会话。通话打断的系统性检测需要音频焦点协作,P0 不申请焦点,
 * 依靠采集读取失败路径兜底(记录于已知限制)。
 */
class AudioInterruptionObserver(
    private val audioManager: AudioManager,
    private val onInputDeviceRemoved: () -> Unit,
) {

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>?) {
            val removedInputs = removedDevices?.count { it.isSource } ?: 0
            if (removedInputs > 0) {
                Log.w(TAG, "input_device_removed count=$removedInputs")
                onInputDeviceRemoved()
            }
        }
    }

    private var registered = false

    fun register() {
        if (registered) return
        audioManager.registerAudioDeviceCallback(callback, null)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        audioManager.unregisterAudioDeviceCallback(callback)
        registered = false
    }

    private companion object {
        const val TAG = "SonletRoute"
    }
}

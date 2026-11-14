package com.koreader.ctrl.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.koreader.ctrl.gamepad.GamepadEngine

class GamepadAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GamepadA11y"

        @Volatile
        var isEnabled = false
            private set

        @Volatile
        var lastKeyEventTime = 0L
            private set

        fun isRunning(): Boolean = isEnabled
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isEnabled = true
        Log.i(TAG, "accessibility service connected")
        try {
            BackgroundInputService.start(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "start fg failed", e)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isEnabled = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isEnabled = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(keyEvent: KeyEvent?): Boolean {
        val ev = keyEvent ?: return false
        if (GamepadEngine.isSystemReservedKey(ev.keyCode)) return false

        if (ev.action != KeyEvent.ACTION_DOWN) return false
        if (ev.repeatCount > 0) return true

        val code = GamepadEngine.mapKeyEventToCode(ev) ?: return false

        val sources = ev.source
        val isGamepadOrDpad = (sources and InputDevice.SOURCE_GAMEPAD != 0) ||
                (sources and InputDevice.SOURCE_JOYSTICK != 0) ||
                (sources and InputDevice.SOURCE_DPAD != 0) ||
                (sources and InputDevice.SOURCE_KEYBOARD != 0)

        if (!isGamepadOrDpad) return false

        lastKeyEventTime = System.currentTimeMillis()
        val deviceId = ev.deviceId

        Log.i(TAG, "gamepad key: device=$deviceId code=$code")
        InputHub.dispatchInput(deviceId, code)
        if (!BackgroundInputService.isRunning) {
            BackgroundInputService.dispatcher?.handleInput(deviceId, code)
        }
        return true
    }
}

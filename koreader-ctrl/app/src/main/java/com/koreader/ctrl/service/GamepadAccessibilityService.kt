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

        val sources = ev.source
        val inputManager = getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager
        val deviceSources = try { inputManager.getInputDevice(ev.deviceId)?.sources ?: 0 } catch (_: Exception) { 0 }
        val combined = sources or deviceSources
        val isGamepadOrDpad = (combined and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (combined and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (combined and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
                (combined and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD

        if (!isGamepadOrDpad) return false
        // KEY_UP 也消费：映射捕获期间不把释放动作继续交给系统导航，
        // 防止 D-pad 上/下被系统当作页面滚动。
        if (ev.action == KeyEvent.ACTION_UP) return true
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        if (ev.repeatCount > 0) return true

        val code = GamepadEngine.mapKeyEventToCode(ev) ?: return false
        lastKeyEventTime = System.currentTimeMillis()
        val deviceId = ev.deviceId

        Log.i(TAG, "gamepad key: device=$deviceId code=$code")
        // One input path only: capture has priority while the app is configuring mappings;
        // otherwise route to the foreground service, with a safe fallback during startup.
        val captureConsumed = InputHub.dispatchInputForCapture(deviceId, code)
        if (!captureConsumed) {
            if (BackgroundInputService.isRunning) {
                InputHub.dispatchInputToAction(deviceId, code)
            } else {
                ActionDispatcher(applicationContext).handleInput(deviceId, code)
            }
        }
        return true
    }
}

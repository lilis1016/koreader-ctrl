package com.koreader.ctrl.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍按键监听服务。
 *
 * 这是「其它 App 处于前台 / 手机熄屏时仍能收到手柄按键」的**唯一可行路径**。
 *
 * 限制（如实说明）：
 *  - 必须由用户在系统「设置 → 无障碍」中手动开启，App 无法自行弹出系统授权页。
 *  - AccessibilityServiceInfo 的 flags 必须包含
 *    FLAG_REQUEST_FILTER_KEY_EVENTS，否则 onKeyEvent 不会被调用。
 *  - 部分 ROM（含部分 MIUI/EMUI 定制）即使开启后也不派发手柄按键事件，
 *    此时会退化为 Foreground Service 的轮询模式。
 *  - 该服务不会读取屏幕内容，仅用于接收按键，不做界面自动化。
 */
class GamepadAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GamepadA11y"

        @Volatile
        var isEnabled = false
            private set

        @Volatile
        var lastKeyEventTime = 0L
            private set

        /** 供 UI 查询 */
        fun isRunning(): Boolean = isEnabled
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isEnabled = true
        Log.i(TAG, "accessibility service connected")
        BackgroundInputService.isRunning.let { _ ->
            // 若前台服务未运行，则一并启动，保证两种模式都有接收路径
            try {
                BackgroundInputService.start(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "start fg failed", e)
            }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本服务不处理界面事件，仅用于按键过滤
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(keyEvent: KeyEvent?): Boolean {
        val ev = keyEvent ?: return false

        // 只处理按下瞬间，抑制长按重复触发
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        if (ev.repeatCount > 0) return true

        lastKeyEventTime = System.currentTimeMillis()

        // 判断是否来自手柄
        val sources = ev.source
        val isGamepad = (sources and android.view.InputDevice.SOURCE_GAMEPAD != 0) ||
                (sources and android.view.InputDevice.SOURCE_JOYSTICK != 0)

        if (!isGamepad) {
            // 部分 ROM 在手柄按键上只上报 SOURCE_KEYBOARD，这里做兼容：
            // 只有当按键码属于 Gamepad 区间才认为是手柄
            val kc = ev.keyCode
            val looksLikeGamepad = (kc >= GamepadEngine.KEYCODE_BUTTON_1 &&
                    kc <= GamepadEngine.KEYCODE_BUTTON_16) ||
                    kc == KeyEvent.KEYCODE_DPAD_UP ||
                    kc == KeyEvent.KEYCODE_DPAD_DOWN ||
                    kc == KeyEvent.KEYCODE_DPAD_LEFT ||
                    kc == KeyEvent.KEYCODE_DPAD_RIGHT
            if (!looksLikeGamepad) return false
        }

        val deviceId = ev.deviceId
        val d = BackgroundInputService.dispatcher
        if (d == null) {
            Log.w(TAG, "dispatcher null, event dropped")
            return false
        }

        // 复用 GamepadEngine 的编号映射规则
        val buttonIndex = buttonIndexOf(ev.keyCode)
        if (buttonIndex == null) return false

        Log.i(TAG, "gamepad key: device=$deviceId button=$buttonIndex")
        d.handleInput(deviceId, "Gamepad_$buttonIndex")
        return true
    }

    private fun buttonIndexOf(keyCode: Int): Int? {
        if (keyCode >= GamepadEngine.KEYCODE_BUTTON_1 && keyCode <= GamepadEngine.KEYCODE_BUTTON_16) {
            return keyCode - GamepadEngine.KEYCODE_BUTTON_1
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 12
            KeyEvent.KEYCODE_DPAD_DOWN -> 13
            KeyEvent.KEYCODE_DPAD_LEFT -> 14
            KeyEvent.KEYCODE_DPAD_RIGHT -> 15
            else -> null
        }
    }
}
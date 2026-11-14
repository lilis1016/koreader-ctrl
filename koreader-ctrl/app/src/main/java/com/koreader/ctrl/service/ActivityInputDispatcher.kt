package com.koreader.ctrl.service

import com.koreader.ctrl.gamepad.GamepadEngine

/**
 * Activity 窗口的输入事件分发器。
 *
 * ============================ 实现说明（重要）===========================
 * 原先这里用的是 android.view.InputEventReceiver + Window.addInputEventReceiver，
 * 但这两个 API 在 Android 公开 SDK 中是 @hide（不对第三方应用开放），
 * 无法通过公开 SDK 编译打包。
 *
 * 现在改用 Activity 的公开分发入口：
 *   - Activity.dispatchKeyEvent(KeyEvent)        -> 按键
 *   - Activity.dispatchGenericMotionEvent(MotionEvent) -> 摇杆
 * 这两个方法是 public 的，所有版本都可用，且 App 前台时必然收到手柄事件。
 *
 * 生效方式：在 MainActivity 中 override 这两个方法并转发到本分发器。
 * ========================================================================
 */
class ActivityInputDispatcher(private val engine: GamepadEngine) {

    /** 返回 true 表示已消费该事件（避免系统再做默认处理，例如触发方向键滚动） */
    fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        return try {
            engine.handleKeyEvent(event)
        } catch (e: Exception) {
            false
        }
    }

    fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        return try {
            engine.handleMotionEvent(event)
        } catch (e: Exception) {
            false
        }
    }
}
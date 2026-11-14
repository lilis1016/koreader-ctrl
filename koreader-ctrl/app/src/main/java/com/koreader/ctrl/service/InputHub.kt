package com.koreader.ctrl.service

import android.content.Context
import com.koreader.ctrl.gamepad.GamepadEngine
import com.koreader.ctrl.model.GamepadInfo

/**
 * 全局唯一的输入中枢。
 *
 * 为什么需要它（修复了一个真实的重复触发缺陷）：
 *   - Activity 通过 InputEventReceiver 接收 MotionEvent / KeyEvent
 *   - BackgroundInputService 通过 InputManager 轮询轴状态
 *   两者若各自持有独立的 GamepadEngine，会各自维护一份「轴方向」状态，
 *   同一个摇杆推动会被两个来源各判定一次「方向改变」，
 *   导致同一条指令被发送两次（例如一次翻页变成翻两页）。
 *
 *   共享同一个 Engine 实例后，轴状态只有一份：
 *   MotionEvent 先更新了状态，轮询再看到时就不会重复触发。
 *
 * 同时这里统一维护「当前手柄列表」，供动作解析使用。
 */
object InputHub {

    @Volatile
    private var engine: GamepadEngine? = null

    /** 动作派发回调（由当前生效的一方注册：服务在跑则服务，否则 Activity） */
    @Volatile
    private var actionListener: ((Int, String) -> Unit)? = null

    /** 捕获回调（用于绑定按键），始终会被调用 */
    @Volatile
    private var captureListener: ((Int, String) -> Unit)? = null

    @Volatile
    private var pads: List<GamepadInfo> = emptyList()

    fun engine(context: Context): GamepadEngine {
        return synchronized(this) {
            engine ?: GamepadEngine(context.applicationContext) { deviceId, code ->
                // 先给捕获器（UI 绑定），再给动作派发器
                captureListener?.invoke(deviceId, code)
                actionListener?.invoke(deviceId, code)
            }.also { engine = it }
        }
    }

    /** 当前可见的手柄列表（由轮询/枚举更新） */
    fun currentPads(): List<GamepadInfo> = pads

    fun updatePads(list: List<GamepadInfo>) {
        pads = list
    }

    fun setActionListener(fn: ((Int, String) -> Unit)?) {
        actionListener = fn
    }

    fun setCaptureListener(fn: ((Int, String) -> Unit)?) {
        captureListener = fn
    }

    fun clear() {
        actionListener = null
        captureListener = null
        pads = emptyList()
        synchronized(this) { engine = null }
    }
}
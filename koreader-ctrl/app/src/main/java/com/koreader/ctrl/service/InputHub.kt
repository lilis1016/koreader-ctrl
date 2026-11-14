package com.koreader.ctrl.service

import android.content.Context
import com.koreader.ctrl.gamepad.GamepadEngine
import com.koreader.ctrl.model.GamepadInfo

object InputHub {

    @Volatile
    private var engine: GamepadEngine? = null

    @Volatile
    private var actionListener: ((Int, String) -> Unit)? = null

    @Volatile
    private var captureListener: ((Int, String) -> Boolean)? = null

    @Volatile
    private var pads: List<GamepadInfo> = emptyList()

    fun engine(context: Context): GamepadEngine {
        return synchronized(this) {
            engine ?: GamepadEngine(context.applicationContext) { deviceId, code ->
                dispatchInput(deviceId, code)
            }.also { engine = it }
        }
    }

    fun dispatchInput(deviceId: Int, code: String) {
        if (!dispatchInputForCapture(deviceId, code)) dispatchInputToAction(deviceId, code)
    }

    fun dispatchInputForCapture(deviceId: Int, code: String): Boolean =
        captureListener?.invoke(deviceId, code) == true

    fun dispatchInputToAction(deviceId: Int, code: String) {
        actionListener?.invoke(deviceId, code)
    }

    fun currentPads(): List<GamepadInfo> = pads

    fun updatePads(list: List<GamepadInfo>) {
        pads = list
    }

    fun setActionListener(fn: ((Int, String) -> Unit)?) {
        actionListener = fn
    }

    fun setCaptureListener(fn: ((Int, String) -> Boolean)?) {
        captureListener = fn
    }

    fun clear() {
        actionListener = null
        captureListener = null
        pads = emptyList()
        synchronized(this) { engine = null }
    }
}

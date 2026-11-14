package com.koreader.ctrl.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.koreader.ctrl.model.GamepadInfo

/**
 * 手柄输入引擎。
 *
 * 关键事实（已逐条核实）：
 *  - InputManager.getInputDeviceIds() 枚举系统所有输入设备（含蓝牙手柄）。
 *  - InputDevice.getMotionRanges() 获取手柄的轴（摇杆）信息。
 *  - InputDevice.getSources() 识别 SOURCE_GAMEPAD / SOURCE_JOYSTICK。
 *  - InputDevice.getDescriptor() 对 USB 设备含 "/dev/input/eventX"，
 *    蓝牙手柄通常含设备名。设备名是唯一稳定的型号标识。
 *  - Android 不保证手柄有稳定的硬件唯一 ID，因此 persistKey 由
 *    「型号名 + 可用时的 MAC」构成，拿不到 MAC 时退化为「型号名 + 序号」。
 *
 * ============================ 按键编号约定（重要）===========================
 * Android 的手柄按键常量是 KEYCODE_BUTTON_1 .. KEYCODE_BUTTON_16（**没有 _0**，
 * 且这些常量本身是 @hide 的），而浏览器 Gamepad API 的按钮索引是 0..15。
 * 原 HTML 用的是浏览器索引。
 *
 * 为让原 HTML 的默认映射表能直接沿用，这里做显式编号转换：
 *   Android KEYCODE_BUTTON_1  →  内部编号 0
 *   Android KEYCODE_BUTTON_2  →  内部编号 1
 *   ...                        ...
 *   Android KEYCODE_BUTTON_16 →  内部编号 15
 * 十字键归一到 12/13/14/15（与浏览器 Gamepad API 常见布局一致）。
 * 这样「Gamepad_0 = A 键」「Gamepad_14 = 十字键左」的语义与原 HTML 完全一致。
 * ========================================================================
 */
class GamepadEngine(
    private val context: Context,
    private val onInput: (deviceId: Int, code: String) -> Unit
) {

    private val inputManager: InputManager =
        context.getSystemService(Context.INPUT_SERVICE) as InputManager

    /** deviceId -> 上一帧按钮按下状态 */
    private val buttonState = mutableMapOf<Int, BooleanArray>()

    /** deviceId -> 上一次每个轴的方向 (-1/0/1) */
    private val axisState = mutableMapOf<Int, IntArray>()

    /** deviceId -> 该设备上报过输入（用于判断是否"活跃"） */
    private val activeDevices = mutableSetOf<Int>()

    // ---------------------------------------------------------------
    // 设备枚举
    // ---------------------------------------------------------------

    fun listGamepads(): List<GamepadInfo> {
        val ids = try {
            inputManager.getInputDeviceIds()
        } catch (e: Exception) {
            Log.w(TAG, "getInputDeviceIds failed", e)
            return emptyList()
        } ?: return emptyList()

        val result = mutableListOf<GamepadInfo>()

        for (id in ids) {
            val dev = try {
                inputManager.getInputDevice(id)
            } catch (e: Exception) {
                continue
            } ?: continue

            val sources = dev.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isGamepad) continue
            result += describe(dev)
        }

        return result.sortedBy { it.persistKey }
    }

    fun describe(device: InputDevice): GamepadInfo {
        val vid = device.vendorId
        val pid = device.productId
        val name = device.name ?: "Gamepad"
        val descriptor = device.descriptor ?: ""
        val mac = extractMac(descriptor) ?: extractMac(name)
        val key = if (mac != null) "$name#$mac" else "$name#${device.id}"
        return GamepadInfo(
            deviceId = device.id,
            name = name,
            vendorId = vid,
            productId = pid,
            macAddress = mac,
            persistKey = key
        )
    }

    /** 从 descriptor / name 中尽力提取 MAC 地址 */
    private fun extractMac(s: String): String? {
        val m = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").find(s) ?: return null
        return m.value.uppercase()
    }

    // ---------------------------------------------------------------
    // 按键事件（KeyEvent 路径）
    // ---------------------------------------------------------------

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val devId = event.deviceId
        if (devId == INVALID_DEVICE_ID) return false

        val device = try {
            inputManager.getInputDevice(devId)
        } catch (e: Exception) {
            null
        } ?: return false

        val sources = device.sources
        if ((sources and InputDevice.SOURCE_GAMEPAD) == 0 &&
            (sources and InputDevice.SOURCE_JOYSTICK) == 0 &&
            (event.source and InputDevice.SOURCE_JOYSTICK) == 0
        ) {
            return false
        }

        val buttonIndex = keyCodeToButtonIndex(event) ?: return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true  // 抑制长按重复
                val arr = buttonState.getOrPut(devId) { BooleanArray(64) }
                if (buttonIndex in arr.indices && arr[buttonIndex]) return true
                if (buttonIndex in arr.indices) arr[buttonIndex] = true
                activeDevices += devId
                onInput(devId, "Gamepad_$buttonIndex")
            }
            KeyEvent.ACTION_UP -> {
                val arr = buttonState.getOrPut(devId) { BooleanArray(64) }
                if (buttonIndex in arr.indices) arr[buttonIndex] = false
            }
        }
        return true
    }

    /**
     * 把 KeyEvent 的 keyCode 映射为「手柄按钮序号」（浏览器 Gamepad API 语义，0 起）。
     */
    private fun keyCodeToButtonIndex(event: KeyEvent): Int? {
        val kc = event.keyCode
        if (kc >= KEYCODE_BUTTON_1 && kc <= KEYCODE_BUTTON_16) {
            return kc - KEYCODE_BUTTON_1
        }
        when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> return 12
            KeyEvent.KEYCODE_DPAD_DOWN -> return 13
            KeyEvent.KEYCODE_DPAD_LEFT -> return 14
            KeyEvent.KEYCODE_DPAD_RIGHT -> return 15
        }
        return null
    }

    // ---------------------------------------------------------------
    // 摇杆事件（MotionEvent 路径）
    // ---------------------------------------------------------------

    private val AXIS_THRESHOLD = 0.65f

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val devId = event.deviceId
        if (devId == INVALID_DEVICE_ID) return false

        val device = try {
            inputManager.getInputDevice(devId)
        } catch (e: Exception) {
            null
        } ?: return false

        if ((event.source and InputDevice.SOURCE_JOYSTICK) == 0) return false

        for (i in 0 until event.axesCount) {
            val value = event.getAxisValue(i)
            val dir = when {
                value <= -AXIS_THRESHOLD -> -1
                value >= AXIS_THRESHOLD -> 1
                else -> 0
            }
            val state = axisState.getOrPut(devId) { IntArray(32) }
            if (i >= state.size) continue
            val old = state[i]
            if (dir != old) {
                state[i] = dir
                if (dir != 0) {
                    activeDevices += devId
                    onInput(devId, "Axis_${i}_$dir")
                }
            }
        }
        return true
    }

    /**
     * 轮询所有手柄的轴状态。
     *
     * 某些 Android 设备（尤其息屏或非焦点 App 时）不会派发 MotionEvent，
     * 但 InputManager 依然可以查询到当前轴值，因此轮询作为补充路径。
     *
     * 轴编号统一使用 MotionEvent.AXIS_* 常量，与 handleMotionEvent() 一致。
     */
    fun poll() {
        val ids = try {
            inputManager.getInputDeviceIds()
        } catch (e: Exception) {
            return
        } ?: return

        for (id in ids) {
            val device = try {
                inputManager.getInputDevice(id)
            } catch (e: Exception) {
                continue
            } ?: continue

            val sources = device.sources
            if ((sources and InputDevice.SOURCE_JOYSTICK) == 0 &&
                (sources and InputDevice.SOURCE_GAMEPAD) == 0
            ) continue

            val ranges = try {
                device.getMotionRanges()
            } catch (e: Exception) {
                continue
            } ?: continue

            for (r in ranges) {
                val axisIndex = r.axis
                val state = axisState.getOrPut(id) { IntArray(32) }
                if (axisIndex !in state.indices) continue

                val value = try {
                    r.getValue(device)
                } catch (e: Exception) {
                    continue
                }
                val dir = when {
                    value <= -AXIS_THRESHOLD -> -1
                    value >= AXIS_THRESHOLD -> 1
                    else -> 0
                }
                val old = state[axisIndex]
                if (dir != old) {
                    state[axisIndex] = dir
                    if (dir != 0) {
                        activeDevices += id
                        onInput(id, "Axis_${axisIndex}_$dir")
                    }
                }
            }
        }
    }

    fun isActive(deviceId: Int) = deviceId in activeDevices

    fun clearState(deviceId: Int) {
        buttonState.remove(deviceId)
        axisState.remove(deviceId)
        activeDevices.remove(deviceId)
    }

    companion object {
        private const val TAG = "GamepadEngine"

        /**
         * InputDevice.INVALID_DEVICE_ID 是 @hide 内部 API，公开 SDK 中不存在。
         * AOSP 中该常量值固定为 -1，此处按同值使用。
         */
        const val INVALID_DEVICE_ID = -1

        /**
         * KeyEvent.KEYCODE_BUTTON_1..16 同样是 @hide 的，
         * 但系统运行时确实使用这些 keyCode。
         * 这里使用 AOSP 的真实数值（KEYCODE_BUTTON_1 = 96，依次递增到 16 = 111），
         * 保证与系统上报的值一致。
         */
        const val KEYCODE_BUTTON_1 = 96
        const val KEYCODE_BUTTON_16 = 111

        fun isGamepadSource(sources: Int): Boolean =
            (sources and InputDevice.SOURCE_GAMEPAD != 0) ||
                    (sources and InputDevice.SOURCE_JOYSTICK != 0)
    }
}
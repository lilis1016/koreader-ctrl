package com.koreader.ctrl.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.koreader.ctrl.model.GamepadInfo
import com.koreader.ctrl.model.MappingPreset

/**
 * 手柄输入识别与转换引擎。
 */
class GamepadEngine(
    private val context: Context,
    private val onInput: (deviceId: Int, code: String) -> Unit
) {

    private val inputManager: InputManager =
        context.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val pressedCodes = mutableMapOf<Int, MutableSet<String>>()
    private val axisState = mutableMapOf<Int, MutableMap<Int, Int>>()
    private val hatState = mutableMapOf<Int, MutableMap<Int, Int>>()
    private val activeDevices = mutableSetOf<Int>()

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

            if (dev.isVirtual) continue

            val sources = dev.sources
            val isGamepadOrJoystick =
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                        (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

            if (!isGamepadOrJoystick) continue

            val name = dev.name ?: ""
            if (isBuiltInSystemKeyDevice(name, sources)) continue

            result += describe(dev)
        }

        return result.sortedBy { it.persistKey }
    }

    private fun isBuiltInSystemKeyDevice(name: String, sources: Int): Boolean {
        val lower = name.lowercase()
        val hasGamepadOrJoystick =
            (sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                    (sources and InputDevice.SOURCE_JOYSTICK) != 0
        if (hasGamepadOrJoystick) return false
        val builtInKeywords = listOf("gpio", "qpnp", "pmic", "pon", "mtk-kpd", "headset", "volume", "powerkey", "touchscreen")
        return builtInKeywords.any { lower.contains(it) }
    }

    fun describe(device: InputDevice): GamepadInfo {
        val vid = device.vendorId
        val pid = device.productId
        val name = device.name ?: "游戏手柄"
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

    private fun extractMac(s: String): String? {
        val m = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").find(s) ?: return null
        return m.value.uppercase()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (isSystemReservedKey(event.keyCode)) return false

        val devId = event.deviceId
        val device = if (devId != INVALID_DEVICE_ID) {
            try {
                inputManager.getInputDevice(devId)
            } catch (e: Exception) {
                null
            }
        } else null

        val code = mapKeyEventToCode(event) ?: return false

        val devSources = device?.sources ?: 0
        val evSources = event.source
        val combinedSources = devSources or evSources

        val isSupportedSource =
            (combinedSources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                    (combinedSources and InputDevice.SOURCE_JOYSTICK) != 0 ||
                    (combinedSources and InputDevice.SOURCE_DPAD) != 0 ||
                    (combinedSources and InputDevice.SOURCE_KEYBOARD) != 0

        if (!isSupportedSource) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                val pressed = pressedCodes.getOrPut(devId) { mutableSetOf() }
                if (!pressed.add(code)) return true
                activeDevices += devId
                onInput(devId, code)
                return true
            }
            KeyEvent.ACTION_UP -> {
                pressedCodes[devId]?.remove(code)
                return true
            }
        }
        return false
    }

    private val AXIS_THRESHOLD = 0.55f

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val devId = event.deviceId
        if (devId == INVALID_DEVICE_ID) return false

        val device = try {
            inputManager.getInputDevice(devId)
        } catch (e: Exception) {
            null
        }

        val combinedSources = (device?.sources ?: 0) or event.source
        val isMotionGamepad =
            (combinedSources and InputDevice.SOURCE_JOYSTICK) != 0 ||
                    (combinedSources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                    (combinedSources and InputDevice.SOURCE_DPAD) != 0
        if (!isMotionGamepad) return false

        var handled = false

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val devHat = hatState.getOrPut(devId) { mutableMapOf() }

        val dirHatX = when {
            hatX <= -AXIS_THRESHOLD -> -1
            hatX >= AXIS_THRESHOLD -> 1
            else -> 0
        }
        val oldHatX = devHat[MotionEvent.AXIS_HAT_X] ?: 0
        if (dirHatX != oldHatX) {
            devHat[MotionEvent.AXIS_HAT_X] = dirHatX
            handled = true
            if (dirHatX == -1) {
                activeDevices += devId
                onInput(devId, CODE_DPAD_LEFT)
            } else if (dirHatX == 1) {
                activeDevices += devId
                onInput(devId, CODE_DPAD_RIGHT)
            }
        }

        val dirHatY = when {
            hatY <= -AXIS_THRESHOLD -> -1
            hatY >= AXIS_THRESHOLD -> 1
            else -> 0
        }
        val oldHatY = devHat[MotionEvent.AXIS_HAT_Y] ?: 0
        if (dirHatY != oldHatY) {
            devHat[MotionEvent.AXIS_HAT_Y] = dirHatY
            handled = true
            if (dirHatY == -1) {
                activeDevices += devId
                onInput(devId, CODE_DPAD_UP)
            } else if (dirHatY == 1) {
                activeDevices += devId
                onInput(devId, CODE_DPAD_DOWN)
            }
        }

        val ranges = try {
            device?.motionRanges ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val devAxes = axisState.getOrPut(devId) { mutableMapOf() }
        for (range in ranges) {
            val axisIndex = range.axis
            if (axisIndex == MotionEvent.AXIS_HAT_X || axisIndex == MotionEvent.AXIS_HAT_Y) {
                continue
            }

            val value = event.getAxisValue(axisIndex)
            val dir = when {
                value <= -AXIS_THRESHOLD -> -1
                value >= AXIS_THRESHOLD -> 1
                else -> 0
            }
            val old = devAxes[axisIndex] ?: 0
            if (dir != old) {
                devAxes[axisIndex] = dir
                handled = true
                if (dir != 0) {
                    activeDevices += devId
                    onInput(devId, "Axis_${axisIndex}_$dir")
                }
            }
        }

        return handled
    }

    fun poll() {}

    fun isActive(deviceId: Int) = deviceId in activeDevices

    fun clearState(deviceId: Int) {
        pressedCodes.remove(deviceId)
        axisState.remove(deviceId)
        hatState.remove(deviceId)
        activeDevices.remove(deviceId)
    }

    companion object {
        private const val TAG = "GamepadEngine"

        const val INVALID_DEVICE_ID = -1
        const val KEYCODE_BUTTON_1 = 96
        const val KEYCODE_BUTTON_16 = 111

        const val CODE_DPAD_UP = "D-pad_UP"
        const val CODE_DPAD_DOWN = "D-pad_DOWN"
        const val CODE_DPAD_LEFT = "D-pad_LEFT"
        const val CODE_DPAD_RIGHT = "D-pad_RIGHT"

        fun isSystemReservedKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
                    keyCode == KeyEvent.KEYCODE_POWER ||
                    keyCode == KeyEvent.KEYCODE_HOME
        }

        fun mapKeyEventToCode(event: KeyEvent): String? {
            return mapKeyCodeToCode(event.keyCode, event.scanCode, event.source)
        }

        fun mapKeyCodeToCode(keyCode: Int, scanCode: Int = 0, source: Int = 0): String? {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> return CODE_DPAD_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> return CODE_DPAD_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> return CODE_DPAD_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> return CODE_DPAD_RIGHT
            }

            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> "Gamepad_0"
                KeyEvent.KEYCODE_BUTTON_B -> "Gamepad_1"
                KeyEvent.KEYCODE_BUTTON_X -> "Gamepad_2"
                KeyEvent.KEYCODE_BUTTON_Y -> "Gamepad_3"
                KeyEvent.KEYCODE_BUTTON_L1 -> "Gamepad_4"
                KeyEvent.KEYCODE_BUTTON_R1 -> "Gamepad_5"
                KeyEvent.KEYCODE_BUTTON_L2 -> "Gamepad_6"
                KeyEvent.KEYCODE_BUTTON_R2 -> "Gamepad_7"
                KeyEvent.KEYCODE_BUTTON_SELECT -> "Gamepad_8"
                KeyEvent.KEYCODE_BUTTON_START -> "Gamepad_9"
                KeyEvent.KEYCODE_BUTTON_THUMBL -> "Gamepad_10"
                KeyEvent.KEYCODE_BUTTON_THUMBR -> "Gamepad_11"
                KeyEvent.KEYCODE_BUTTON_C -> "Gamepad_16"
                KeyEvent.KEYCODE_BUTTON_Z -> "Gamepad_17"
                KeyEvent.KEYCODE_BUTTON_MODE -> "Gamepad_18"
                KeyEvent.KEYCODE_DPAD_CENTER -> "Gamepad_19"
                else -> {
                    if (keyCode in KEYCODE_BUTTON_1..KEYCODE_BUTTON_16) {
                        val offset = keyCode - KEYCODE_BUTTON_1
                        "Gamepad_${20 + offset}"
                    } else if (KeyEvent.isGamepadButton(keyCode)) {
                        "Gamepad_$keyCode"
                    } else {
                        val fromGamepad = (source and InputDevice.SOURCE_GAMEPAD) != 0 ||
                                (source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                                (source and InputDevice.SOURCE_DPAD) != 0
                        if (fromGamepad && keyCode in listOf(
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER,
                                KeyEvent.KEYCODE_SPACE,
                                KeyEvent.KEYCODE_PAGE_UP,
                                KeyEvent.KEYCODE_PAGE_DOWN
                            )
                        ) {
                            "Key_$keyCode"
                        } else {
                            null
                        }
                    }
                }
            }
        }

        fun formatCodeForDisplay(rawCode: String): String {
            val code = MappingPreset.normalizeCode(rawCode)
            return when {
                code == CODE_DPAD_UP -> "十字键上"
                code == CODE_DPAD_DOWN -> "十字键下"
                code == CODE_DPAD_LEFT -> "十字键左"
                code == CODE_DPAD_RIGHT -> "十字键右"
                code.startsWith("Gamepad_") -> {
                    val idx = code.removePrefix("Gamepad_").toIntOrNull()
                    if (idx != null) "手柄按钮 ${idx + 1}" else code
                }
                code.startsWith("Axis_") -> {
                    val parts = code.removePrefix("Axis_").split("_")
                    if (parts.size == 2) {
                        val axis = parts[0].toIntOrNull()
                        val dir = parts[1].toIntOrNull()
                        when (axis) {
                            MotionEvent.AXIS_X -> when (dir) {
                                -1 -> "摇杆左"
                                1 -> "摇杆右"
                                else -> "摇杆X"
                            }
                            MotionEvent.AXIS_Y -> when (dir) {
                                -1 -> "摇杆上"
                                1 -> "摇杆下"
                                else -> "摇杆Y"
                            }
                            MotionEvent.AXIS_Z -> when (dir) {
                                -1 -> "右摇杆左"
                                1 -> "右摇杆右"
                                else -> "右摇杆X"
                            }
                            MotionEvent.AXIS_RZ -> when (dir) {
                                -1 -> "右摇杆上"
                                1 -> "右摇杆下"
                                else -> "右摇杆Y"
                            }
                            else -> {
                                val dirText = if (dir == -1) "-" else if (dir == 1) "+" else ""
                                "摇杆轴${parts[0]}$dirText"
                            }
                        }
                    } else code
                }
                code.startsWith("Key_") -> {
                    val kc = code.removePrefix("Key_").toIntOrNull()
                    when (kc) {
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "手柄确认键"
                        KeyEvent.KEYCODE_SPACE -> "手柄空格键"
                        KeyEvent.KEYCODE_PAGE_UP -> "手柄上翻键"
                        KeyEvent.KEYCODE_PAGE_DOWN -> "手柄下翻键"
                        else -> "手柄按键 $kc"
                    }
                }
                else -> code
            }
        }

        fun isGamepadSource(sources: Int): Boolean =
            (sources and InputDevice.SOURCE_GAMEPAD != 0) ||
                    (sources and InputDevice.SOURCE_JOYSTICK != 0) ||
                    (sources and InputDevice.SOURCE_DPAD != 0)
    }
}

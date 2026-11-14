package com.koreader.ctrl.data

import android.content.Context
import android.content.SharedPreferences
import com.koreader.ctrl.model.Action
import com.koreader.ctrl.model.MappingPreset
import org.json.JSONObject
import java.util.UUID

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("koreader_ctrl", Context.MODE_PRIVATE)

    var kindleIp: String
        get() = sp.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        set(v) {
            sp.edit().putString(KEY_IP, v.trim()).commit()
        }

    var serviceRunning: Boolean
        get() = sp.getBoolean(KEY_SERVICE, false)
        set(v) {
            sp.edit().putBoolean(KEY_SERVICE, v).commit()
        }

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) {
            sp.edit().putBoolean(KEY_AUTOSTART, v).commit()
        }

    var backgroundRequestsEnabled: Boolean
        get() = sp.getBoolean(KEY_BG_REQ, true)
        set(v) {
            sp.edit().putBoolean(KEY_BG_REQ, v).commit()
        }

    fun loadPresets(): Pair<MutableMap<String, MappingPreset>, MutableMap<String, String>> {
        val presets = mutableMapOf<String, MappingPreset>()
        val devicePresets = mutableMapOf<String, String>()

        val raw = sp.getString(KEY_PRESETS, null) ?: return Pair(presets, devicePresets)
        return try {
            val root = JSONObject(raw)
            val pObj = root.optJSONObject("presets")
            if (pObj != null) {
                pObj.keys().forEach { id ->
                    val o = pObj.optJSONObject(id) ?: return@forEach
                    presets[id] = MappingPreset.fromJson(o)
                }
            }
            val dObj = root.optJSONObject("devicePresets")
            if (dObj != null) {
                dObj.keys().forEach { k -> devicePresets[k] = dObj.optString(k) }
            }
            Pair(presets, devicePresets)
        } catch (e: Exception) {
            Pair(presets, devicePresets)
        }
    }

    fun savePresets(
        presets: Map<String, MappingPreset>,
        devicePresets: Map<String, String>
    ) {
        val root = JSONObject()
        val pObj = JSONObject()
        presets.forEach { (id, p) ->
            p.enforceGlobalUniqueness()
            pObj.put(id, p.toJson())
        }
        root.put("presets", pObj)
        val dObj = JSONObject()
        devicePresets.forEach { (k, v) -> dObj.put(k, v) }
        root.put("devicePresets", dObj)
        sp.edit().putString(KEY_PRESETS, root.toString()).commit()
    }

    fun applyDefaultBindings(preset: MappingPreset) {
        preset.bindings.clear()
        preset.bindUnique(Action.PREV_PAGE, "D-pad_LEFT")
        preset.bindUnique(Action.NEXT_PAGE, "D-pad_RIGHT")
        preset.bindUnique(Action.INCREASE_FL, "D-pad_UP")
        preset.bindUnique(Action.DECREASE_FL, "D-pad_DOWN")
        preset.bindUnique(Action.BACK, "Gamepad_1")
        preset.bindUnique(Action.CONFIRM, "Gamepad_0")
    }

    fun ensureDefaultPreset(): MappingPreset {
        val (presets, devicePresets) = loadPresets()
        presets.values.firstOrNull()?.let { existing ->
            existing.enforceGlobalUniqueness()
            return existing
        }
        val p = MappingPreset(
            id = UUID.randomUUID().toString(),
            name = "默认预设"
        )
        applyDefaultBindings(p)
        savePresets(presets + (p.id to p), devicePresets)
        return p
    }

    fun resetPresetToDefault(preset: MappingPreset) {
        val (presets, devicePresets) = loadPresets()
        applyDefaultBindings(preset)
        presets[preset.id] = preset
        savePresets(presets, devicePresets)
    }

    fun presetIdForDevice(persistKey: String): String? {
        val (_, devicePresets) = loadPresets()
        return devicePresets[persistKey]
    }

    fun assignPreset(deviceKey: String, presetId: String) {
        val (presets, devicePresets) = loadPresets()
        devicePresets[deviceKey] = presetId
        savePresets(presets, devicePresets)
    }

    companion object {
        const val DEFAULT_IP = "192.168.31.114"
        private const val KEY_IP = "savedKindleIp"
        private const val KEY_SERVICE = "serviceRunning"
        private const val KEY_AUTOSTART = "autoStart"
        private const val KEY_BG_REQ = "bgRequests"
        private const val KEY_PRESETS = "joyconKeyMap"
    }
}

package com.koreader.ctrl.data

import android.content.Context
import android.content.SharedPreferences
import com.koreader.ctrl.model.Action
import com.koreader.ctrl.model.MappingPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地配置存储。使用 SharedPreferences，语义对齐原 HTML 的 localStorage：
 *  - savedKindleIp      -> Kindle IP
 *  - joyconKeyMap       -> 预设集合 + 预设与手柄的绑定
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("koreader_ctrl", Context.MODE_PRIVATE)

    // ---------------- Kindle IP ----------------

    var kindleIp: String
        get() = sp.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        set(v) = sp.edit().putString(KEY_IP, v.trim()).apply()

    // ---------------- 服务状态 ----------------

    var serviceRunning: Boolean
        get() = sp.getBoolean(KEY_SERVICE, false)
        set(v) = sp.edit().putBoolean(KEY_SERVICE, v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    /** 允许在后台自动发请求（网络请求不再受前台限制） */
    var backgroundRequestsEnabled: Boolean
        get() = sp.getBoolean(KEY_BG_REQ, true)
        set(v) = sp.edit().putBoolean(KEY_BG_REQ, v).apply()

    // ---------------- 预设管理 ----------------

    /**
     * 读取全部预设。结构：
     * {
     *   "presets": { "<presetId>": {...preset json...} },
     *   "devicePresets": { "<persistKey>": "<presetId>" }
     * }
     */
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
        presets.forEach { (id, p) -> pObj.put(id, p.toJson()) }
        root.put("presets", pObj)
        val dObj = JSONObject()
        devicePresets.forEach { (k, v) -> dObj.put(k, v) }
        root.put("devicePresets", dObj)
        sp.edit().putString(KEY_PRESETS, root.toString()).apply()
    }

    /**
     * 首次运行时创建默认预设，绑定方式与原 HTML 的 defaultMap 完全一致：
     *   prev=Gamepad_14, next=Gamepad_15, return=Gamepad_1, confirm=Gamepad_0
     */
    fun ensureDefaultPreset(): MappingPreset {
        val (presets, _) = loadPresets()
        presets.values.firstOrNull()?.let { return it }
        val p = MappingPreset(
            id = UUID.randomUUID().toString(),
            name = "默认预设"
        )
        p.setCodes(Action.PREV_PAGE, listOf("Gamepad_14"))
        p.setCodes(Action.NEXT_PAGE, listOf("Gamepad_15"))
        p.setCodes(Action.BACK, listOf("Gamepad_1"))
        p.setCodes(Action.CONFIRM, listOf("Gamepad_0"))
        val (_, devicePresets) = loadPresets()
        savePresets(presets + (p.id to p), devicePresets)
        return p
    }

    // ---------------- 便捷：某个设备的预设 ----------------

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
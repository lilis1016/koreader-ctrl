package com.koreader.ctrl.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 可被映射到手柄按键的功能动作。
 *
 * 事件名与 KOReader HTTP Inspector 的 /koreader/event/<EventName> 一一对应，
 * 取值来自 KOReader 源码 frontend/apps/filemanager/filemanager.lua 与
 * frontend/apps/reader/reader.lua 中的 Event:new 定义。
 */
enum class Action(
    val actionId: String,
    val displayName: String,
    val eventName: String
) {
    PREV_PAGE("prev", "上一页", "GotoViewRel/-1"),
    NEXT_PAGE("next", "下一页", "GotoViewRel/1"),
    BACK("return", "返回/退出", "Close"),
    CONFIRM("confirm", "确认/菜单", "ShowMenu"),
    TOGGLE_NIGHT_MODE("night", "切换日/夜模式", "ToggleNightMode"),
    INCREASE_FL("fl_inc", "亮度增加", "IncreaseFlIntensity"),
    DECREASE_FL("fl_dec", "亮度减少", "DecreaseFlIntensity"),
    FULL_REFRESH("full_refresh", "全屏刷新", "FullRefresh"),
    SCREENSHOT("screenshot", "截屏", "Screenshot"),
    GAME_HALL("games", "游戏大厅", "ShowGames");

    companion object {
        fun byId(id: String): Action? = values().firstOrNull { it.actionId == id }
    }
}

/**
 * 一个动作可以绑定到多个手柄输入码（按键 + 摇杆方向）。
 */
data class ActionBinding(
    val actionId: String,
    /** 形如 Gamepad_0 / Gamepad_14 / Axis_0_-1 */
    val inputCodes: List<String>
) {
    val action: Action? get() = Action.byId(actionId)
}

/**
 * 一套映射预设。手柄型号 -> 预设 的映射保存在 Prefs 里。
 */
data class MappingPreset(
    val id: String,
    var name: String,
    /** actionId -> inputCodes */
    val bindings: MutableMap<String, MutableList<String>> = mutableMapOf()
) {
    fun codesFor(action: Action): List<String> = bindings[action.actionId] ?: emptyList()

    fun setCodes(action: Action, codes: List<String>) {
        if (codes.isEmpty()) bindings.remove(action.actionId)
        else bindings[action.actionId] = codes.toMutableList()
    }

    /** 该预设是否绑定了动作 */
    fun isBound(action: Action): Boolean = codesFor(action).isNotEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        val b = JSONObject()
        bindings.forEach { (k, v) -> b.put(k, JSONArray(v)) }
        o.put("bindings", b)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): MappingPreset {
            val p = MappingPreset(o.optString("id"), o.optString("name"))
            val b = o.optJSONObject("bindings") ?: return p
            b.keys().forEach { k ->
                val arr = b.optJSONArray(k) ?: return@forEach
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                if (list.isNotEmpty()) p.bindings[k] = list
            }
            return p
        }
    }
}

/**
 * 已连接手柄的信息。
 *
 * 注意：Android 无法可靠获取蓝牙手柄的硬件唯一标识，
 * 这里使用「型号名 + 连接时的 MAC/地址（若可得）」作为身份键，
 * 若地址不可得则退化为「型号名 + 序号」，由用户手动分配预设。
 */
data class GamepadInfo(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    /** 蓝牙 MAC 地址，若可得 */
    val macAddress: String?,
    /** 用于在设备重启后保持稳定绑定的持久化 key */
    val persistKey: String
) {
    val displayName: String get() = name.ifBlank { "Unknown Gamepad" }
    val shortModel: String
        get() = buildString {
            append(name.ifBlank { "Gamepad" })
            if (vendorId != 0 || productId != 0) {
                append(" (")
                append(String.format("%04X", vendorId))
                append(":")
                append(String.format("%04X", productId))
                append(")")
            }
        }
}
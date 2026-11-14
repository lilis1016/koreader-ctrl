package com.koreader.ctrl.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 可被映射到手柄按键的功能动作。
 *
 * 事件名与 KOReader HTTP Inspector 的 /koreader/event/<EventName> 一一对应。
 */
enum class Action(
    val actionId: String,
    val displayName: String,
    val description: String,
    val eventName: String
) {
    PREV_PAGE("prev", "上一页", "Kindle 翻到上一页", "GotoViewRel/-1"),
    NEXT_PAGE("next", "下一页", "Kindle 翻到下一页", "GotoViewRel/1"),
    BACK("return", "返回", "返回或退出当前界面", "Close"),
    CONFIRM("confirm", "确认", "确认或打开菜单", "ShowMenu"),
    INCREASE_FL("fl_inc", "亮度增加", "增加屏幕亮度", "IncreaseFlIntensity"),
    DECREASE_FL("fl_dec", "亮度减少", "降低屏幕亮度", "DecreaseFlIntensity"),
    FULL_REFRESH("full_refresh", "全屏刷新", "执行 KOReader 全屏刷新", "FullRefresh"),
    TOGGLE_NIGHT_MODE("night", "日夜模式切换", "切换日间和夜间模式", "ToggleNightMode"),
    SCREENSHOT("screenshot", "截屏", "执行截屏功能", "Screenshot"),
    GAME_HALL("games", "游戏大厅", "打开游戏大厅插件（需 Kindle 已安装插件）", "ShowGames");

    companion object {
        fun byId(id: String): Action? = values().firstOrNull { it.actionId == id }
    }
}

data class ActionBinding(
    val actionId: String,
    val inputCodes: List<String>
) {
    val action: Action? get() = Action.byId(actionId)
}

/**
 * 一套映射预设。支持【A 方案：全局唯一映射】。
 *
 * 规则：
 *  - 一个实体按键只能绑定一个功能。
 *  - 同一个实体按键不能同时绑定两个不同功能。
 *  - 将已绑定给 A 功能的按键重新分配给 B 功能时，自动解除 A 功能的旧绑定。
 */
data class MappingPreset(
    val id: String,
    var name: String,
    val bindings: MutableMap<String, MutableList<String>> = mutableMapOf()
) {
    fun codesFor(action: Action): List<String> = bindings[action.actionId] ?: emptyList()

    fun codeFor(action: Action): String? = bindings[action.actionId]?.firstOrNull()

    fun bindUnique(targetAction: Action, code: String): List<Action> {
        val canonical = normalizeCode(code)
        val aliases = equivalentCodes(canonical)
        val displaced = mutableListOf<Action>()

        for (act in Action.values()) {
            val existing = bindings[act.actionId] ?: continue
            val hadMatch = existing.any { normalizeCode(it) in aliases || it in aliases }
            if (hadMatch) {
                existing.removeAll { normalizeCode(it) in aliases || it in aliases }
                if (existing.isEmpty()) {
                    bindings.remove(act.actionId)
                }
                if (act != targetAction) {
                    displaced.add(act)
                }
            }
        }

        bindings[targetAction.actionId] = mutableListOf(canonical)
        return displaced
    }

    fun clearBinding(action: Action) {
        bindings.remove(action.actionId)
    }

    fun setCodes(action: Action, codes: List<String>) {
        if (codes.isEmpty()) {
            bindings.remove(action.actionId)
            return
        }
        val targetCode = normalizeCode(codes.last())
        bindUnique(action, targetCode)
    }

    fun findActionForCode(code: String): Action? {
        val canonical = normalizeCode(code)
        val aliases = equivalentCodes(canonical)
        for ((actionId, codes) in bindings) {
            if (codes.any { normalizeCode(it) in aliases || it in aliases }) {
                return Action.byId(actionId)
            }
        }
        return null
    }

    fun enforceGlobalUniqueness() {
        val seenCodes = mutableSetOf<String>()
        for (act in Action.values()) {
            val list = bindings[act.actionId] ?: continue
            val firstValid = list.map { normalizeCode(it) }.firstOrNull { it.isNotBlank() && it !in seenCodes }
            if (firstValid != null) {
                seenCodes.addAll(equivalentCodes(firstValid))
                bindings[act.actionId] = mutableListOf(firstValid)
            } else {
                bindings.remove(act.actionId)
            }
        }
    }

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
        fun normalizeCode(code: String): String {
            return when (code.trim()) {
                "Gamepad_12", "DPAD_UP", "Dpad_UP" -> "D-pad_UP"
                "Gamepad_13", "DPAD_DOWN", "Dpad_DOWN" -> "D-pad_DOWN"
                "Gamepad_14", "DPAD_LEFT", "Dpad_LEFT" -> "D-pad_LEFT"
                "Gamepad_15", "DPAD_RIGHT", "Dpad_RIGHT" -> "D-pad_RIGHT"
                else -> code.trim()
            }
        }

        fun equivalentCodes(code: String): Set<String> {
            return when (normalizeCode(code)) {
                "D-pad_UP" -> setOf("D-pad_UP", "Gamepad_12")
                "D-pad_DOWN" -> setOf("D-pad_DOWN", "Gamepad_13")
                "D-pad_LEFT" -> setOf("D-pad_LEFT", "Gamepad_14")
                "D-pad_RIGHT" -> setOf("D-pad_RIGHT", "Gamepad_15")
                else -> setOf(code.trim())
            }
        }

        fun fromJson(o: JSONObject): MappingPreset {
            val p = MappingPreset(o.optString("id"), o.optString("name"))
            val b = o.optJSONObject("bindings") ?: return p
            b.keys().forEach { k ->
                val arr = b.optJSONArray(k) ?: return@forEach
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val raw = arr.optString(i)
                    if (raw.isNotBlank()) list.add(normalizeCode(raw))
                }
                if (list.isNotEmpty()) p.bindings[k] = list
            }
            p.enforceGlobalUniqueness()
            return p
        }
    }
}

data class GamepadInfo(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val macAddress: String?,
    val persistKey: String
) {
    val displayName: String get() = name.ifBlank { "未知手柄" }
    val shortModel: String
        get() = buildString {
            append(name.ifBlank { "游戏手柄" })
            if (vendorId != 0 || productId != 0) {
                append(" (")
                append(String.format("%04X", vendorId))
                append(":")
                append(String.format("%04X", productId))
                append(")")
            }
        }
}

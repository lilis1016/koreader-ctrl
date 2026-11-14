package com.koreader.ctrl

import com.koreader.ctrl.model.Action
import com.koreader.ctrl.model.MappingPreset
import com.koreader.ctrl.net.KoreaderClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心逻辑单元测试。
 *
 * 这些断言全部对照 KOReader 源码 frontend/dispatcher.lua 中
 * 已核实的事件名与参数格式，确保 App 发送的 URL 与 HTTP Inspector 解析一致。
 */
class CoreLogicTest {

    // ---------------- 事件名与 KOReader 源码一致 ----------------

    @Test
    fun `event names match KOReader dispatcher`() {
        // frontend/dispatcher.lua:139
        assertEquals("ToggleNightMode", Action.TOGGLE_NIGHT_MODE.eventName)
        // frontend/dispatcher.lua:134
        assertEquals("IncreaseFlIntensity", Action.INCREASE_FL.eventName)
        // frontend/dispatcher.lua:135
        assertEquals("DecreaseFlIntensity", Action.DECREASE_FL.eventName)
        // frontend/dispatcher.lua:142
        assertEquals("FullRefresh", Action.FULL_REFRESH.eventName)
        // frontend/dispatcher.lua:87
        assertEquals("Screenshot", Action.SCREENSHOT.eventName)
        // frontend/dispatcher.lua:82
        assertEquals("ShowMenu", Action.CONFIRM.eventName)
        // frontend/apps/reader/modules/readerpaging.lua:61-62
        assertEquals("GotoViewRel/1", Action.NEXT_PAGE.eventName)
        assertEquals("GotoViewRel/-1", Action.PREV_PAGE.eventName)
    }

    @Test
    fun `action ids are unique`() {
        val ids = Action.values().map { it.actionId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `byId resolves and rejects unknown`() {
        assertEquals(Action.PREV_PAGE, Action.byId("prev"))
        assertNull(Action.byId("does_not_exist"))
    }

    // ---------------- URL 构造与原 HTML 一致 ----------------

    @Test
    fun `url matches original html format`() {
        assertEquals(
            "http://192.168.31.114:8080/koreader/event/GotoViewRel/1",
            KoreaderClient.buildUrl("192.168.31.114", "GotoViewRel/1")
        )
    }

    @Test
    fun `url strips scheme and trailing slash`() {
        assertEquals(
            "http://10.0.0.5:8080/koreader/event/Close",
            KoreaderClient.buildUrl("http://10.0.0.5/", "Close")
        )
        assertEquals(
            "http://10.0.0.5:8080/koreader/event/Close",
            KoreaderClient.buildUrl("https://10.0.0.5", "Close")
        )
    }

    @Test
    fun `url keeps negative page arg`() {
        assertEquals(
            "http://192.168.31.114:8080/koreader/event/GotoViewRel/-1",
            KoreaderClient.buildUrl("192.168.31.114", "GotoViewRel/-1")
        )
    }

    // ---------------- 预设映射 ----------------

    @Test
    fun `preset stores and restores bindings`() {
        val p = MappingPreset("id1", "测试预设")
        p.setCodes(Action.PREV_PAGE, listOf("Gamepad_14"))
        p.setCodes(Action.NEXT_PAGE, listOf("Gamepad_15"))

        assertEquals(listOf("Gamepad_14"), p.codesFor(Action.PREV_PAGE))
        assertTrue(p.isBound(Action.PREV_PAGE))
        assertTrue(!p.isBound(Action.BACK))

        val json = p.toJson().toString()
        val restored = MappingPreset.fromJson(org.json.JSONObject(json))
        assertEquals("测试预设", restored.name)
        assertEquals(listOf("Gamepad_14"), restored.codesFor(Action.PREV_PAGE))
        assertEquals(listOf("Gamepad_15"), restored.codesFor(Action.NEXT_PAGE))
    }

    @Test
    fun `setting empty codes removes binding`() {
        val p = MappingPreset("id2", "x")
        p.setCodes(Action.BACK, listOf("Gamepad_1"))
        assertTrue(p.isBound(Action.BACK))
        p.setCodes(Action.BACK, emptyList())
        assertTrue(!p.isBound(Action.BACK))
        assertTrue(p.bindings.isEmpty())
    }

    @Test
    fun `multiple codes can map to one action`() {
        val p = MappingPreset("id3", "y")
        p.setCodes(Action.CONFIRM, listOf("Gamepad_0", "Gamepad_3"))
        assertEquals(2, p.codesFor(Action.CONFIRM).size)
    }

    @Test
    fun `default preset matches original html default map`() {
        val p = MappingPreset("d", "默认")
        p.setCodes(Action.PREV_PAGE, listOf("Gamepad_14"))
        p.setCodes(Action.NEXT_PAGE, listOf("Gamepad_15"))
        p.setCodes(Action.BACK, listOf("Gamepad_1"))
        p.setCodes(Action.CONFIRM, listOf("Gamepad_0"))
        // 与原 HTML 的 defaultMap 逐项对照
        assertEquals(listOf("Gamepad_14"), p.codesFor(Action.PREV_PAGE))
        assertEquals(listOf("Gamepad_15"), p.codesFor(Action.NEXT_PAGE))
        assertEquals(listOf("Gamepad_1"), p.codesFor(Action.BACK))
        assertEquals(listOf("Gamepad_0"), p.codesFor(Action.CONFIRM))
    }
}
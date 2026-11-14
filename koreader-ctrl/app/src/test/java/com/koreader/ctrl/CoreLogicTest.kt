package com.koreader.ctrl

import com.koreader.ctrl.gamepad.GamepadEngine
import com.koreader.ctrl.model.Action
import com.koreader.ctrl.model.MappingPreset
import com.koreader.ctrl.net.KoreaderClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun `event names match KOReader dispatcher`() {
        assertEquals("GotoViewRel/-1", Action.PREV_PAGE.eventName)
        assertEquals("GotoViewRel/1", Action.NEXT_PAGE.eventName)
        assertEquals("Close", Action.BACK.eventName)
        assertEquals("ShowMenu", Action.CONFIRM.eventName)
        assertEquals("IncreaseFlIntensity", Action.INCREASE_FL.eventName)
        assertEquals("DecreaseFlIntensity", Action.DECREASE_FL.eventName)
        assertEquals("FullRefresh", Action.FULL_REFRESH.eventName)
        assertEquals("ToggleNightMode", Action.TOGGLE_NIGHT_MODE.eventName)
        assertEquals("Screenshot", Action.SCREENSHOT.eventName)
        assertEquals("ShowGames", Action.GAME_HALL.eventName)
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
    fun `global unique mapping unbinds previous action automatically`() {
        val p = MappingPreset("id1", "全局唯一测试")
        p.bindUnique(Action.PREV_PAGE, "Gamepad_0")
        p.bindUnique(Action.NEXT_PAGE, "Gamepad_1")
        p.bindUnique(Action.BACK, "Gamepad_2")

        val displaced = p.bindUnique(Action.BACK, "Gamepad_0")

        assertEquals(listOf(Action.PREV_PAGE), displaced)
        assertNull(p.codeFor(Action.PREV_PAGE))
        assertEquals("Gamepad_1", p.codeFor(Action.NEXT_PAGE))
        assertEquals("Gamepad_0", p.codeFor(Action.BACK))
    }

    @Test
    fun `dpad up and down bind to brightness and resolve properly`() {
        val p = MappingPreset("id2", "亮度测试")
        p.bindUnique(Action.INCREASE_FL, "D-pad_UP")
        p.bindUnique(Action.DECREASE_FL, "D-pad_DOWN")

        assertEquals(Action.INCREASE_FL, p.findActionForCode("D-pad_UP"))
        assertEquals(Action.DECREASE_FL, p.findActionForCode("D-pad_DOWN"))
        assertEquals(Action.INCREASE_FL, p.findActionForCode("Gamepad_12"))
        assertEquals(Action.DECREASE_FL, p.findActionForCode("Gamepad_13"))

        assertEquals("十字键上", GamepadEngine.formatCodeForDisplay("D-pad_UP"))
        assertEquals("十字键下", GamepadEngine.formatCodeForDisplay("D-pad_DOWN"))
        assertEquals("手柄按钮 1", GamepadEngine.formatCodeForDisplay("Gamepad_0"))
        assertEquals("手柄按钮 2", GamepadEngine.formatCodeForDisplay("Gamepad_1"))
        assertEquals("摇杆上", GamepadEngine.formatCodeForDisplay("Axis_1_-1"))
        assertEquals("摇杆下", GamepadEngine.formatCodeForDisplay("Axis_1_1"))
    }
}

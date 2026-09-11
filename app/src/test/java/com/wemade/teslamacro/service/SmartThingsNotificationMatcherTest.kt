package com.wemade.teslamacro.service

import com.wemade.teslamacro.data.settings.SmartThingsCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartThingsNotificationMatcherTest {

    @Test
    fun `알림 문구마다 서로 다른 차량 동작을 찾는다`() {
        val commands = mapOf("open_frunk" to "ㅎㅎㅎㅎㅎ", "open_trunk" to "ㅌㅌㅌㅌㅌ")

        assertEquals(
            "open_frunk",
            matchingSmartThingsAction(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("SmartThings", "ㅎㅎㅎㅎㅎ"),
                commandTexts = commands,
            ),
        )
        assertEquals(
            "open_trunk",
            matchingSmartThingsAction(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("ㅌㅌㅌㅌㅌ"),
                commandTexts = commands,
            ),
        )
        assertNull(
            matchingSmartThingsAction(
                packageName = "example.fake",
                texts = listOf("ㅎㅎㅎㅎㅎ"),
                commandTexts = commands,
            ),
        )
        assertNull(
            matchingSmartThingsAction(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("ㅎㅎㅎㅎㅎ 실행됨"),
                commandTexts = commands,
            ),
        )
    }

    @Test
    fun `빈 문구와 중복 문구는 어떤 동작도 실행하지 않는다`() {
        assertNull(
            matchingSmartThingsAction(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("SmartThings"),
                commandTexts = mapOf("open_frunk" to "   "),
            ),
        )
        assertNull(
            matchingSmartThingsAction(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("같은 문구"),
                commandTexts = mapOf("open_frunk" to "같은 문구", "open_trunk" to "같은 문구"),
            ),
        )
    }

    @Test
    fun `설정에 노출한 모든 동작은 빠른 실행 허용 목록에 있다`() {
        assertTrue(SmartThingsCommands.all.all { it.action in QuickActionActivity.ACTIONS })
        assertFalse(SmartThingsCommands.all.any { it.action == "lock" || it.action == "unlock" })
    }

    @Test
    fun `같은 키의 반복 알림은 요청 시간 동안 막고 이후 새 명령은 받는다`() {
        val guard = NotificationReceiptGuard(duplicateWindowMillis = 120_000L)

        assertTrue(guard.accept("smartthings|42", 100L))
        assertFalse(guard.accept("smartthings|42", 200L))
        assertTrue(guard.accept("smartthings|42", 120_100L))
    }
}

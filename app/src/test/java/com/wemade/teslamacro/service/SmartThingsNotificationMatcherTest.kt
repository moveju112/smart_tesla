package com.wemade.teslamacro.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartThingsNotificationMatcherTest {

    @Test
    fun `스마트싱스의 정확한 문구만 프렁크 명령으로 받는다`() {
        assertTrue(
            matchesSmartThingsFrunkNotification(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("SmartThings", "ㅎㅎㅎㅎㅎ"),
                triggerText = "ㅎㅎㅎㅎㅎ",
            )
        )
        assertFalse(
            matchesSmartThingsFrunkNotification(
                packageName = "example.fake",
                texts = listOf("ㅎㅎㅎㅎㅎ"),
                triggerText = "ㅎㅎㅎㅎㅎ",
            )
        )
        assertFalse(
            matchesSmartThingsFrunkNotification(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("ㅎㅎㅎㅎㅎ 실행됨"),
                triggerText = "ㅎㅎㅎㅎㅎ",
            )
        )
    }

    @Test
    fun `빈 문구는 어떤 알림도 실행하지 않는다`() {
        assertFalse(
            matchesSmartThingsFrunkNotification(
                packageName = SMARTTHINGS_PACKAGE_NAME,
                texts = listOf("SmartThings"),
                triggerText = "   ",
            )
        )
    }

    @Test
    fun `같은 키의 반복 알림은 요청 시간 동안 막고 이후 새 명령은 받는다`() {
        val guard = NotificationReceiptGuard(duplicateWindowMillis = 120_000L)

        assertTrue(guard.accept("smartthings|42", 100L))
        assertFalse(guard.accept("smartthings|42", 200L))
        assertTrue(guard.accept("smartthings|42", 120_100L))
    }
}

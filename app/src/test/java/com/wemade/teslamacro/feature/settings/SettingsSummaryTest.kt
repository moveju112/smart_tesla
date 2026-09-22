package com.wemade.teslamacro.feature.settings

import com.wemade.teslamacro.data.charge.StealthChargePlan
import com.wemade.teslamacro.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** 상세를 접어도 설정값을 잃지 않고 사용자가 실행 범위를 확인할 수 있어야 한다. */
class SettingsSummaryTest {
    /** 기본 진입점부터 관련 기능 순서로 찾을 수 있게 한다. */
    @Test
    fun `settings navigation starts with automation`() {
        assertEquals(listOf("자동화", "주행", "차량", "기기"), SettingsGroup.entries.map { it.label })
    }

    /** 자동 하한은 표시용으로 추정하지 않고 실제 충전 계획의 반올림을 따른다. */
    @Test
    fun `automatic current summary matches charge planner across supported range`() {
        for (maximum in 5..48) {
            val settings = AppSettings(stealthMaxAmps = maximum, stealthMinAmps = null, stealthScheduleEnabled = false)
            assertEquals("${StealthChargePlan.autoMinAmps(5, maximum)}~${maximum}A · 시간 제한 없음", stealthSettingsSummary(settings))
        }
    }

    /** 수동 하한과 자정 통과 시간대가 접힌 상태에서도 드러난다. */
    @Test
    fun `manual range and overnight schedule stay visible`() {
        val settings = AppSettings(stealthMaxAmps = 32, stealthMinAmps = 8, stealthScheduleEnabled = true,
            stealthStartMinutes = 22 * 60 + 5, stealthEndMinutes = 6 * 60 + 9)
        assertEquals("8~32A · 22:05~06:09", stealthSettingsSummary(settings))
        assertEquals("8~32A · 시간 제한 없음", stealthSettingsSummary(settings.copy(stealthScheduleEnabled = false)))
    }

    /** 동일 시각은 실행기와 마찬가지로 빈 구간이 아니라 하루 종일로 안내한다. */
    @Test
    fun `matching start and end mean all day`() {
        for (minute in listOf(0, 480, 1439)) {
            val settings = AppSettings(stealthMaxAmps = 32, stealthMinAmps = 8, stealthScheduleEnabled = true,
                stealthStartMinutes = minute, stealthEndMinutes = minute)
            assertEquals("8~32A · 하루 종일", stealthSettingsSummary(settings))
            assertEquals("8~32A · 시간 제한 없음", stealthSettingsSummary(settings.copy(stealthScheduleEnabled = false)))
        }
    }

    /** 하루 경계와 동일 전류도 자리수가 깨지거나 별도 의미로 바뀌지 않는다. */
    @Test
    fun `time and current boundary values are retained`() {
        val settings = AppSettings(stealthMaxAmps = 5, stealthMinAmps = 5, stealthScheduleEnabled = true,
            stealthStartMinutes = 0, stealthEndMinutes = 1439)
        assertEquals("5~5A · 00:00~23:59", stealthSettingsSummary(settings))
    }
}

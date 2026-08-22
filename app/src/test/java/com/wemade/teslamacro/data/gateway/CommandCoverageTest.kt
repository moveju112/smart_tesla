package com.wemade.teslamacro.data.gateway

import com.wemade.teslamacro.domain.command.CommandCatalog
import com.wemade.teslamacro.domain.model.Signal
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 커버리지 회귀 방지.
 *
 * 명령을 추가해놓고 인코더 분기를 빼먹으면 실차에서야 터진다.
 * 카탈로그 전 항목이 실제로 인코딩되는지 여기서 못 박는다.
 */
class CommandCoverageTest {

    @Test
    fun `카탈로그의 모든 명령이 실제로 인코딩된다`() {
        CommandCatalog.all.forEach { template ->
            val command = CommandCatalog.defaultCommand(template)
            val encoded = runCatching { CommandEncoder.encode(command) }
            assertTrue(
                "${template.label} 인코딩 실패: ${encoded.exceptionOrNull()?.message}",
                encoded.isSuccess,
            )
            assertNotNull(encoded.getOrNull())
        }
    }

    @Test
    fun `모든 신호가 읽을 카테고리를 가진다`() {
        // 카테고리가 없으면 폴링 계획에서 누락돼 조건이 영원히 불충족이 된다
        Signal.entries.forEach { signal ->
            assertNotNull("${signal.label}에 소스 카테고리가 없다", signal.sourceCategory)
        }
    }

    @Test
    fun `모든 신호가 실제로 값을 뽑아낼 수 있다`() {
        // enum만 추가하고 numberOf/booleanOf 분기를 빼먹으면 항상 null이 되어 조용히 죽는다
        val full = com.wemade.teslamacro.domain.model.VehicleSnapshot(
            timestampMillis = 1L,
            insideTempC = 25.0,
            outsideTempC = 20.0,
            driverTempSettingC = 22.0,
            isClimateOn = true,
            isPreconditioning = true,
            isUserPresent = true,
            isLocked = true,
            shiftState = com.wemade.teslamacro.domain.model.ShiftState.PARK,
            doorOpen = com.wemade.teslamacro.domain.model.Door.entries.associateWith { true },
            batteryLevelPercent = 70,
            isCharging = true,
            chargeLimitPercent = 80,
            rangeKm = 300f,
            isChargePortOpen = true,
            speedKph = 0f,
            rideMinutes = 12.0,
            tirePressuresBar = com.wemade.teslamacro.domain.model.TirePosition.entries
                .associateWith { 2.9f },
        )
        Signal.entries.forEach { signal ->
            val value: Any? = when (signal.kind) {
                com.wemade.teslamacro.domain.model.SignalKind.NUMBER -> signal.numberOf(full)
                com.wemade.teslamacro.domain.model.SignalKind.BOOLEAN -> signal.booleanOf(full)
            }
            assertNotNull("${signal.label} 값 추출 분기가 없다", value)
        }
    }
}

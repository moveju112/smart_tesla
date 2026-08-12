package com.wemade.teslamacro.feature.dashboard

import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 좌석 표시 동기화 규칙을 못박는다.
 * 실차 사고: 차에서 켠 통풍 2단이 앱에는 "끔"으로 나왔다 — 저장값만 보고 차 보고값을 무시해서.
 */
class SeatSyncTest {

    private val seat = SeatPosition.FRONT_LEFT

    @Test
    fun `차가 보고한 통풍이 저장값보다 우선한다`() {
        val snapshot = VehicleSnapshot(timestampMillis = 0L, seatCooler = mapOf(seat to Level.MEDIUM))
        val stored = mapOf(seat to SeatClimate(SeatMode.COOL, Level.OFF))
        assertEquals(
            SeatClimate(SeatMode.COOL, Level.MEDIUM),
            seatClimateOf(snapshot, stored)[seat],
        )
    }

    @Test
    fun `열선이 돌고 있으면 모드도 열선으로 바뀐다`() {
        val snapshot = VehicleSnapshot(timestampMillis = 0L, seatHeater = mapOf(seat to Level.HIGH))
        val stored = mapOf(seat to SeatClimate(SeatMode.COOL, Level.LOW))
        assertEquals(
            SeatClimate(SeatMode.HEAT, Level.HIGH),
            seatClimateOf(snapshot, stored)[seat],
        )
    }

    @Test
    fun `아직 못 읽었으면 저장값을 보여준다`() {
        val stored = mapOf(seat to SeatClimate(SeatMode.HEAT, Level.LOW))
        assertEquals(
            SeatClimate(SeatMode.HEAT, Level.LOW),
            seatClimateOf(VehicleSnapshot(timestampMillis = 0L), stored)[seat],
        )
    }

    @Test
    fun `둘 다 꺼져 있으면 모드는 기억하고 단계만 끔`() {
        val snapshot = VehicleSnapshot(
            timestampMillis = 0L,
            seatCooler = mapOf(seat to Level.OFF),
            seatHeater = mapOf(seat to Level.OFF),
        )
        val stored = mapOf(seat to SeatClimate(SeatMode.HEAT, Level.MEDIUM))
        assertEquals(
            SeatClimate(SeatMode.HEAT, Level.OFF),
            seatClimateOf(snapshot, stored)[seat],
        )
    }
}

package com.wemade.teslamacro.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 저전류 구간에서 충전을 "끝났다"고 오인하지 않는지 검증한다. */
class SnapshotDecoderChargingTest {

    @Test
    fun `전력이 0으로 내려앉아도 실측 전류가 흐르면 충전 중이다`() {
        // 7A × 220V = 1.5kW인데 차량이 kW 정수로 0을 보고하는 경우
        assertEquals(true, SnapshotDecoder.chargingState(powerKw = 0, actualAmps = 7))
    }

    @Test
    fun `둘 다 0이면 충전이 아니다`() {
        assertEquals(false, SnapshotDecoder.chargingState(powerKw = 0, actualAmps = 0))
    }

    @Test
    fun `전력만 읽혀도 판정한다`() {
        assertEquals(true, SnapshotDecoder.chargingState(powerKw = 3, actualAmps = null))
        assertEquals(false, SnapshotDecoder.chargingState(powerKw = 0, actualAmps = null))
    }

    @Test
    fun `둘 다 못 읽었으면 모른다`() {
        assertNull(SnapshotDecoder.chargingState(powerKw = null, actualAmps = null))
    }
}

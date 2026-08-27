package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 차량 충전 응답의 최대 전류가 스냅샷에 보존되는지 검증 */
class SnapshotDecoderTest {

    @Test
    fun `차량이 보고한 충전 최대 전류를 읽는다`() {
        val bytes = responseWith(
            Vehicle.ChargeState.newBuilder().setChargeCurrentRequestMax(16).build()
        )

        assertEquals(16, SnapshotDecoder.fromVehicleData(bytes, nowMillis = 1L).maxChargingAmps)
    }

    @Test
    fun `충전 최대 전류가 없으면 임의 기본값을 만들지 않는다`() {
        val bytes = responseWith(Vehicle.ChargeState.getDefaultInstance())

        assertNull(SnapshotDecoder.fromVehicleData(bytes, nowMillis = 1L).maxChargingAmps)
    }

    /** 충전 상태 하나만 담은 실제 응답 모양을 만든다 */
    private fun responseWith(charge: Vehicle.ChargeState): ByteArray =
        CarServer.Response.newBuilder()
            .setVehicleData(Vehicle.VehicleData.newBuilder().setChargeState(charge))
            .build()
            .toByteArray()
}

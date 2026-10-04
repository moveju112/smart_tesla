package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import com.wemade.teslamacro.domain.model.overlay
import org.junit.Assert.*
import org.junit.Test

class SnapshotDecoderHistoryTest {
    /** 원본 누적거리·전력 부호·충전량을 새 저장 필드까지 전달한다. */
    @Test fun `decode history fields and preserve through overlay`() {
        val data = Vehicle.VehicleData.newBuilder()
            .setDriveState(Vehicle.DriveState.newBuilder().setOdometerInHundredthsOfAMile(12345).setPower(-8))
            .setChargeState(Vehicle.ChargeState.newBuilder().setChargeEnergyAdded(12.3f))
        val response = CarServer.Response.newBuilder().setVehicleData(data).build().toByteArray()
        val fresh = SnapshotDecoder.fromVehicleData(response, 100)
        assertEquals(12345, fresh.odometerHundredthsMile)
        assertEquals(-8, fresh.drivePowerKw)
        assertEquals(12.3f, fresh.chargeEnergyAddedKwh)
        assertEquals(fresh.odometerHundredthsMile, VehicleSnapshot.Empty.overlay(fresh).odometerHundredthsMile)
        assertEquals(fresh.drivePowerKw, VehicleSnapshot.Empty.overlay(fresh).drivePowerKw)
        assertEquals(fresh.chargeEnergyAddedKwh, VehicleSnapshot.Empty.overlay(fresh).chargeEnergyAddedKwh)
    }

    /** 미지원 필드로 0 소비량을 만들지 않는다. */
    @Test fun `missing history fields remain unknown`() {
        val fresh = SnapshotDecoder.fromVehicleData(CarServer.Response.getDefaultInstance().toByteArray(), 100)
        assertNull(fresh.odometerHundredthsMile)
        assertNull(fresh.drivePowerKw)
        assertNull(fresh.chargeEnergyAddedKwh)
    }
}

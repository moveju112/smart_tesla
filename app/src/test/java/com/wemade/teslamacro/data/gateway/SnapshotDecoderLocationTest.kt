package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.wemade.teslamacro.data.history.VehicleHistory
import org.junit.Assert.*
import org.junit.Test

class SnapshotDecoderLocationTest {
    /** Native WGS만 전송하는 차량도 위치를 잃지 않고 실제 기록까지 전달한다. */
    @Test fun `native WGS coordinates survive decoding and history sampling`() {
        val location = Vehicle.LocationState.newBuilder().setNativeLatitude(37f).setNativeLongitude(127f)
            .setNativeType(Vehicle.LocationState.GPSCoordinateType.newBuilder().setWGS(Common.Void.getDefaultInstance()))
        val snapshot = decode(location)
        assertEquals(37.0, snapshot.vehicleLatitude!!, 0.0)
        assertEquals(127.0, snapshot.vehicleLongitude!!, 0.0)
        val sample = VehicleHistory.sample(snapshot, 100)
        assertEquals(snapshot.vehicleLatitude, sample.latitude)
        assertEquals(snapshot.vehicleLongitude, sample.longitude)
    }

    /** OSM 좌표계와 다른 Native GCJ는 피하고 일반 WGS 좌표를 쓴다. */
    @Test fun `GCJ native coordinates use complete WGS fallback`() {
        val location = Vehicle.LocationState.newBuilder().setNativeLatitude(35f).setNativeLongitude(129f)
            .setNativeType(Vehicle.LocationState.GPSCoordinateType.newBuilder().setGCJ(Common.Void.getDefaultInstance()))
            .setLatitude(37f).setLongitude(127f)
        assertEquals(37.0, decode(location).vehicleLatitude!!, 0.0)
        assertNull(decode(location.clearLatitude().clearLongitude()).vehicleLatitude)
    }

    /** 누락된 위경도를 서로 다른 필드에서 조합하지 않고 완전한 원본 쌍만 쓴다. */
    @Test fun `raw GPS is used when estimated fields are incomplete or invalid`() {
        val location = Vehicle.LocationState.newBuilder().setLatitude(38f).setGeoLatitude(37f).setGeoLongitude(127f)
        assertEquals(37.0, decode(location).vehicleLatitude!!, 0.0)
        location.setLongitude(999f)
        assertEquals(127.0, decode(location).vehicleLongitude!!, 0.0)
        location.clearGeoLatitude().clearGeoLongitude()
        assertNull(decode(location).vehicleLatitude)
        assertNull(decode(location.setLatitude(0f).setLongitude(0f)).vehicleLongitude)
    }

    /** 여러 좌표가 함께 오면 공식 권장 Native WGS를 우선하고 일반 주소도 보존한다. */
    @Test fun `native WGS precedes plain and raw coordinates`() {
        val location = Vehicle.LocationState.newBuilder().setNativeLatitude(37f).setNativeLongitude(127f)
            .setNativeType(Vehicle.LocationState.GPSCoordinateType.newBuilder().setWGS(Common.Void.getDefaultInstance()))
            .setLatitude(36f).setLongitude(128f).setGeoLatitude(35f).setGeoLongitude(129f)
        assertEquals(37.0, decode(location).vehicleLatitude!!, 0.0)
        assertEquals(36.0, decode(location.setNativeLatitude(Float.NaN)).vehicleLatitude!!, 0.0)
    }

    /** 실제 protobuf 응답 형식으로 최소 위치 표본을 만든다. */
    private fun decode(location: Vehicle.LocationState.Builder) = SnapshotDecoder.fromVehicleData(
        CarServer.Response.newBuilder().setVehicleData(Vehicle.VehicleData.newBuilder().setLocationState(location)).build().toByteArray(), 100)
}

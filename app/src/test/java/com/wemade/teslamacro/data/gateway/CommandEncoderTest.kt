package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.vcsec.Vcsec
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.StateCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 명령 인코딩 검증.
 *
 * 필드 번호가 하나만 틀려도 차량이 조용히 무시하거나 엉뚱한 동작을 한다.
 * 문서에 실린 바이트열이 있는 명령은 그것으로, 나머지는 왕복 파싱으로 확인한다.
 */
class CommandEncoderTest {

    /** protocol.md에 실린 유일한 명령 인코딩 정답 벡터 */
    @Test
    fun `공조 켜기가 문서의 바이트열과 정확히 같다`() {
        val encoded = CommandEncoder.encode(VehicleCommand.ClimateOn)
        assertTrue(encoded is EncodedCommand.Infotainment)

        val bytes = (encoded as EncodedCommand.Infotainment).action.toByteArray()
        assertEquals("120452020801", bytes.hex())
    }

    @Test
    fun `통풍 2단계가 앞좌석 위치와 레벨로 정확히 인코딩된다`() {
        val encoded = CommandEncoder.encode(
            VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.MEDIUM)
        ) as EncodedCommand.Infotainment

        // 왕복 파싱으로 실제 필드에 값이 박혔는지 본다
        val parsed = CarServer.Action.parseFrom(encoded.action.toByteArray())
        val cooler = parsed.vehicleAction.hvacSeatCoolerActions.getHvacSeatCoolerAction(0)

        assertEquals(
            CarServer.HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Med,
            cooler.seatCoolerLevel,
        )
        assertEquals(
            CarServer.HvacSeatCoolerActions.HvacSeatCoolerPosition_E
                .HvacSeatCoolerPosition_FrontLeft,
            cooler.seatPosition,
        )
    }

    @Test
    fun `열선 단계와 좌석이 oneof로 정확히 설정된다`() {
        val encoded = CommandEncoder.encode(
            VehicleCommand.SetSeatHeater(SeatPosition.FRONT_RIGHT, Level.HIGH)
        ) as EncodedCommand.Infotainment

        val heater = CarServer.Action.parseFrom(encoded.action.toByteArray())
            .vehicleAction.hvacSeatHeaterActions.getHvacSeatHeaterAction(0)

        assertTrue(heater.hasSEATHEATERHIGH())
        assertTrue(heater.hasCARSEATFRONTRIGHT())
    }

    @Test
    fun `목표 온도가 좌우 존에 모두 적용된다`() {
        val encoded = CommandEncoder.encode(VehicleCommand.SetTemperature(24.0))
            as EncodedCommand.Infotainment

        val adjust = CarServer.Action.parseFrom(encoded.action.toByteArray())
            .vehicleAction.hvacTemperatureAdjustmentAction

        assertEquals(24.0f, adjust.absoluteCelsius, 0.001f)
        assertEquals(2, adjust.hvacTemperatureZoneCount)
    }

    @Test
    fun `잠금은 인포테인먼트가 아니라 VCSEC로 간다`() {
        // 차가 자고 있어도 받아야 하는 명령이라 도메인이 다르다
        val encoded = CommandEncoder.encode(VehicleCommand.Lock)
        assertTrue(encoded is EncodedCommand.Vehicle)
        assertEquals(
            Vcsec.RKEAction_E.RKE_ACTION_LOCK,
            (encoded as EncodedCommand.Vehicle).message.rkeAction,
        )
    }

    @Test
    fun `창문 환기와 닫기가 서로 다른 oneof를 쓴다`() {
        val vent = (CommandEncoder.encode(VehicleCommand.VentWindows)
            as EncodedCommand.Infotainment).action.vehicleAction.vehicleControlWindowAction
        val close = (CommandEncoder.encode(VehicleCommand.CloseWindows)
            as EncodedCommand.Infotainment).action.vehicleAction.vehicleControlWindowAction

        assertTrue(vent.hasVent())
        assertTrue(close.hasClose())
    }

    @Test
    fun `요청한 카테고리만 지정된다`() {
        val data = CommandEncoder.encodeVehicleDataRequest(setOf(StateCategory.CLIMATE))
            .vehicleAction.getVehicleData
        assertTrue(data.hasGetClimateState())
        assertTrue(!data.hasGetChargeState())
    }

    /** 묶음 조회의 요점 — 한 요청에 여러 상태가 함께 실린다 */
    @Test
    fun `여러 카테고리를 한 요청에 담는다`() {
        val data = CommandEncoder.encodeVehicleDataRequest(
            setOf(
                StateCategory.CLIMATE,
                StateCategory.CHARGE,
                StateCategory.TIRES,
                StateCategory.LOCATION,
                StateCategory.SOFTWARE,
            )
        ).vehicleAction.getVehicleData
        assertTrue(data.hasGetClimateState())
        assertTrue(data.hasGetChargeState())
        assertTrue(data.hasGetTirePressureState())
        assertTrue(data.hasGetLocationState())
        assertTrue(data.hasGetSoftwareUpdateState())
    }

    /** VCSEC 도메인 카테고리는 인포테인먼트 요청에 섞이면 안 된다 */
    @Test
    fun `차체 카테고리는 담기지 않는다`() {
        val data = CommandEncoder.encodeVehicleDataRequest(
            setOf(StateCategory.BODY_CONTROLLER, StateCategory.CLOSURES, StateCategory.DRIVE)
        ).vehicleAction.getVehicleData
        assertTrue(data.hasGetDriveState())
        assertTrue(!data.hasGetClosuresState())
    }

    @Test
    fun `뒷좌석 통풍은 명령 자체가 만들어지지 않는다`() {
        // 프로토콜이 앞좌석만 지원한다. 조용히 무시하지 말고 터뜨려야 버그를 빨리 잡는다
        val failure = runCatching {
            CommandEncoder.encode(
                VehicleCommand.SetSeatCooler(SeatPosition.REAR_LEFT, Level.LOW)
            )
        }
        assertTrue(failure.isFailure)
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

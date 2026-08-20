package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 명령 응답의 실행 결과 판정.
 *
 * 실차(0.8.35)에서 창문 환기·닫기가 조용히 무시됐다 — 차는 actionStatus에
 * ERROR와 사유를 실어 보냈는데 앱이 안 읽고 성공으로 기록했다.
 */
class CommandResultTest {

    @Test
    fun `정상 응답이면 거부가 아니다`() {
        val bytes = CarServer.Response.newBuilder().build().toByteArray()
        assertNull(infotainmentRejection(bytes))
    }

    @Test
    fun `ERROR면 차가 보낸 사유를 그대로 돌려준다`() {
        val bytes = CarServer.Response.newBuilder()
            .setActionStatus(
                CarServer.ActionStatus.newBuilder()
                    .setResult(CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR)
                    .setResultReason(
                        CarServer.ResultReason.newBuilder().setPlainText("cabin door open")
                    )
            )
            .build().toByteArray()
        assertEquals("cabin door open", infotainmentRejection(bytes))
    }

    @Test
    fun `ERROR인데 사유가 비면 기본 문구를 쓴다`() {
        val bytes = CarServer.Response.newBuilder()
            .setActionStatus(
                CarServer.ActionStatus.newBuilder()
                    .setResult(CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR)
            )
            .build().toByteArray()
        assertEquals("차량이 거부함 (사유 없음)", infotainmentRejection(bytes))
    }

    @Test
    fun `해석 불가 바이트는 판정하지 않는다`() {
        assertNull(infotainmentRejection(ByteArray(5) { 0x7F }))
    }
}

package com.wemade.teslamacro.data.gateway

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.errors.Errors
import com.tesla.generated.vcsec.Vcsec
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

    // ---- VCSEC (잠금·트렁크). 봉투 fault만 보면 본문의 거부를 놓친다 ----

    @Test
    fun `VCSEC 상태가 없으면 거부가 아니다`() {
        assertNull(vcsecRejection(Vcsec.FromVCSECMessage.newBuilder().build()))
    }

    @Test
    fun `VCSEC OK는 거부가 아니다`() {
        assertNull(vcsecRejection(commandStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK)))
    }

    @Test
    fun `VCSEC WAIT를 성공으로 기록하지 않는다`() {
        assertEquals(
            "차량이 아직 준비되지 않았어요",
            vcsecRejection(commandStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT)),
        )
    }

    @Test
    fun `VCSEC ERROR는 서명 계층 사유를 붙여 돌려준다`() {
        val response = Vcsec.FromVCSECMessage.newBuilder()
            .setCommandStatus(
                Vcsec.CommandStatus.newBuilder()
                    .setOperationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR)
                    .setSignedMessageStatus(
                        Vcsec.SignedMessage_status.newBuilder().setSignedMessageInformation(
                            Vcsec.SignedMessage_information_E
                                .SIGNEDMESSAGE_INFORMATION_FAULT_NOT_ON_WHITELIST,
                        )
                    )
            )
            .build()
        assertEquals("차량이 거부함 (NOT_ON_WHITELIST)", vcsecRejection(response))
    }

    @Test
    fun `문이 열려 있다는 사유는 사람 말로 바꾼다`() {
        assertEquals("문이 열려 있어요", vcsecRejection(nominalError(Errors.GenericError_E.GENERICERROR_CLOSURES_OPEN)))
    }

    /** 이미 원하는 상태면 실패가 아니다 — 매크로가 실패로 기록하면 안 된다 */
    @Test
    fun `이미 그 상태라는 응답은 거부가 아니다`() {
        assertNull(vcsecRejection(nominalError(Errors.GenericError_E.GENERICERROR_ALREADY_ON)))
    }

    private fun commandStatus(status: Vcsec.OperationStatus_E): Vcsec.FromVCSECMessage =
        Vcsec.FromVCSECMessage.newBuilder()
            .setCommandStatus(Vcsec.CommandStatus.newBuilder().setOperationStatus(status))
            .build()

    private fun nominalError(error: Errors.GenericError_E): Vcsec.FromVCSECMessage =
        Vcsec.FromVCSECMessage.newBuilder()
            .setNominalError(Errors.NominalError.newBuilder().setGenericError(error))
            .build()
}

package com.wemade.teslable

import android.content.Context
import com.google.protobuf.ByteString
import com.tesla.generated.keys.Keys
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import com.wemade.teslable.crypto.ClientKey
import com.wemade.teslable.crypto.ClientKeyStore
import com.wemade.teslable.session.DomainSession
import com.wemade.teslable.session.SignedRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** 프로토콜 계층에서 나는 오류 */
class TeslaProtocolException(message: String) : Exception(message)

/**
 * 링크 위에 서명 세션을 얹어 실제 명령을 주고받는다.
 *
 * BLE는 응답을 스트림으로 흘려보내므로 요청을 [requestLock]으로 직렬화한다.
 * VCSEC은 동시 요청을 감당하지 못한다고 규격에 명시돼 있어 어차피 필요하다.
 */
class TeslaClient(
    context: Context,
    private val link: TeslaBleLink,
    private val vin: String,
) {
    private val random = SecureRandom()
    private val requestLock = Mutex()

    val clientKey: ClientKey = ClientKeyStore(context).loadOrCreate()

    /** 연결마다 새로 만드는 16바이트 주소. 차량이 응답을 되돌릴 때 쓴다 */
    private val routingAddress = ByteArray(16).also(random::nextBytes)

    private val sessions = mutableMapOf<UniversalMessage.Domain, DomainSession>()

    private fun session(domain: UniversalMessage.Domain) = sessions.getOrPut(domain) {
        DomainSession(domain, vin, clientKey)
    }

    /**
     * 카드키 승인이 필요한 키 등록을 요청한다.
     *
     * 이 메시지만은 **서명하지 않는다** — 아직 등록된 키가 없기 때문이다.
     * RoutableMessage로 감싸지 않고 VCSEC 봉투를 그대로 보낸다.
     * 전송 성공이 승인 완료를 뜻하지 않는다. 사용자가 센터콘솔에 카드를 대야 한다.
     */
    suspend fun requestKeyEnrollment(asOwner: Boolean = true) = requestLock.withLock {
        val permission = Vcsec.PermissionChange.newBuilder()
            .setKey(
                Vcsec.PublicKey.newBuilder()
                    .setPublicKeyRaw(ByteString.copyFrom(clientKey.encodedPublicKey))
            )
            .setKeyRole(if (asOwner) Keys.Role.ROLE_OWNER else Keys.Role.ROLE_DRIVER)
            .build()

        val payload = Vcsec.UnsignedMessage.newBuilder()
            .setWhitelistOperation(
                Vcsec.WhitelistOperation.newBuilder()
                    .setAddKeyToWhitelistAndAddPermissions(permission)
                    .setMetadataForKey(
                        Vcsec.KeyMetadata.newBuilder()
                            .setKeyFormFactor(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE)
                    )
            )
            .build()

        val envelope = Vcsec.ToVCSECMessage.newBuilder()
            .setSignedMessage(
                Vcsec.SignedMessage.newBuilder()
                    .setProtobufMessageAsBytes(payload.toByteString())
                    .setSignatureType(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY)
            )
            .build()

        DiagLog.add("키 등록 요청 전송 (${envelope.serializedSize}B, role=${if (asOwner) "OWNER" else "DRIVER"})")
        link.send(envelope.toByteArray())
        DiagLog.add("키 등록 요청 전송 완료 — 카드키 태그 대기")
    }

    /**
     * 이 키가 차량에 등록됐는지 확인한다.
     *
     * 카드키 승인 전에는 VCSEC 핸드셰이크가 거부되고, 승인되면 통과한다.
     * 그래서 핸드셰이크 성공 = 등록 완료다. 명령을 따로 보낼 필요가 없다.
     */
    suspend fun verifyEnrollment(): Unit = requestLock.withLock {
        val domain = UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY
        val session = session(domain)
        if (session.isEstablished) return@withLock
        handshake(domain, session)
    }

    /** VCSEC 도메인에 명령을 보낸다 (잠금·트렁크·리모트 시동) */
    suspend fun sendToVcsec(message: Vcsec.UnsignedMessage): Vcsec.FromVCSECMessage {
        val raw = execute(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, message.toByteArray())
        return Vcsec.FromVCSECMessage.parseFrom(raw)
    }

    /** 인포테인먼트 도메인에 명령을 보낸다 (공조·시트·미디어·상태 읽기) */
    suspend fun sendToInfotainment(actionBytes: ByteArray): ByteArray =
        execute(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, actionBytes)

    /** 세션이 없거나 깨졌으면 다시 세우고 명령을 보낸다 */
    private suspend fun execute(
        domain: UniversalMessage.Domain,
        payload: ByteArray,
    ): ByteArray = requestLock.withLock {
        val session = session(domain)
        if (!session.isEstablished) handshake(domain, session)

        return@withLock try {
            transmit(domain, session, payload)
        } catch (retryable: SessionOutOfSyncException) {
            // 차량이 재부팅하면 epoch이 바뀐다. 한 번만 다시 세우고 재시도한다
            DiagLog.add("세션 어긋남(${retryable.message}) → 재핸드셰이크")
            session.invalidate()
            handshake(domain, session)
            transmit(domain, session, payload)
        }
    }

    private suspend fun handshake(domain: UniversalMessage.Domain, session: DomainSession) {
        val uuid = ByteArray(16).also(random::nextBytes)

        val request = baseMessage(domain, uuid)
            .setSessionInfoRequest(
                UniversalMessage.SessionInfoRequest.newBuilder()
                    .setPublicKey(ByteString.copyFrom(clientKey.encodedPublicKey))
            )
            .build()

        DiagLog.add("핸드셰이크 시작 (${domain.name})")
        link.send(request.toByteArray())
        val response = awaitResponse(uuid)
            ?: run {
                DiagLog.add("핸드셰이크 응답 없음 (${RESPONSE_TIMEOUT_MS}ms)")
                throw TeslaProtocolException("차량이 핸드셰이크에 응답하지 않는다")
            }

        val failure = session.applySessionInfo(
            sessionInfoBytes = response.sessionInfo.toByteArray(),
            tagFromVehicle = response.signatureData.sessionInfoTag.tag.toByteArray(),
            challenge = uuid,
        )
        if (failure != null) {
            DiagLog.add("핸드셰이크 검증 실패: $failure")
            throw TeslaProtocolException(failure)
        }
        DiagLog.add("핸드셰이크 완료 (${domain.name})")
    }

    private suspend fun transmit(
        domain: UniversalMessage.Domain,
        session: DomainSession,
        payload: ByteArray,
    ): ByteArray {
        val uuid = ByteArray(16).also(random::nextBytes)
        val flags = 1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE.number
        val signed: SignedRequest = session.sign(payload, flags)

        val message = baseMessage(domain, uuid)
            .setProtobufMessageAsBytes(ByteString.copyFrom(signed.ciphertext))
            .setSignatureData(signed.signatureData)
            .setFlags(flags)
            .build()

        DiagLog.add("명령 전송 ${domain.name} (${payload.size}B)")
        link.send(message.toByteArray())
        val response = awaitResponse(uuid)
            ?: run {
                DiagLog.add("명령 응답 없음 (${RESPONSE_TIMEOUT_MS}ms)")
                throw TeslaProtocolException("차량이 응답하지 않는다")
            }

        checkFault(response)

        // 응답이 암호화됐으면 풀고, 아니면 평문 그대로 쓴다
        return if (response.signatureData.hasAESGCMResponseData()) {
            session.decryptResponse(signed, response)
        } else {
            response.protobufMessageAsBytes.toByteArray()
        }
    }

    /** 프로토콜 계층 오류를 사람이 읽는 메시지로 바꾼다 */
    private fun checkFault(response: UniversalMessage.RoutableMessage) {
        val fault = response.signedMessageStatus.signedMessageFault
        if (fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE) return
        DiagLog.add("차량 거부 fault=${fault.name}")

        // 세션 동기화 문제는 재핸드셰이크로 회복 가능하다
        val recoverable = fault in setOf(
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REPEATED_COUNTER,
        )
        if (recoverable) throw SessionOutOfSyncException(fault.name)

        throw TeslaProtocolException(faultMessage(fault))
    }

    private fun faultMessage(fault: UniversalMessage.MessageFault_E): String = when (fault) {
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID ->
            "이 앱 키가 차량에 등록되어 있지 않다. 카드키로 다시 등록해라"
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES ->
            "권한이 없다. 키 역할을 확인해라"
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT ->
            "차량이 바쁘다. 잠시 후 다시 시도해라"
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED ->
            "응답이 너무 크다. 상태를 나눠서 요청해라"
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REMOTE_ACCESS_DISABLED ->
            "차량에서 모바일 접근이 꺼져 있다"
        else -> "차량이 명령을 거부했다 (${fault.name})"
    }

    private fun baseMessage(
        domain: UniversalMessage.Domain,
        uuid: ByteArray,
    ): UniversalMessage.RoutableMessage.Builder =
        UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(UniversalMessage.Destination.newBuilder().setDomain(domain))
            .setFromDestination(
                UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(routingAddress))
            )
            .setUuid(ByteString.copyFrom(uuid))

    /**
     * 우리 요청에 대한 응답을 기다린다.
     *
     * VCSEC은 메모리 제약으로 request_uuid를 안 채우는 경우가 있어,
     * 라우팅 주소가 우리 것이면 받아들인다.
     */
    private suspend fun awaitResponse(
        uuid: ByteArray,
        timeoutMillis: Long = RESPONSE_TIMEOUT_MS,
    ): UniversalMessage.RoutableMessage? = withTimeoutOrNull(timeoutMillis) {
        link.incoming
            .first { bytes ->
                val parsed = runCatching {
                    UniversalMessage.RoutableMessage.parseFrom(bytes)
                }.getOrNull() ?: return@first false
                matchesRequest(parsed, uuid)
            }
            .let { UniversalMessage.RoutableMessage.parseFrom(it) }
    }

    private fun matchesRequest(
        message: UniversalMessage.RoutableMessage,
        uuid: ByteArray,
    ): Boolean {
        // request_uuid가 있으면 그게 1순위 판별
        val responseUuid = message.requestUuid.toByteArray()
        if (responseUuid.isNotEmpty()) return responseUuid.contentEquals(uuid)

        // 라우팅 주소가 우리 것이면 확정
        if (message.toDestination.routingAddress.toByteArray().contentEquals(routingAddress)) return true

        // 둘 다 없어도 받아들인다. 요청은 requestLock으로 직렬화돼 한 번에 하나뿐이라,
        // 지금 도착한 응답은 방금 보낸 요청의 것이다.
        // VCSEC은 request_uuid도 routing도 안 채우는 경우가 있어 이게 없으면 응답을 통째로 놓친다
        return true
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 8_000L
    }
}

/** 세션 동기화가 깨졌다는 신호. 재핸드셰이크로 회복한다 */
private class SessionOutOfSyncException(fault: String) : Exception(fault)

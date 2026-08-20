package com.wemade.teslable.session

import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.wemade.teslable.crypto.ClientKey
import com.wemade.teslable.crypto.Metadata
import com.wemade.teslable.crypto.SessionCrypto
import com.google.protobuf.ByteString
import java.security.SecureRandom

/** 명령 유효 시간. 짧을수록 안전하지만 시계 오차에 취약해진다 */
private const val COMMAND_TTL_SECONDS = 30

/** AES-GCM 인코딩 결과와, 응답 복호화에 필요한 문맥 */
class SignedRequest(
    val ciphertext: ByteArray,
    val signatureData: Signatures.SignatureData,
    val flags: Int,
    /** 응답 메타데이터에 들어갈 요청 해시 */
    val requestHash: ByteArray,
)

/**
 * 도메인(VCSEC / Infotainment) 하나에 대한 세션 상태.
 *
 * 도메인마다 공개키·시계·카운터가 따로라 반드시 분리해서 관리해야 한다.
 * protocol.md "Authorizing commands" 절을 그대로 구현한다.
 */
/**
 * 깨는 중인 차가 공개키 자리를 비워 보낸 경우.
 * 키가 틀린 게 아니라 아직 준비가 안 된 것이라, 잠깐 뒤 다시 물으면 풀린다.
 * 호출부가 "이건 곧 풀릴 실패"인지 알아야 즉시 재시도할 수 있어 밖으로 뺀다.
 */
const val REASON_VEHICLE_NOT_READY = "차량이 아직 세션 키를 안 보냈다 (깨는 중 — 곧 재시도)"

class DomainSession(
    private val domain: UniversalMessage.Domain,
    private val vin: String,
    private val clientKey: ClientKey,
    private val random: SecureRandom = SecureRandom(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private var sharedKey: ByteArray? = null
    private var epoch: ByteArray = ByteArray(0)
    private var counter: Int = 0

    /** 차량 시계 - 로컬 시계. 만료 시각을 차량 기준으로 계산하려면 필요하다 */
    private var clockOffsetSeconds: Long = 0

    /** 같은 epoch 안에서 마지막으로 인증한 차량 시각. 롤백 감지에 쓴다 */
    private var lastAuthenticatedClock: Long = -1

    val isEstablished: Boolean get() = sharedKey != null

    /**
     * 세션 정보 응답을 검증하고 상태를 갱신한다.
     *
     * @param challenge 세션 정보 요청에 넣었던 uuid
     * @return 검증 실패 사유. 성공하면 null
     */
    fun applySessionInfo(
        sessionInfoBytes: ByteArray,
        tagFromVehicle: ByteArray,
        challenge: ByteArray,
    ): String? {
        val info = runCatching { Signatures.SessionInfo.parseFrom(sessionInfoBytes) }
            .getOrElse { return "세션 정보를 해석하지 못했다" }

        if (info.status == Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST) {
            return "이 키가 차량에 등록되어 있지 않다"
        }

        // 1. 공유키 유도 후 태그 검증 — 여기서 MITM이 걸러진다.
        //    깨는 중인 차는 공개키 자리를 비운 응답을 먼저 보낸다. 이걸 "키가 올바르지 않다"고
        //    적으면 등록이 깨진 줄 알고 엉뚱한 데를 파게 된다 — 두 경우를 갈라서 말한다
        val vehicleKey = info.publicKey.toByteArray()
        if (vehicleKey.isEmpty()) {
            return REASON_VEHICLE_NOT_READY
        }
        val derived = runCatching { clientKey.sharedKeyWith(vehicleKey) }
            .getOrElse { return "차량 공개키를 해석하지 못했다 (${vehicleKey.size}바이트)" }

        val metadata = Metadata.Builder()
            .putByte(Metadata.TAG_SIGNATURE_TYPE, SIGNATURE_TYPE_HMAC)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .put(Metadata.TAG_CHALLENGE, challenge)
            .build()

        val expected = SessionCrypto.hmacSha256(
            key = SessionCrypto.sessionInfoKey(derived),
            message = metadata + sessionInfoBytes,
        )
        if (!SessionCrypto.constantTimeEquals(expected, tagFromVehicle)) {
            return "세션 정보 인증 실패 (중간자 공격 가능성)"
        }

        // 2. 같은 epoch에서 시계가 되돌아가면 재생 공격이다
        val newEpoch = info.epoch.toByteArray()
        val clockTime = info.clockTime.toLong() and 0xFFFFFFFFL
        val sameEpoch = newEpoch.contentEquals(epoch)
        if (sameEpoch && clockTime < lastAuthenticatedClock) {
            return "차량 시계가 되돌아갔다 (재생된 응답)"
        }

        // 3. 상태 갱신. epoch이 그대로면 카운터를 되돌리지 않는다
        sharedKey = derived
        counter = if (sameEpoch) maxOf(counter, info.counter) else info.counter
        epoch = newEpoch
        clockOffsetSeconds = clockTime - nowSeconds()
        lastAuthenticatedClock = clockTime
        return null
    }

    /** 세션이 깨졌을 때 재핸드셰이크를 강제한다 */
    fun invalidate() {
        sharedKey = null
    }

    /** 평문 protobuf를 AES-GCM으로 봉인한다 */
    fun sign(plaintext: ByteArray, flags: Int): SignedRequest {
        val key = requireNotNull(sharedKey) { "핸드셰이크 전에는 명령을 보낼 수 없다" }

        // 카운터는 명령마다 반드시 증가해야 한다 (재생 방지)
        counter += 1
        val expiresAt = (nowSeconds() + clockOffsetSeconds + COMMAND_TTL_SECONDS).toInt()

        val metadata = Metadata.Builder()
            .putByte(Metadata.TAG_SIGNATURE_TYPE, SIGNATURE_TYPE_AES_GCM_PERSONALIZED)
            .putByte(Metadata.TAG_DOMAIN, domain.number)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .put(Metadata.TAG_EPOCH, epoch)
            .putInt(Metadata.TAG_EXPIRES_AT, expiresAt)
            .putInt(Metadata.TAG_COUNTER, counter)
            .apply { if (flags != 0) putInt(Metadata.TAG_FLAGS, flags) }
            .build()

        val nonce = ByteArray(SessionCrypto.NONCE_SIZE).also(random::nextBytes)
        val encrypted = SessionCrypto.encrypt(key, nonce, metadata, plaintext)

        val signatureData = Signatures.SignatureData.newBuilder()
            .setSignerIdentity(
                Signatures.KeyIdentity.newBuilder()
                    .setPublicKey(ByteString.copyFrom(clientKey.encodedPublicKey))
            )
            .setAESGCMPersonalizedData(
                Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
                    .setEpoch(ByteString.copyFrom(epoch))
                    .setNonce(ByteString.copyFrom(nonce))
                    .setCounter(counter)
                    .setExpiresAt(expiresAt)
                    .setTag(ByteString.copyFrom(encrypted.tag))
            )
            .build()

        return SignedRequest(
            ciphertext = encrypted.ciphertext,
            signatureData = signatureData,
            flags = flags,
            requestHash = buildRequestHash(encrypted.tag),
        )
    }

    /** 암호화된 응답을 푼다 */
    fun decryptResponse(
        request: SignedRequest,
        response: UniversalMessage.RoutableMessage,
    ): ByteArray {
        val key = requireNotNull(sharedKey) { "세션이 없다" }
        // 생성된 자바 게터가 대문자로 시작해 코틀린 프로퍼티로 안 잡힌다. 직접 호출한다
        val responseData = response.signatureData.getAESGCMResponseData()

        val metadata = Metadata.Builder()
            .putByte(Metadata.TAG_SIGNATURE_TYPE, SIGNATURE_TYPE_AES_GCM_RESPONSE)
            .putByte(Metadata.TAG_DOMAIN, domain.number)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .putInt(Metadata.TAG_COUNTER, responseData.counter)
            // 응답 플래그는 0이어도 항상 포함한다 (요청과 규칙이 다르다)
            .putInt(Metadata.TAG_FLAGS, response.flags)
            .put(Metadata.TAG_REQUEST_HASH, request.requestHash)
            .putInt(Metadata.TAG_FAULT, response.signedMessageStatus.signedMessageFault.number)
            .build()

        return SessionCrypto.decrypt(
            sharedKey = key,
            nonce = responseData.nonce.toByteArray(),
            metadata = metadata,
            ciphertext = response.protobufMessageAsBytes.toByteArray(),
            tag = responseData.tag.toByteArray(),
        )
    }

    /**
     * 요청 해시 = [인증 방식 1바이트] || [인증 태그].
     * VCSEC로 보낸 요청은 17바이트로 잘린다.
     */
    private fun buildRequestHash(tag: ByteArray): ByteArray {
        val full = byteArrayOf(SIGNATURE_TYPE_AES_GCM_PERSONALIZED.toByte()) + tag
        return if (domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
            full.copyOf(minOf(full.size, VCSEC_REQUEST_HASH_SIZE))
        } else {
            full
        }
    }

    private companion object {
        const val SIGNATURE_TYPE_AES_GCM_PERSONALIZED = 5
        const val SIGNATURE_TYPE_HMAC = 6
        const val SIGNATURE_TYPE_AES_GCM_RESPONSE = 9
        const val VCSEC_REQUEST_HASH_SIZE = 17
    }
}

package com.wemade.teslable

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.wemade.teslable.crypto.ClientKey
import com.wemade.teslable.crypto.Metadata
import com.wemade.teslable.crypto.SessionCrypto
import com.wemade.teslable.session.DomainSession
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.*
import org.junit.Test

class DomainSessionCounterTest {
    /** uint32 상위 비트가 바뀌어도 인증한 차량 카운터보다 큰 값으로 서명한다. */
    @Test fun authenticatedCounterUsesUnsignedOrder() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val client = generator.generateKeyPair()
        val vehicle = generator.generateKeyPair()
        val vin = "5YJS0000000000000"
        val challenge = ByteArray(16) { 3 }
        val session = DomainSession(
            UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, vin,
            ClientKey(client.private, client.public, false), nowSeconds = { 100L })
        val key = SessionCrypto.deriveSharedKey(vehicle.private, client.public)
        val metadata = Metadata.Builder().putByte(Metadata.TAG_SIGNATURE_TYPE, 6)
            .putAscii(Metadata.TAG_PERSONALIZATION, vin)
            .put(Metadata.TAG_CHALLENGE, challenge).build()
        for (counter in listOf(0x7fffffff, 0x80000001.toInt(), 0x7ffffffe)) {
            val info = Signatures.SessionInfo.newBuilder().setCounter(counter)
                .setPublicKey(ByteString.copyFrom(SessionCrypto.encodePublicKey(vehicle.public)))
                .setEpoch(ByteString.copyFrom(byteArrayOf(1))).setClockTime(100).build().toByteArray()
            val tag = SessionCrypto.hmacSha256(SessionCrypto.sessionInfoKey(key), metadata + info)
            assertNull(session.applySessionInfo(info, tag, challenge))
        }
        val signed = session.sign(byteArrayOf(1), 0)
        assertEquals(0x80000002.toInt(), signed.signatureData.getAESGCMPersonalizedData().counter)
    }
}

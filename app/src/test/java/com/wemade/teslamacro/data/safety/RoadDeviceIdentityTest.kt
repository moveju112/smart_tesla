package com.wemade.teslamacro.data.safety

import android.app.Application
import android.util.AtomicFile
import app.cash.paparazzi.Paparazzi
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoadDeviceIdentityTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    private val samples = listOf(RoadPoint(37.5, 127.0, 100L, 10.0), RoadPoint(37.5001, 127.0001, 105L, 10.0))
    private val matched = """{"status":"matched","matchings":[{"confidence":0.9,"geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.0001,37.5001]]}}],"unmatchedCount":0}"""

    /** 같은 설치의 개인키로 서명하며 서버로 보낼 공개키·서명만 파일 밖으로 꺼낸다. */
    @Test fun certificateAndProofSurviveRecreation() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val file = AtomicFile(File(temporary.root, "cert.txt"))
        val identity = RoadDeviceIdentity(file) { key }
        assertNull(identity.readCertificate())
        val certificate = "dc1.example.signature"
        identity.saveCertificate(certificate)
        val restored = RoadDeviceIdentity(file) { key }
        assertEquals(certificate, restored.readCertificate())
        assertEquals(key.public.encoded.toList(), Base64.getUrlDecoder().decode(restored.publicKey()).toList())
        val nonce = "bm5ubm5ubm5ubm5ubm5ubg"
        val signature = Base64.getUrlDecoder().decode(restored.sign(certificate, 1234, nonce))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key.public)
        verifier.update("road-session-v1\n$certificate\n1234\n$nonce".toByteArray(Charsets.US_ASCII))
        assertTrue(verifier.verify(signature))
        val different = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        verifier.initVerify(different.public)
        verifier.update("road-session-v1\n$certificate\n1234\n$nonce".toByteArray(Charsets.US_ASCII))
        assertFalse(verifier.verify(signature))
        restored.clearCertificate()
        assertNull(identity.readCertificate())
    }

    /** 최초 가입 뒤 매칭 401은 갱신해 한 번만 재시도하고 다음 매칭에서는 토큰을 재사용한다. */
    @Test fun enrollRenewRetryAndCache() = runBlocking {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "device.txt"))) { key }
        val paths = mutableListOf<String>()
        var matchAttempts = 0
        val matcher = RoadMatcher(Application(), "bootstrap-test-token-0123456789", identity) { path, body, bearer ->
            paths.add(path)
            when (path) {
                "/v1/devices" -> {
                    assertEquals("bootstrap-test-token-0123456789", bearer)
                    assertFalse(body.toString(Charsets.UTF_8).contains("coordinate"))
                    RoadHttpResponse(200, """{"certificate":"dc1.example.signature"}""")
                }
                "/v1/session" -> {
                    assertNull(bearer)
                    assertFalse(body.toString(Charsets.UTF_8).contains("coordinate"))
                    RoadHttpResponse(200, """{"accessToken":"rm1.example.signature","expiresInSeconds":1800}""")
                }
                else -> {
                    assertEquals("/v1/match", path)
                    assertEquals("rm1.example.signature", bearer)
                    assertTrue(body.toString(Charsets.UTF_8).contains("coordinate"))
                    matchAttempts++
                    if (matchAttempts == 1) RoadHttpResponse(401) else RoadHttpResponse(200, matched)
                }
            }
        }
        assertEquals(MatchedRoad(37.5001, 127.0001), matcher.match(samples).road)
        assertEquals(MatchedRoad(37.5001, 127.0001), matcher.match(samples).road)
        assertEquals(listOf("/v1/devices", "/v1/session", "/v1/match", "/v1/session", "/v1/match", "/v1/match"), paths)
    }

    /** 분당 제한·장애는 도로 좌표를 쓰지 않고 기존 인증서로 다음 요청을 복구한다. */
    @Test fun throttledAndUnavailableThenRecover() = runBlocking {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "limit.txt"))) { key }
        val paths = mutableListOf<String>()
        var attempts = 0
        val matcher = RoadMatcher(Application(), "bootstrap-test-token-0123456789", identity) { path, _, _ ->
            paths.add(path)
            when (path) {
                "/v1/devices" -> RoadHttpResponse(200, """{"certificate":"dc1.example.signature"}""")
                "/v1/session" -> RoadHttpResponse(200, """{"accessToken":"rm1.example.signature","expiresInSeconds":1800}""")
                else -> {
                    attempts++
                    if (attempts == 1) RoadHttpResponse(429)
                    else if (attempts == 2) RoadHttpResponse(503)
                    else RoadHttpResponse(200, matched)
                }
            }
        }
        assertEquals(RoadMatchResponse(code = 429), matcher.match(samples))
        assertEquals(RoadMatchResponse(code = 503), matcher.match(samples))
        assertEquals(MatchedRoad(37.5001, 127.0001), matcher.match(samples).road)
        assertEquals(listOf("/v1/devices", "/v1/session", "/v1/match", "/v1/match", "/v1/match"), paths)
    }

    /** 분실된 개인키·인증서 만료는 한 번 재등록하고, 기기 키 생성 실패는 위치를 보내지 않는다. */
    @Test fun expiredCertificateReenrollsAndMissingKeyStaysOffline() = runBlocking {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val file = AtomicFile(File(temporary.root, "device.txt"))
        val identity = RoadDeviceIdentity(file) { key }
        identity.saveCertificate("dc1.stale.signature")
        val paths = mutableListOf<String>()
        var sessions = 0
        val matcher = RoadMatcher(Application(), "bootstrap-test-token-0123456789", identity) { path, _, _ ->
            paths.add(path)
            when (path) {
                "/v1/devices" -> RoadHttpResponse(200, """{"certificate":"dc1.fresh.signature"}""")
                "/v1/session" -> {
                    sessions++
                    if (sessions == 1) RoadHttpResponse(401)
                    else RoadHttpResponse(200, """{"accessToken":"rm1.example.signature","expiresInSeconds":1800}""")
                }
                else -> RoadHttpResponse(200, matched)
            }
        }
        assertEquals(MatchedRoad(37.5001, 127.0001), matcher.match(samples).road)
        assertEquals("dc1.fresh.signature", identity.readCertificate())
        assertEquals(listOf("/v1/session", "/v1/devices", "/v1/session", "/v1/match"), paths)

        val broken = RoadDeviceIdentity(AtomicFile(File(temporary.root, "missing.txt"))) { error("Keystore inaccessible") }
        var sent = false
        val offline = RoadMatcher(Application(), "bootstrap-test-token-0123456789", broken) { _, _, _ ->
            sent = true
            RoadHttpResponse(200)
        }
        assertEquals(RoadMatchResponse(), offline.match(samples))
        assertFalse(sent)
    }
}

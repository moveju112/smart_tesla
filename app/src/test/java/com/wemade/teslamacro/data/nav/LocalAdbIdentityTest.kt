package com.wemade.teslamacro.data.nav

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Test

class LocalAdbIdentityTest {
    /** 인증서 자체 서명과 TLS 서명 키가 일치하며 현재 시각에 유효한지 확인한다. */
    @Test fun certificateSignsAndVerifies() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = LocalAdbIdentity.certificate(pair.private, pair.public.encoded) as X509Certificate
        cert.checkValidity()
        cert.verify(pair.public)
        assertArrayEquals(pair.public.encoded, cert.publicKey.encoded)
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(byteArrayOf(1, 2, 3)) }
        val proof = signer.sign()
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(cert); update(byteArrayOf(1, 2, 3)) }
        assertTrue(verifier.verify(proof))
    }

    /** 셸 삽입·주소·범위를 벗어난 포트는 실행 요청 전에 거절한다. */
    @Test fun acceptsOnlyLocalPortNumbers() {
        listOf("", "0", "65536", "-1", "1;id", "127.0.0.1:1234", " 1234", "１２３４").forEach {
            assertNull(it, WirelessNavigation.validPort(it))
        }
        assertEquals(65535, WirelessNavigation.validPort("65535"))
        assertEquals(1234, WirelessNavigation.validPort("01234"))
        assertEquals("'a'\\''b'", WirelessNavigation.quote("a'b"))
    }
}

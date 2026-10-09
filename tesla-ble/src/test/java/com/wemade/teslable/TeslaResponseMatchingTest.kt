package com.wemade.teslable

import com.google.protobuf.ByteString
import com.tesla.generated.universalmessage.UniversalMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaResponseMatchingTest {
    private val uuid = byteArrayOf(1, 2, 3)
    private val routingAddress = ByteArray(16) { 4 }

    /** UUID 없는 응답의 다른 연결 주소를 거부하고 같은 주소는 수락한다. */
    @Test
    fun `routing address must match when uuid is absent`() {
        for (address in listOf(routingAddress, ByteArray(16) { 5 })) {
            val message = UniversalMessage.RoutableMessage.newBuilder()
                .setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(address)))
                .build()
            if (address.contentEquals(routingAddress)) {
                assertTrue(matchesRequest(message, uuid, routingAddress))
            } else assertFalse(matchesRequest(message, uuid, routingAddress))
        }
    }

    /** UUID가 있으면 주소보다 우선하고 다른 요청 UUID는 거부한다. */
    @Test
    fun `uuid retains priority over routing address`() {
        val builder = UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(UniversalMessage.Destination.newBuilder()
                .setRoutingAddress(ByteString.copyFrom(ByteArray(16) { 5 })))
        assertTrue(matchesRequest(builder.setRequestUuid(ByteString.copyFrom(uuid)).build(), uuid, routingAddress))
        assertFalse(matchesRequest(builder.setRequestUuid(ByteString.copyFrom(byteArrayOf(9))).build(), uuid, routingAddress))
    }

    /** 식별자를 둘 다 생략하는 기존 VCSEC 응답과의 호환성을 유지한다. */
    @Test
    fun `missing identifiers retain serialized vcsec compatibility`() {
        assertTrue(matchesRequest(UniversalMessage.RoutableMessage.getDefaultInstance(), uuid, routingAddress))
    }
}

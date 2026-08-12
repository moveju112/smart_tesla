package com.wemade.teslable.crypto

import java.io.ByteArrayOutputStream

/**
 * 메타데이터 직렬화 (protocol.md "Metadata serialization").
 *
 * 서명/암호화의 AAD가 되는 값이라 **바이트 하나만 틀려도 차가 명령을 거부한다.**
 * 규칙: 태그 오름차순 정렬 -> 각각 `tag || len || value` -> 마지막에 0xFF.
 */
object Metadata {

    // signatures.proto Tag enum
    const val TAG_SIGNATURE_TYPE = 0
    const val TAG_DOMAIN = 1
    const val TAG_PERSONALIZATION = 2
    const val TAG_EPOCH = 3
    const val TAG_EXPIRES_AT = 4
    const val TAG_COUNTER = 5
    const val TAG_CHALLENGE = 6
    const val TAG_FLAGS = 7
    const val TAG_REQUEST_HASH = 8
    const val TAG_FAULT = 9
    const val TAG_END = 0xFF

    /** 태그 순서를 실수로 어기지 못하도록 빌더로만 만들게 한다 */
    class Builder {
        private val items = sortedMapOf<Int, ByteArray>()

        fun put(tag: Int, value: ByteArray) = apply {
            require(value.size <= 255) { "메타데이터 값이 255바이트를 넘는다 (tag=$tag)" }
            items[tag] = value
        }

        /** 정수 값은 항상 빅엔디언 4바이트다 */
        fun putInt(tag: Int, value: Int) = put(tag, intToBigEndian(value))

        fun putByte(tag: Int, value: Int) = put(tag, byteArrayOf(value.toByte()))

        fun putAscii(tag: Int, value: String) = put(tag, value.toByteArray(Charsets.US_ASCII))

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            // sortedMap이라 태그 오름차순이 보장된다
            items.forEach { (tag, value) ->
                out.write(tag)
                out.write(value.size)
                out.write(value)
            }
            out.write(TAG_END)
            return out.toByteArray()
        }
    }

    fun intToBigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

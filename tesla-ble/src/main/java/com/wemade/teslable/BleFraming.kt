package com.wemade.teslable

/**
 * BLE 청크 <-> 메시지 변환.
 * 차량은 [2바이트 빅엔디언 길이][페이로드] 형태를 쓰고, GATT MTU를 넘으면 잘려서 온다.
 * 안드로이드 GATT 콜백은 청크 단위로 올라오므로 여기서 다시 이어붙인다.
 */
object BleFraming {

    /** 전송용으로 길이 헤더를 붙이고 MTU 크기로 자른다 */
    fun frame(payload: ByteArray, chunkSize: Int): List<ByteArray> {
        require(payload.size <= TeslaBleSpec.MAX_MESSAGE_LENGTH) {
            "메시지가 너무 길다 (${payload.size} > ${TeslaBleSpec.MAX_MESSAGE_LENGTH})"
        }
        require(chunkSize > 0) { "chunkSize는 1 이상이어야 한다" }

        val framed = ByteArray(payload.size + 2)
        framed[0] = (payload.size shr 8 and 0xFF).toByte()
        framed[1] = (payload.size and 0xFF).toByte()
        payload.copyInto(framed, destinationOffset = 2)

        return framed.asIterable().chunked(chunkSize) { it.toByteArray() }
    }

    /**
     * 수신 청크를 누적해 완성된 메시지만 뱉는 재조립기.
     * GATT 콜백 스레드에서만 호출된다는 전제라 내부 동기화는 두지 않는다.
     */
    class Reassembler {
        private val buffer = ArrayList<Byte>(TeslaBleSpec.MAX_MESSAGE_LENGTH + 2)

        /** 청크 하나를 넣고, 이번에 완성된 메시지가 있으면 반환한다 */
        fun push(chunk: ByteArray): ByteArray? {
            // 1. 누적
            chunk.forEach { buffer.add(it) }

            // 2. 길이 헤더가 아직 안 찼으면 대기
            if (buffer.size < 2) return null
            val expected = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)

            // 3. 비정상 길이면 버퍼를 버린다 (연결 잡음/desync 복구)
            if (expected > TeslaBleSpec.MAX_MESSAGE_LENGTH) {
                buffer.clear()
                return null
            }

            // 4. 본문이 다 안 왔으면 대기
            if (buffer.size < expected + 2) return null

            // 5. 한 메시지 꺼내고 나머지는 다음 메시지 몫으로 남긴다
            // ponytail: removeAt(0) 반복은 O(n^2)이지만 메시지 상한이 1KB라 무시 가능.
            //           프레임이 커지면 ArrayDeque나 링버퍼로 교체한다
            val message = ByteArray(expected) { buffer[it + 2] }
            repeat(expected + 2) { buffer.removeAt(0) }
            return message
        }

        fun reset() = buffer.clear()
    }
}

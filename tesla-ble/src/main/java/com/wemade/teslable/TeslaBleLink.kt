package com.wemade.teslable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * GATT status 코드에 사람이 읽을 해설을 붙인다 — 실차 로그 진단용.
 * 코드별 분기는 하지 않는다(검증된 참고 앱도 안 한다). 오직 로그 품질용
 */
internal fun gattStatusName(status: Int): String = when (status) {
    0 -> "0(OK)"
    8 -> "8(CONN_TIMEOUT·전파이탈)"
    19 -> "19(REMOTE_TERMINATED·차량측종료)"
    22 -> "22(LOCAL_HOST_TERMINATED)"
    62 -> "62(FAILED_ESTABLISH·응답없음)"
    133 -> "133(GATT_ERROR·스택)"
    147 -> "147(CONN_TIMEOUT)"
    else -> "$status"
}

/**
 * 차량 1대와의 GATT 연결. 바이트만 주고받고 프로토콜 해석은 하지 않는다.
 * 상위 계층(서명/세션)이 이 링크 위에 얹힌다.
 *
 * GATT는 동시에 하나의 오퍼레이션만 허용하므로 쓰기는 [opLock]으로 직렬화한다.
 */
@SuppressLint("MissingPermission")
class TeslaBleLink(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var mtu: Int = DEFAULT_MTU

    /**
     * 링크 사망 표시(단방향). 끊김 콜백·쓰기 거부·쓰기 완료 타임아웃에서 선다.
     * GATT 객체와 characteristic이 멀쩡해 보여도 이 플래그가 서면 죽은 링크다.
     * isConnected가 이걸 봐서, 콜백이 아예 안 오는 "좀비 GATT"도 쓰기 실패 즉시 드러난다
     */
    @Volatile
    private var dead = false

    /**
     * 사망 처리 공통 지점: 플래그를 세우고 끊김 이벤트를 즉시 방출한다.
     * 방출 덕에 게이트웨이가 다음 ensureLinked를 기다리지 않고 바로 상태를 내린다.
     *
     * [source] 세대 검사: 옛 GATT에서 5초짜리 쓰기 타임아웃이 늦게 터지면
     * 그 사이 재연결로 태어난 새 링크를 죽여버린다 — 지금 GATT가 아니면 무시한다
     */
    private fun markDead(source: BluetoothGatt?) {
        if (source !== gatt) return
        if (dead) return
        dead = true
        _disconnects.tryEmit(Unit)
    }

    private val reassembler = BleFraming.Reassembler()
    private val opLock = Mutex()

    private var connectResult: CompletableDeferred<Unit>? = null
    private var writeResult: CompletableDeferred<Unit>? = null

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 재조립이 끝난 완성 메시지 스트림 */
    val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _disconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnects: Flow<Unit> = _disconnects.asSharedFlow()

    val isConnected: Boolean get() = txCharacteristic != null && !dead

    /** 연결 -> MTU 협상 -> 서비스 탐색 -> notify 구독까지 마치고 돌아온다 */
    suspend fun connect(
        device: BluetoothDevice,
        timeoutMillis: Long = CONNECT_TIMEOUT_MS,
        autoConnect: Boolean = false,
    ) {
        // 끊김 콜백의 정리(close)와 재연결 시도가 경합하면 이전 껍데기가 남아 있을 수 있다.
        // 예외로 한 사이클을 버리는 대신 여기서 치우고 새로 연다
        if (gatt != null) {
            DiagLog.add("이전 GATT 잔재 정리 후 재연결")
            close()
        }
        val deferred = CompletableDeferred<Unit>()
        connectResult = deferred
        reassembler.reset()
        dead = false   // 새 연결의 생사는 새로 판정한다

        DiagLog.add("GATT 접속 시도 ${device.address}" + if (autoConnect) " (autoConnect)" else "")
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /** 페이로드에 길이 헤더를 붙여 청크로 나눠 보낸다 */
    suspend fun send(payload: ByteArray, timeoutMillis: Long = WRITE_TIMEOUT_MS) = opLock.withLock {
        // 죽은 링크엔 안 쏜다 — ensureLinked를 안 거치는 경로(등록 등)도 여기서 다 막힌다
        if (dead) error("링크가 죽었다 — 재연결 필요")
        val characteristic = txCharacteristic ?: error("연결되어 있지 않다")
        val activeGatt = gatt ?: error("연결되어 있지 않다")

        // ATT 헤더 3바이트를 빼야 실제 페이로드 크기가 된다
        for (chunk in BleFraming.frame(payload, mtu - ATT_HEADER_SIZE)) {
            val deferred = CompletableDeferred<Unit>()
            writeResult = deferred
            writeChunkWithRetry(activeGatt, characteristic, chunk)
            try {
                withTimeout(timeoutMillis) { deferred.await() }
            } catch (t: TimeoutCancellationException) {
                // 완료 콜백이 영영 안 오는 건 스택이 죽은 것 — 살아있는 척을 끝낸다
                markDead(activeGatt)
                throw IllegalStateException("쓰기 완료 미수신 ${timeoutMillis}ms — 링크 사망 처리", t)
            }
        }
    }

    fun close() {
        // disconnect 없이 close만 하면 일부 스택이 링크를 물고 있는다 — 각각 독립 실행
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        txCharacteristic = null
        mtu = DEFAULT_MTU
        reassembler.reset()
    }

    /** writeCharacteristic 제출 결과 — 큐에 실림 / 바쁨(재시도 가치 있음) / 그 외 거부 */
    private enum class WriteAccept { QUEUED, BUSY, REJECTED }

    /**
     * GATT가 바쁘다고 거부하면 150ms 계단(150/300/450ms)으로 최대 4회 다시 민다.
     * busy가 아닌 거부는 재시도 가치가 없다 — 즉시 사망 처리. 마지막 거부 뒤엔 기다리지 않는다
     */
    private suspend fun writeChunkWithRetry(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        repeat(WRITE_ATTEMPTS) { attempt ->
            when (writeChunk(gatt, characteristic, chunk)) {
                WriteAccept.QUEUED -> return
                WriteAccept.BUSY ->
                    if (attempt < WRITE_ATTEMPTS - 1) delay(WRITE_RETRY_STEP_MS * (attempt + 1))
                WriteAccept.REJECTED -> {
                    markDead(gatt)
                    error("writeCharacteristic 거부 (busy 아님)")
                }
            }
        }
        markDead(gatt)
        error("writeCharacteristic ${WRITE_ATTEMPTS}회 연속 거부 (GATT busy)")
    }

    // 안드로이드 13에서 쓰기 API가 바뀌어 분기한다.
    // 12 이하는 boolean뿐이라 거부 사유를 알 수 없다 — 전부 BUSY로 보고 재시도한다
    private fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): WriteAccept {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (gatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
                BluetoothStatusCodes.SUCCESS -> WriteAccept.QUEUED
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteAccept.BUSY
                else -> WriteAccept.REJECTED
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) WriteAccept.QUEUED else WriteAccept.BUSY
        }
    }

    private val callback = object : BluetoothGattCallback() {

        /**
         * close() 뒤에 도착하는 지각 콜백 차단.
         * 안 막으면 타임아웃으로 닫은 직후 onServicesDiscovered가 txCharacteristic을
         * 되살려 isConnected가 참으로 오판된다 (gatt는 이미 null인데)
         */
        private fun isStale(source: BluetoothGatt): Boolean = source !== this@TeslaBleLink.gatt

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (isStale(gatt)) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    DiagLog.add("GATT 연결됨 → MTU 협상")
                    gatt.requestMtu(PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    DiagLog.add("GATT 끊김 (status=${gattStatusName(status)})")
                    txCharacteristic = null
                    connectResult?.completeExceptionally(
                        IllegalStateException("연결이 끊겼다 (status=${gattStatusName(status)})")
                    )
                    markDead(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, negotiated: Int, status: Int) {
            if (isStale(gatt)) return
            // MTU 협상 실패는 치명적이지 않다. 기본값으로 계속 간다
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) negotiated else DEFAULT_MTU
            DiagLog.add("MTU=$mtu → 서비스 탐색")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isStale(gatt)) return
            val service = gatt.getService(TeslaBleSpec.SERVICE_UUID)
            val tx = service?.getCharacteristic(TeslaBleSpec.TX_CHARACTERISTIC_UUID)
            val rx = service?.getCharacteristic(TeslaBleSpec.RX_CHARACTERISTIC_UUID)

            if (tx == null || rx == null) {
                // 이 기기가 실제로 뭘 갖고 있는지 남긴다. "차가 아니었다"의 증거가 된다
                val services = gatt.services.joinToString { it.uuid.toString().take(8) }
                DiagLog.add("테슬라 서비스 없음. 보유 서비스: [$services]")
                connectResult?.completeExceptionally(
                    IllegalStateException("차량 GATT 서비스를 찾지 못했다")
                )
                return
            }
            DiagLog.add("테슬라 서비스 확인 → 알림 구독")

            txCharacteristic = tx
            gatt.setCharacteristicNotification(rx, true)
            enableNotification(gatt, rx)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (isStale(gatt)) return
            // CCCD 쓰기가 끝나야 알림이 실제로 열린다. 여기가 연결 완료 지점
            if (descriptor.uuid == TeslaBleSpec.CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    DiagLog.add("연결 완료")
                    connectResult?.complete(Unit)
                } else {
                    connectResult?.completeExceptionally(
                        IllegalStateException("알림 구독 실패 (status=${gattStatusName(status)})")
                    )
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (isStale(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeResult?.complete(Unit)
            } else {
                markDead(gatt)
                writeResult?.completeExceptionally(
                    IllegalStateException("쓰기 실패 (status=${gattStatusName(status)})")
                )
            }
        }

        // API 33+ 경로
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (isStale(gatt)) return
            handleIncoming(characteristic, value)
        }

        // API 32 이하 경로
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (isStale(gatt)) return
            @Suppress("DEPRECATION")
            handleIncoming(characteristic, characteristic.value ?: return)
        }
    }

    private fun handleIncoming(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != TeslaBleSpec.RX_CHARACTERISTIC_UUID) return
        reassembler.push(value)?.let {
            // 완성된 응답이 실제로 도착하는지 눈으로 본다. 응답 자체가 안 오는지,
            // 오는데 매칭에서 버려지는지를 가른다
            DiagLog.add("응답 수신 ${it.size}B")
            _incoming.tryEmit(it)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, rx: BluetoothGattCharacteristic) {
        val cccd = rx.getDescriptor(TeslaBleSpec.CCCD_UUID) ?: run {
            connectResult?.completeExceptionally(IllegalStateException("CCCD를 찾지 못했다"))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 512
        const val ATT_HEADER_SIZE = 3
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val WRITE_ATTEMPTS = 4
        const val WRITE_RETRY_STEP_MS = 150L
    }
}

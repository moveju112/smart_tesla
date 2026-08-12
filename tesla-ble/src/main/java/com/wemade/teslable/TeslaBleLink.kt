package com.wemade.teslable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

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

    val isConnected: Boolean get() = txCharacteristic != null

    /** 연결 -> MTU 협상 -> 서비스 탐색 -> notify 구독까지 마치고 돌아온다 */
    suspend fun connect(
        device: BluetoothDevice,
        timeoutMillis: Long = CONNECT_TIMEOUT_MS,
        autoConnect: Boolean = false,
    ) {
        check(gatt == null) { "이미 연결되어 있다" }
        val deferred = CompletableDeferred<Unit>()
        connectResult = deferred
        reassembler.reset()

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
        val characteristic = txCharacteristic ?: error("연결되어 있지 않다")
        val activeGatt = gatt ?: error("연결되어 있지 않다")

        // ATT 헤더 3바이트를 빼야 실제 페이로드 크기가 된다
        for (chunk in BleFraming.frame(payload, mtu - ATT_HEADER_SIZE)) {
            val deferred = CompletableDeferred<Unit>()
            writeResult = deferred
            writeChunk(activeGatt, characteristic, chunk)
            withTimeout(timeoutMillis) { deferred.await() }
        }
    }

    fun close() {
        runCatching { gatt?.close() }
        gatt = null
        txCharacteristic = null
        mtu = DEFAULT_MTU
        reassembler.reset()
    }

    // 안드로이드 13에서 쓰기 API가 바뀌어 분기한다
    private fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    DiagLog.add("GATT 연결됨 → MTU 협상")
                    gatt.requestMtu(PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    DiagLog.add("GATT 끊김 (status=$status)")
                    txCharacteristic = null
                    connectResult?.completeExceptionally(
                        IllegalStateException("연결이 끊겼다 (status=$status)")
                    )
                    _disconnects.tryEmit(Unit)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, negotiated: Int, status: Int) {
            // MTU 협상 실패는 치명적이지 않다. 기본값으로 계속 간다
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) negotiated else DEFAULT_MTU
            DiagLog.add("MTU=$mtu → 서비스 탐색")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
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
            // CCCD 쓰기가 끝나야 알림이 실제로 열린다. 여기가 연결 완료 지점
            if (descriptor.uuid == TeslaBleSpec.CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    DiagLog.add("연결 완료")
                    connectResult?.complete(Unit)
                } else {
                    connectResult?.completeExceptionally(
                        IllegalStateException("알림 구독 실패 (status=$status)")
                    )
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeResult?.complete(Unit)
            } else {
                writeResult?.completeExceptionally(
                    IllegalStateException("쓰기 실패 (status=$status)")
                )
            }
        }

        // API 33+ 경로
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncoming(characteristic, value)
        }

        // API 32 이하 경로
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
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
    }
}

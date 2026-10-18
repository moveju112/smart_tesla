package com.wemade.teslamacro.data.gateway

import android.bluetooth.BluetoothGattCharacteristic
import app.cash.paparazzi.Paparazzi
import com.wemade.teslable.BleFraming
import com.wemade.teslable.TeslaBleLink
import com.wemade.teslable.TeslaBleSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BleIncomingFramesTest {
    @get:Rule val paparazzi = Paparazzi()

    /** 실차 없이 수신 경계에 청크를 넣어 추가 알림 없이 두 응답 모두 전달되는지 확인한다. */
    @Test fun mergedResponsesAreDeliveredWithoutAnotherNotification() = runTest {
        val link = TeslaBleLink(paparazzi.context)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            link.incoming.collect { received.add(it) }
        }
        val characteristic = BluetoothGattCharacteristic(
            TeslaBleSpec.RX_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val chunk = BleFraming.frame(byteArrayOf(9), 100).single() +
            BleFraming.frame(byteArrayOf(8), 100).single()
        val receive = TeslaBleLink::class.java.getDeclaredMethod(
            "handleIncoming", BluetoothGattCharacteristic::class.java, ByteArray::class.java)
        receive.isAccessible = true
        receive.invoke(link, characteristic, chunk)
        assertEquals(2, received.size)
        assertArrayEquals(byteArrayOf(9), received[0])
        assertArrayEquals(byteArrayOf(8), received[1])
    }
}

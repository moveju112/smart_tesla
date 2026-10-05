package com.wemade.teslamacro.nav

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import com.wemade.teslamacro.data.nav.LocalAdbIdentity
import com.wemade.teslamacro.data.nav.WirelessNavigation
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 로컬 에뮬레이터와 지도 대체 앱으로 연결·잠금 실행·오디오 해제 종료를 검사한다. */
class WirelessNavigationSmokeInstrumentation : Instrumentation() {
    /** 별도 계측 실행기로만 테스트를 시작하며 일반 앱 실행에는 포함하지 않는다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** RSA 저장 복원과 로컬 ADB 세션을 검증하고 자동 실행 선택은 끝날 때 해제한다. */
    override fun onStart() {
        val result = Bundle()
        var status = Activity.RESULT_OK
        var controller: WirelessNavigation? = null
        val key = File(targetContext.cacheDir, "nav-smoke-key.p12")
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator only" }
            val original = LocalAdbIdentity(key).load()
            val restored = LocalAdbIdentity(key).load()
            check(original.first.encoded.contentEquals(restored.first.encoded))
            restored.second.verify(restored.second.publicKey)
            runBlocking {
                val navigation = withContext(Dispatchers.Main) { WirelessNavigation(targetContext).also { controller = it } }
                withContext(Dispatchers.Main) {
                    navigation.setPort("5555")
                    navigation.setEnabled(true)
                    navigation.vehicleChanged(true)
                }
                withTimeout(30_000) { navigation.state.first { it.running } }
                withContext(Dispatchers.Main) { navigation.vehicleChanged(false) }
                delay(1_000)
                withContext(Dispatchers.Main) { navigation.vehicleChanged(true) }
                check(navigation.state.value.running)
                withContext(Dispatchers.Main) { navigation.vehicleChanged(false) }
                withTimeout(50_000) { navigation.state.first { !it.running && !it.busy } }
                check(navigation.state.value.message == "실험 종료 완료") { navigation.state.value.message }
            }
            result.putString("result", "PASS: key persistence, local ADB launch, reconnect grace, automatic stop")
        } catch (error: Throwable) {
            status = Activity.RESULT_CANCELED
            result.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runOnMainSync { controller?.setEnabled(false) }
            key.delete()
        }
        finish(status, result)
    }
}

package com.wemade.teslamacro.data.nav

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 알림과 접근성은 한 예약기를 공유해 목적지 하나를 공식 앱에 한 번만 넘긴다. */
internal class TeslaNavigationShare(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val navigator: NaverNavigator,
) {
    private val tracker = TeslaNavigationDestination()
    private val lock = Mutex()

    /** 안내 알림에 포함된 목적지와 같은 앱의 최신 화면 후보만 결합한다. */
    suspend fun notification(packageName: String, key: String, title: String, text: String) = lock.withLock {
        if (!enabled()) return@withLock
        tracker.notification(packageName, key, title, text, SystemClock.elapsedRealtime())?.let { share(it) }
    }

    /** 화면만 고르는 동작은 전송하지 않고 안내 시작이 확인됐을 때만 공유한다. */
    suspend fun screen(packageName: String, destination: String) = lock.withLock {
        if (!enabled()) return@withLock
        tracker.screen(packageName, destination, SystemClock.elapsedRealtime())?.let { share(it) }
    }

    /** 종료된 안내는 같은 이름의 다음 목적지와 분리한다. */
    suspend fun removed(packageName: String, key: String) = lock.withLock { tracker.removed(packageName, key) }

    /** OFF 설정과 함께 대기 중인 후보를 정리한다. */
    suspend fun clear() = lock.withLock { tracker.clear() }

    /** OFF 이후 늦게 도착하는 알림·접근성 이벤트도 전송 권한을 얻지 못한다. */
    private suspend fun enabled(): Boolean {
        if (settingsStore.settings.first().teslaNavigationShareEnabled) return true
        tracker.clear()
        return false
    }

    /** 전달 직전에 설정·잠금을 재검사하고 접수와 차량 수신을 구분한다. */
    private suspend fun share(destination: String) {
        try {
            val settings = settingsStore.settings.first()
            navigator.shareTeslaDestination(destination, settings.teslaNavigationLaunchMode == TeslaNavigationLaunchMode.ADB_FIRST) {
                check(settingsStore.settings.first().teslaNavigationShareEnabled) { "테슬라 내비 연동이 꺼져 있어요" }
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                check(!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) { "휴대폰 잠금을 해제하고 길안내를 다시 시작해 주세요" }
            }.getOrThrow()
            DiagLog.add("테슬라 내비 연동 — 공식 앱에 목적지 공유 요청 전달 · 차량 수신 미확인")
            withContext(Dispatchers.Main) { Toast.makeText(context, "테슬라 앱에 목적지 공유를 요청했어요", Toast.LENGTH_SHORT).show() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            DiagLog.add("테슬라 내비 연동 — 공유 실패 ${error.javaClass.simpleName} · 자동 재전송 안 함")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (error is DestinationLaunchException || error is IllegalStateException)
                    error.message ?: "테슬라 앱과 공유 권한을 확인해 주세요"
                    else "테슬라 앱과 공유 권한을 확인해 주세요", Toast.LENGTH_LONG).show()
            }
        }
    }
}

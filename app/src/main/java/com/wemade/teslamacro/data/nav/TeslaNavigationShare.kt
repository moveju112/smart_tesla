package com.wemade.teslamacro.data.nav

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val scope: CoroutineScope,
    private val currentPoint: suspend () -> com.wemade.teslamacro.domain.macro.GeoPoint? = { null },
    private val lookup: suspend (String, com.wemade.teslamacro.domain.macro.GeoPoint?) -> List<TeslaDestinationCandidate> = navigator::teslaDestinationCandidates,
) {
    private val tracker = TeslaNavigationDestination()
    private val lock = Mutex()
    private val mutableSelection = MutableStateFlow<TeslaDestinationSelection?>(null)
    val selection = mutableSelection.asStateFlow()
    private var revision = 0L
    private var source: Pair<String, String>? = null
    private var startedAt = 0L
    private var resolution: Job? = null

    /** 안내 알림에 포함된 목적지와 같은 앱의 최신 화면 후보만 결합한다. */
    suspend fun notification(packageName: String, key: String, title: String, text: String) = lock.withLock {
        if (!enabled()) return@withLock
        tracker.notification(packageName, key, title, text, SystemClock.elapsedRealtime())?.let { resolve(it, packageName, tracker.activeKey(packageName).orEmpty()) }
    }

    /** 화면만 고르는 동작은 전송하지 않고 안내 시작이 확인됐을 때만 공유한다. */
    suspend fun screen(packageName: String, destination: String) = lock.withLock {
        if (!enabled()) return@withLock
        tracker.screen(packageName, destination, SystemClock.elapsedRealtime())?.let { resolve(it, packageName, tracker.activeKey(packageName).orEmpty()) }
    }

    /** 안내 종료는 조회와 선택도 무효화해 과거 안내를 뒤늦게 전송하지 않는다. */
    suspend fun removed(packageName: String, key: String) = lock.withLock {
        tracker.removed(packageName, key)
        if (source == (packageName to key)) invalidate()
    }

    /** OFF는 화면 캐시·위치 조회·선택 대기까지 함께 제거한다. */
    suspend fun clear() = lock.withLock { tracker.clear(); invalidate() }

    /** 늦은 조회 응답과 이전 선택 버튼을 현재 요청에 적용하지 않는다. */
    private fun invalidate() {
        revision++
        resolution?.cancel()
        resolution = null
        source = null
        mutableSelection.value = null
    }

    /** OFF 이후 이벤트는 조회와 전송 권한을 얻지 못한다. */
    private suspend fun enabled(): Boolean {
        if (settingsStore.settings.first().teslaNavigationShareEnabled) return true
        tracker.clear()
        invalidate()
        return false
    }

    /** 조회는 알림 잠금 밖에서 실행하고 새 안내·OFF가 이전 결과를 폐기할 수 있게 한다. */
    private fun resolve(query: String, packageName: String, key: String) {
        invalidate()
        source = packageName to key
        startedAt = SystemClock.elapsedRealtime()
        val id = revision
        resolution = scope.launch { findCandidates(id, query, automatic = true) }
    }

    /** 원본 좌표는 바로 공유하고 상호·일부 주소는 후보가 하나여도 사용자가 확인한다. */
    private suspend fun findCandidates(id: Long, query: String, automatic: Boolean) {
        try {
            val sourcePoint = teslaDestinationPoint(query)
            val near = if (sourcePoint == null) currentPoint() else null
            val candidates = if (sourcePoint != null) listOf(TeslaDestinationCandidate(query, sourcePoint))
                else rankedTeslaCandidates(lookup(query, near), near).ifEmpty {
                    if (qualifiedTeslaRoadAddress(query)) listOf(TeslaDestinationCandidate(query)) else emptyList()
                }
            lock.withLock {
                if (id != revision || !enabled() || !fresh()) return@withLock
                val exact = if (automatic) sourcePoint?.let { candidates.single() } ?: automaticTeslaCandidate(query, candidates) else null
                if (exact != null) share(exact.shareText, id)
                else {
                    DiagLog.add("테슬라 내비 연동 — 목적지 주소 확인 필요")
                    mutableSelection.value = TeslaDestinationSelection(id, query, candidates,
                        error = if (candidates.isEmpty()) "시·군·구를 포함한 주소로 다시 검색해 주세요" else null)
                    if (automatic) feedback("Smart Tesla에서 목적지 주소를 확인해 주세요")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            lock.withLock {
                if (id == revision && enabled() && fresh()) {
                    mutableSelection.value = TeslaDestinationSelection(id, query, error = "주소를 조회하지 못했어요 · 전체 주소로 다시 검색해 주세요")
                    feedback("Smart Tesla에서 목적지 주소를 확인해 주세요")
                }
            }
        }
    }

    /** 오래 방치한 선택은 현재 주행의 목적지로 재사용하지 않는다. */
    private fun fresh(): Boolean = SystemClock.elapsedRealtime() - startedAt in 0..300_000

    /** 선택창에서 보완한 주소만 다시 조회하고 자동 확정하지 않는다. */
    suspend fun search(id: Long, query: String) = lock.withLock {
        if (mutableSelection.value?.id != id || !enabled()) return@withLock
        if (!fresh()) { invalidate(); feedback("길안내를 다시 시작해 주세요"); return@withLock }
        val normalized = TeslaNavigationDestination.normalize(query)
        if (normalized == null) {
            mutableSelection.value = mutableSelection.value?.copy(error = "주소를 1~200자로 입력해 주세요")
            return@withLock
        }
        resolution?.cancel()
        val nextId = ++revision
        mutableSelection.value = TeslaDestinationSelection(nextId, normalized, searching = true)
        resolution = scope.launch { findCandidates(nextId, normalized, automatic = false) }
    }

    /** 현재 후보만 한 번 소비한 뒤 동일한 ADB·일반 실행 통로로 공유한다. */
    suspend fun confirm(id: Long, candidate: TeslaDestinationCandidate) = lock.withLock {
        val current = mutableSelection.value ?: return@withLock
        if (current.id != id || current.searching || candidate !in current.candidates || !enabled()) return@withLock
        if (!fresh()) { invalidate(); feedback("길안내를 다시 시작해 주세요"); return@withLock }
        mutableSelection.value = null
        share(candidate.shareText, id)
    }

    /** 선택 취소는 자동 재전송 없이 해당 안내의 대기를 끝낸다. */
    suspend fun dismiss(id: Long) = lock.withLock { if (mutableSelection.value?.id == id) invalidate() }

    /** 백그라운드 선택 요청도 레이아웃을 늘리지 않는 일시 알림으로 전달한다. */
    private suspend fun feedback(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** 전달 직전에 설정·잠금을 재검사하고 접수와 차량 수신을 구분한다. */
    private suspend fun share(destination: String, id: Long) {
        try {
            val settings = settingsStore.settings.first()
            navigator.shareTeslaDestination(destination, settings.teslaNavigationLaunchMode == TeslaNavigationLaunchMode.ADB_FIRST) {
                check(id == revision && fresh()) { "길안내를 다시 시작해 주세요" }
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

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
    suspend fun removed(packageName: String, key: String, reason: Int? = null) = lock.withLock {
        tracker.removed(packageName, key)
        if (source == (packageName to key)) invalidate("안내 알림 종료 · 사유=${reason ?: -1}")
    }

    /** OFF는 화면 캐시·위치 조회·선택 대기까지 함께 제거한다. */
    suspend fun clear() = lock.withLock { tracker.clear(); invalidate("연동 설정 OFF") }

    /** 모드 저장과 예약 취소를 같은 잠금에서 처리해 테스트 후보가 실제 공유로 바뀌지 않게 한다. */
    suspend fun setTestMode(enabled: Boolean) = lock.withLock {
        tracker.clear()
        invalidate("테스트 모드 변경")
        settingsStore.setTeslaNavigationTestMode(enabled)
    }

    /** 늦은 조회 응답과 이전 선택 버튼을 현재 요청에 적용하지 않는다. */
    private fun invalidate(reason: String) {
        if (mutableSelection.value != null || resolution?.isActive == true) {
            DiagLog.add("테슬라 내비 연동 — 목적지 확인 대기 종료 · $reason")
        }
        revision++
        resolution?.cancel()
        resolution = null
        source = null
        mutableSelection.value = null
    }

    /** OFF 이후 이벤트는 조회와 전송 권한을 얻지 못한다. */
    private suspend fun enabled(): Boolean {
        val settings = settingsStore.settings.first()
        if (settings.teslaNavigationShareEnabled || settings.teslaNavigationTestMode) return true
        tracker.clear()
        invalidate("연동 설정 OFF")
        return false
    }

    /** 조회는 알림 잠금 밖에서 실행하고 새 안내·OFF가 이전 결과를 폐기할 수 있게 한다. */
    private suspend fun resolve(query: String, packageName: String, key: String) {
        invalidate("새 길안내 수신")
        source = packageName to key
        startedAt = SystemClock.elapsedRealtime()
        val id = revision
        val testRequest = settingsStore.settings.first().teslaNavigationTestMode
        resolution = scope.launch { findCandidates(id, query, testRequest) }
    }

    /** 원본 좌표·단일 후보는 공유하고 복수 후보와 조회 실패는 오버레이 선택으로 넘긴다. */
    private suspend fun findCandidates(id: Long, query: String, testRequest: Boolean) {
        try {
            val sourcePoint = teslaDestinationPoint(query)
            val near = if (sourcePoint == null) currentPoint() else null
            val candidates = if (sourcePoint != null) listOf(TeslaDestinationCandidate(query, sourcePoint))
                else rankedTeslaCandidates(matchingTeslaAddressCandidates(query, lookup(query, near)), near).ifEmpty {
                    if (qualifiedTeslaRoadAddress(query)) listOf(TeslaDestinationCandidate(query)) else emptyList()
                }
            lock.withLock {
                if (id != revision || !enabled() || !fresh()) return@withLock
                val exact = automaticTeslaCandidate(candidates)
                val testMode = testRequest || settingsStore.settings.first().teslaNavigationTestMode
                if (exact != null && testMode) showPreview(id, query, exact)
                else if (exact != null) {
                    mutableSelection.value = null
                    share(exact.shareText, id)
                }
                else {
                    DiagLog.add("테슬라 내비 연동 — 목적지 주소 확인 필요 · 후보=${candidates.size}")
                    mutableSelection.value = TeslaDestinationSelection(id, query, candidates,
                        error = if (candidates.isEmpty()) "시·군·구를 포함한 주소로 다시 검색해 주세요" else null,
                        testMode = testMode)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            lock.withLock {
                if (id == revision && enabled() && fresh()) {
                    mutableSelection.value = TeslaDestinationSelection(id, query, error = "주소를 조회하지 못했어요 · 전체 주소로 다시 검색해 주세요",
                        testMode = testRequest || settingsStore.settings.first().teslaNavigationTestMode)
                    DiagLog.add("테슬라 내비 연동 — 주소 조회 실패 · 목적지 확인 대기")
                }
            }
        }
    }

    /** 오래 방치한 선택은 현재 주행의 목적지로 재사용하지 않는다. */
    private fun fresh(): Boolean = SystemClock.elapsedRealtime() - startedAt in 0..300_000

    /** 보완한 주소를 다시 조회하며 단일 후보면 같은 공유 경로로 자동 전달한다. */
    suspend fun search(id: Long, query: String) = lock.withLock {
        if (mutableSelection.value?.id != id || !enabled()) return@withLock
        if (!fresh()) { invalidate("확인 시간 만료"); feedback("길안내를 다시 시작해 주세요"); return@withLock }
        val normalized = TeslaNavigationDestination.normalize(query)
        if (normalized == null) {
            mutableSelection.value = mutableSelection.value?.copy(error = "주소를 1~200자로 입력해 주세요")
            return@withLock
        }
        resolution?.cancel()
        val nextId = ++revision
        val testRequest = mutableSelection.value?.testMode == true || settingsStore.settings.first().teslaNavigationTestMode
        mutableSelection.value = TeslaDestinationSelection(nextId, normalized, searching = true, testMode = testRequest)
        resolution = scope.launch { findCandidates(nextId, normalized, testRequest) }
    }

    /** 선택창이 닫혀도 공유가 취소되지 않도록 앱 수명의 작업으로 넘긴다. */
    fun select(id: Long, candidate: TeslaDestinationCandidate) {
        scope.launch { confirm(id, candidate) }
    }

    /** 현재 후보만 한 번 소비한 뒤 동일한 ADB·일반 실행 통로로 공유한다. */
    suspend fun confirm(id: Long, candidate: TeslaDestinationCandidate) = lock.withLock {
        val current = mutableSelection.value ?: return@withLock
        if (current.id != id || current.searching || candidate !in current.candidates || !enabled()) return@withLock
        if (!fresh()) { invalidate("확인 시간 만료"); feedback("길안내를 다시 시작해 주세요"); return@withLock }
        if (current.testMode) { showPreview(id, current.query, candidate); return@withLock }
        mutableSelection.value = null
        share(candidate.shareText, id)
    }

    // 테스트 모드 결과 표시 (보냈을 목적지 -> 보조창)
    /** 실제 공유 대신 테슬라로 보냈을 주소·좌표를 같은 보조창에 남겨 안내 종료·닫기 전까지 확인하게 한다. */
    private fun showPreview(id: Long, query: String, candidate: TeslaDestinationCandidate) {
        DiagLog.add("테슬라 내비 테스트 — 보낼 목적지 표시 · 실제 공유 차단")
        mutableSelection.value = TeslaDestinationSelection(id, query, testMode = true, preview = candidate)
    }

    /** 선택 취소는 자동 재전송 없이 해당 안내의 대기를 끝낸다. */
    suspend fun dismiss(id: Long) = lock.withLock { if (mutableSelection.value?.id == id) invalidate("선택창 취소") }

    /** 백그라운드 선택 요청도 레이아웃을 늘리지 않는 일시 알림으로 전달한다. */
    private suspend fun feedback(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** 전달 직전에 설정·잠금을 재검사하고 접수와 차량 수신을 구분한다. */
    private suspend fun share(destination: String, id: Long) {
        try {
            val settings = settingsStore.settings.first()
            if (settings.teslaNavigationTestMode) {
                DiagLog.add("테슬라 내비 테스트 — 목적지 확인 완료 · 실제 공유 차단")
                feedback("테스트 목적지 확인 완료 · 차량에 보내지 않았어요")
                return
            }
            navigator.shareTeslaDestination(destination, settings.teslaNavigationLaunchMode == TeslaNavigationLaunchMode.ADB_FIRST) {
                check(id == revision && fresh()) { "길안내를 다시 시작해 주세요" }
                check(settingsStore.settings.first().teslaNavigationShareEnabled) { "테슬라 내비 연동이 꺼져 있어요" }
                check(!settingsStore.settings.first().teslaNavigationTestMode) { "테스트 모드에서는 차량에 보내지 않아요" }
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

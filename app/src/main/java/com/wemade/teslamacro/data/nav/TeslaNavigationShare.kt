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
    private val regionOf: suspend (com.wemade.teslamacro.domain.macro.GeoPoint) -> String? = { navigator.addressOf(it) },
) {
    private val tracker = TeslaNavigationDestination()
    private val lock = Mutex()
    private val mutableSelection = MutableStateFlow<TeslaDestinationSelection?>(null)
    val selection = mutableSelection.asStateFlow()
    private var revision = 0L
    private var source: Pair<String, String>? = null
    private var startedAt = 0L
    private var resolution: Job? = null
    private var ignored: String? = null
    private var repeatedAt = -30_001L
    private val routeDistances = ArrayDeque<Pair<Long, Int>>()

    /** 안내 알림에 포함된 목적지와 같은 앱의 최신 화면 후보만 결합한다. */
    suspend fun notification(packageName: String, key: String, title: String, text: String) = lock.withLock {
        if (!enabled()) return@withLock
        val guiding = isTeslaNavigationGuidance(packageName, title, text)
        val known = tracker.activeKey(packageName) == key
        val destination = tracker.notification(packageName, key, title, text, SystemClock.elapsedRealtime())
        if (destination != null) {
            DiagLog.add("테슬라 내비 연동 — 안내 알림 감지 · 목적지 확인")
            resolve(destination, packageName, tracker.activeKey(packageName).orEmpty())
        } else if (guiding && !known) {
            DiagLog.add("테슬라 내비 연동 — 안내 알림 감지 · 화면 목적지 대기")
            awaitScreen(packageName, key)
        } else if (guiding && source == null && SystemClock.elapsedRealtime() - repeatedAt > 30_000) {
            // 대기 없이 같은 안내 알림만 갱신되는 경우를 기록해 재시작 미감지를 구분한다.
            repeatedAt = SystemClock.elapsedRealtime()
            DiagLog.add("테슬라 내비 연동 — 같은 안내 알림 갱신 · 새 안내로 보지 않음")
        } else if (!guiding && ignored != text) {
            ignored = text
            DiagLog.add("테슬라 내비 연동 — 안내 아님 알림 무시 · ${title.take(20)} / ${text.take(30)}")
        }
    }

    // 화면 목적지 대기 (안내 시작 -> 5초 내 화면 판독 없음 -> 주소 입력창)
    /** 화면에서 목적지를 못 읽어도 조용히 끝내지 않고 주소 입력창을 띄워 사용자가 보완하게 한다. */
    private fun awaitScreen(packageName: String, key: String) {
        invalidate("새 길안내 수신")
        source = packageName to key
        startedAt = SystemClock.elapsedRealtime()
        val id = revision
        resolution = scope.launch {
            kotlinx.coroutines.delay(5_000)
            lock.withLock {
                if (id != revision || !enabled() || !fresh()) return@withLock
                DiagLog.add("테슬라 내비 연동 — 화면 목적지 없음 · 주소 입력 대기")
                mutableSelection.value = TeslaDestinationSelection(id, "", error = "목적지를 읽지 못했어요 · 주소를 입력해 주세요",
                    testMode = settingsStore.settings.first().teslaNavigationTestMode)
            }
        }
    }

    /** 화면만 고르는 동작은 전송하지 않고 안내 시작이 확인됐을 때만 공유한다. */
    suspend fun screen(packageName: String, destination: String) = lock.withLock {
        if (!enabled()) return@withLock
        tracker.screen(packageName, destination, SystemClock.elapsedRealtime())?.let { resolve(it, packageName, tracker.activeKey(packageName).orEmpty()) }
    }

    /** 안내 종료는 조회와 선택도 무효화해 과거 안내를 뒤늦게 전송하지 않는다. */
    suspend fun removed(packageName: String, key: String, reason: Int? = null) = lock.withLock {
        if (tracker.activeKey(packageName) == key) DiagLog.add("테슬라 내비 연동 — 안내 알림 제거 · 사유=${reason ?: -1}")
        tracker.removed(packageName, key)
        if (source == (packageName to key)) invalidate("안내 알림 종료 · 사유=${reason ?: -1}")
    }

    /** 안내 중 경로 미리보기에서 바꾼 목적지로 안내가 이어지면 새 안내처럼 조회·공유한다. */
    suspend fun reroute(packageName: String, destination: String) = lock.withLock {
        if (!enabled()) return@withLock
        val key = tracker.activeKey(packageName) ?: return@withLock
        tracker.reroute(packageName, destination)?.let {
            DiagLog.add("테슬라 내비 연동 — 안내 중 목적지 변경 감지 · $it")
            resolve(it, packageName, key)
        }
    }

    // 경로 거리 기록 (네이버 화면 거리 -> 최근 60초 최댓값을 후보 거리 상한으로 사용)
    /** 이전 안내의 짧은 남은 거리가 새 경로 후보를 지우지 않도록 최근 값 중 가장 긴 거리를 쓴다. */
    fun routeDistance(meters: Int) = synchronized(routeDistances) {
        if (meters !in 1..1_000_000) return@synchronized
        routeDistances.addLast(SystemClock.elapsedRealtime() to meters)
        while (routeDistances.size > 50) routeDistances.removeFirst()
    }

    /** 최근 60초 안에 본 경로 거리가 없으면 거리 조건을 쓰지 않는다. */
    private fun routeBound(): Int? = synchronized(routeDistances) {
        val now = SystemClock.elapsedRealtime()
        routeDistances.filter { now - it.first in 0..60_000 }.maxOfOrNull { it.second }
    }

    /** 진단 기록을 안내 중으로 한정하기 위해 해당 내비의 안내 알림이 살아 있는지 알려준다. */
    suspend fun guiding(packageName: String): Boolean = lock.withLock { tracker.activeKey(packageName) != null }

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

    // 목적지 자동 결정 (조회 -> 경로 거리로 먼 동명 제외 -> 가장 그럴듯한 후보 -> 없으면 지역+이름)
    /** 사용자 결정대로 선택창 없이 한 곳을 정해 공유하고, 테스트 모드는 같은 결과를 보조창에 보여준다. */
    private suspend fun findCandidates(id: Long, query: String, testRequest: Boolean) {
        try {
            val sourcePoint = teslaDestinationPoint(query)
            val near = if (sourcePoint == null) currentPoint() else null
            val found = if (sourcePoint != null) listOf(TeslaDestinationCandidate(query, sourcePoint))
                else rankedTeslaCandidates(matchingTeslaAddressCandidates(query, lookup(query, near)), near)
            val route = routeBound()
            val bounded = withinTeslaRouteDistance(found, near, route)
            val best = bestTeslaCandidate(bounded, near, route)
                ?: fallbackTeslaCandidate(query, near?.let { runCatching { regionOf(it) }.getOrNull() })
            DiagLog.add("테슬라 내비 연동 — 목적지 자동 선택 · 경로=${route ?: -1}m · 후보 ${found.size}→${bounded.size} · " +
                "${best.address}${if (best.point == null) " (이름 검색)" else ""}")
            lock.withLock {
                if (id != revision || !enabled() || !fresh()) return@withLock
                val testMode = testRequest || settingsStore.settings.first().teslaNavigationTestMode
                if (testMode) showPreview(id, query, best)
                else {
                    mutableSelection.value = null
                    share(best.shareText, id)
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

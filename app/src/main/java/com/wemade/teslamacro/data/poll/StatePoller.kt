package com.wemade.teslamacro.data.poll

import com.wemade.teslamacro.data.macro.RuleStore
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.ConditionEvaluator
import com.wemade.teslamacro.domain.macro.GeoPoint
import com.wemade.teslamacro.domain.macro.MacroEngine
import com.wemade.teslamacro.domain.macro.MacroRunner
import com.wemade.teslamacro.domain.macro.Reading
import com.wemade.teslamacro.domain.macro.TimeContext
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 차량 상태를 주기적으로 읽어 매크로 엔진에 먹인다.
 *
 * 폴링 전략이 이 프로젝트의 성패를 가른다.
 * - 평상시: VCSEC(body-controller)만 저빈도로 본다. 인포테인먼트를 안 건드리니 차가 계속 잔다
 * - 사건 발생(문/탑승 변화): 짧은 시간 동안 필요한 카테고리를 집중 폴링한다
 *
 * 항상 전체를 고빈도로 읽으면 차가 잠들지 못해 방전된다.
 */
class StatePoller(
    private val gateway: VehicleGateway,
    private val ruleStore: RuleStore,
    private val settingsStore: SettingsStore,
    private val runner: MacroRunner,
    /** 러너의 조건 대기가 참조하는 최신 상태 */
    private val latestReading: MutableStateFlow<Reading?> = MutableStateFlow(null),
    private val engine: MacroEngine = MacroEngine(),
    private val now: () -> Long = System::currentTimeMillis,
    /** 태블릿 위치. "출발지 근처" 조건을 쓰는 매크로가 있을 때만 호출된다 */
    private val locationReader: suspend () -> GeoPoint? = { null },
) {
    private var job: Job? = null

    private val _snapshot = MutableStateFlow(VehicleSnapshot.Empty)
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val lastFiredAt = mutableMapOf<String, Long>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() = coroutineScope {
        // 재시작 전 탑승 상태를 읽어 둔다. 신선할 때만 믿는다 —
        // 밤새 꺼져 있던 태블릿의 기록은 그 사이 타고 내렸을 수 있어 의미가 없다
        settingsStore.lastPresence()?.let { (present, savedAt) ->
            if (now() - savedAt < PRESENCE_TRUST_MILLIS) {
                presenceBeforeRestart = present
                lastSavedPresence = present
                if (present) {
                    com.wemade.teslable.DiagLog.add(
                        "재시작 전 이미 탑승 중이었음 — 탑승 트리거 헛발동 방지"
                    )
                }
            }
        }

        // 링크가 "살아 있다가" 끊기는 순간 즉시 깨운다 — 안 깨우면 깊은 유휴 잠(최대 120초)을
        // 다 자고서야 끊김을 알아챈다. 시도 실패로 뜨는 Failed(Ready였던 적 없음)에는
        // 반응하지 않아 재연결 백오프를 망가뜨리지 않는다
        launch {
            var last: LinkState? = null
            gateway.linkState.collect { state ->
                if (last is LinkState.Ready && state !is LinkState.Ready) {
                    // 끊긴 순간의 값은 더 이상 사실이 아니다 — 특히 isCharging이 참으로 굳으면
                    // 재연결 순간(전체 읽기 전) 스텔스 충전이 자는 차에 명령을 쏜다
                    _snapshot.update {
                        it.copy(isCharging = null, isUserPresent = null, isLocked = null)
                    }
                    nudge()
                }
                last = state
            }
        }

        var previous: Reading? = null
        var activeUntil = 0L
        var needFullRead = true   // 연결 직후 한 번은 전부 읽어 대시보드를 채운다
        // 명령 후 확인 읽기로 요청된 카테고리 — 확인 창(짧게) 동안만 읽는다.
        // 1회 성공에 바로 끝내면 차량이 명령을 아직 반영하기 전의 정상 응답(옛값)으로
        // 낙관 표시가 되돌아간 채 재확인이 없다. 창(10초, 2초 주기 4~5회)이 흡수한다
        var focusCategories = setOf<StateCategory>()
        var focusUntil = 0L
        // 좀비 GATT 워치독: GATT는 "연결됨"인데 차가 전혀 응답하지 않는 상태(밤샘 BT 절전 후 실차 발생).
        // isConnected로는 못 잡아서, 읽기가 사이클 통째로 연속 전멸하면 강제로 끊고 다시 붙는다
        var failStreak = 0

        while (isActive) {
            // 주기 계산용 사이클 시작 시각 — 읽기 소요(실패 시 8초 타임아웃 포함)를
            // 주기에서 빼지 않으면 30초 설정이 31~49초로 들쭉날쭉해진다 (실차 로그 제보)
            val cycleStart = now()
            val settings = settingsStore.settings.first()

            // 1. 연결이 안 됐으면 붙이고 다시 돈다.
            //    키 등록까지 끝난 차만 — 등록 중인 차를 백그라운드가 건드리면 안 되고,
            //    등록 실패 상태로 집에서 30초마다 스캔을 돌려서도 안 된다
            if (gateway.linkState.value !is LinkState.Ready) {
                needFullRead = true   // 다음 연결 때 다시 전체 읽기
                // 연속 실패 백오프: 차가 없는 곳에서 매 주기 30초짜리 연결 대기를
                // 반복하지 않도록 시도 간격을 0→0→15→30→60초(상한)로 벌린다.
                // nudge(앱 전면 진입)와 연결 성공이 리셋한다
                if (settings.isReady && now() >= reconnectHoldUntil) {
                    // 성패는 connect()의 Result로 판정한다 — linkState는 flatMapLatest를
                    // 거쳐 비동기로 복제되므로 여기서 읽으면 성공이 실패로 집계될 수 있다
                    // 붙었으면 바로 읽으러 간다 — 여기서 한 주기를 자면
                    // 연결 후 30초 동안 화면이 비어 있는다 (실차 로그 2026-08-13 15:37)
                    if (gateway.connect(settings.vin).isSuccess) {
                        reconnectStrikes = 0
                        // Ready가 flatMapLatest를 타고 복제될 때까지 잠깐 기다린다 —
                        // 안 기다리면 루프 상단 검사가 옛 상태를 보고 connect를 또 부른다
                        withTimeoutOrNull(2_000L) {
                            gateway.linkState.first { it is LinkState.Ready }
                        }
                        continue
                    }
                    reconnectStrikes++
                    // 상한 5초 — 차내 상시 전원 태블릿이라 공격적으로 가도 방전 걱정이 없고,
                    // 직행(autoConnect) 시도는 스캔이 없어 라디오 비용도 낮다.
                    // 시도 자체가 30초 대기라 실질 청취 점유율은 30/35 ≈ 86%
                    val holdSeconds = if (reconnectStrikes <= 1) 0 else 5
                    reconnectHoldUntil = now() + holdSeconds * 1000L
                    // 시도마다 적으면 차에서 떨어져 있는 내내 버퍼를 밀어낸다 —
                    // 백오프 진입 시 1회 + 100회마다 생존 신호만 남긴다
                    if (reconnectStrikes == 2 || reconnectStrikes % 100 == 0) {
                        com.wemade.teslable.DiagLog.add(
                            "재연결 대기 중 — 연속 ${reconnectStrikes}회 실패, ${holdSeconds}s 간격 재시도"
                        )
                    }
                }
                // 등록 전엔 느긋하게. 재연결 대기 중엔 백오프가 끝나는 시점까지만 잔다 —
                // idle 주기(30초)를 통째로 자면 백오프 0초여도 30초 귀머거리가 된다
                val sleepMs =
                    if (settings.isReady) (reconnectHoldUntil - now()).coerceAtLeast(1_000L)
                    else settings.idlePollSeconds * 1000L
                sleep(sleepMs)
                continue
            }
            reconnectStrikes = 0

            // 2. 카테고리 선택:
            //    - 연결 직후: 전부 (온도·배터리까지 화면에 한 번 채운다)
            //    - 집중 폴링 창: 룰이 요구하는 것
            //    - 사람이 타고 있음: 공조·배터리도 갱신 — 타고 있는 동안 차는 어차피 안 잔다.
            //      (접속 시 한 번만 읽으면 주행 중 배터리가 화면에서 멈춰 있는다 — 실차 제보)
            //    - 평상시(빈 차): VCSEC만 (차를 재우기 위해)
            // 2-0. 명령 직후 확인 읽기 — 요청된 카테고리를 집중 창에 태우고 창을 연다.
            //      안 하면 명령 결과가 다음 정기 주기(최대 120초)까지 화면에 안 나타난다
            val focusRequested = pendingFocus.getAndSet(emptySet())
            if (focusRequested.isNotEmpty()) {
                focusCategories = focusCategories + focusRequested
                focusUntil = now() + FOCUS_CONFIRM_MS
                // 집중 창은 확인 창 길이만큼만 — 명령 한 번에 3~5분짜리 고빈도 창이 열리면
                // FOCUS_CONFIRM_MS로 아끼려던 인포테인먼트 비용이 그대로 나간다
                activeUntil = maxOf(activeUntil, focusUntil)
            }
            val isActiveWindow = now() < activeUntil
            if (now() >= focusUntil) focusCategories = setOf()
            val categories = when {
                needFullRead -> setOf(
                    StateCategory.BODY_CONTROLLER,
                    StateCategory.CLIMATE,
                    StateCategory.CHARGE,
                    StateCategory.DRIVE,
                )
                // 집중 창에도 차체는 항상 본다(탑승·잠금 변화 감지가 멈추면 안 된다).
                // 타고 있으면 공조·배터리도 — 매크로가 안 써도 화면이 멈춰 보이면 안 된다
                isActiveWindow -> buildSet {
                    add(StateCategory.BODY_CONTROLLER)
                    addAll(requiredCategories())
                    addAll(focusCategories)
                    if (_snapshot.value.isUserPresent == true) {
                        add(StateCategory.CLIMATE)
                        add(StateCategory.CHARGE)
                    }
                }
                _snapshot.value.isUserPresent == true -> setOf(
                    StateCategory.BODY_CONTROLLER,
                    StateCategory.CLIMATE,
                    StateCategory.CHARGE,
                )
                // 빈 차라도 충전 중이면 CHARGE는 계속 본다 — 안 보면 isCharging이 참으로 동결돼
                // 충전이 끝나도 스텔스가 밤새 자는 차에 명령을 쏘고, 깊은 유휴도 영영 못 든다.
                // 충전 중엔 차가 어차피 깨어 있어 추가 비용이 없다
                _snapshot.value.isCharging == true -> setOf(
                    StateCategory.BODY_CONTROLLER,
                    StateCategory.CHARGE,
                ) + requiredCategories()
                // 켜진 룰이 요구하는 카테고리는 빈 차에서도 읽는다 — "주차 과열 보호"처럼
                // 빈 차가 본령인 룰이 하차 시점 값(몇 시간 전 46℃)으로 판정되는 걸 막는다.
                // 인포테인먼트 읽기가 차 수면을 방해하는 비용은 그 룰을 켠 사용자의 선택이다
                else -> setOf(StateCategory.BODY_CONTROLLER) + requiredCategories()
            }
            needFullRead = false

            // 3. 카테고리는 하나씩 읽어 병합한다 (한 번에 여러 개는 응답 크기 초과)
            val results = categories.map { it to gateway.read(it) }
            val merged = results.fold(_snapshot.value) { acc, (category, result) ->
                result.getOrNull()?.let { merge(acc, category, it) } ?: acc
            }.let { withRideMinutes(it) }
            _snapshot.value = merged

            // 3-1. 좀비 GATT 워치독 — 한 사이클이 통째로 실패하는 게 이어지면 강제 재접속.
            //      하나라도 성공했으면 링크는 산 것이다 (빈 차 사이클도 VCSEC는 항상 응답해야 정상)
            if (results.isNotEmpty() && results.all { it.second.isFailure }) {
                failStreak++
                if (failStreak >= FAIL_STREAK_LIMIT) {
                    com.wemade.teslable.DiagLog.add(
                        "읽기 연속 전멸 ${failStreak}회 — 좀비 연결 의심, 강제 재연결"
                    )
                    gateway.disconnect()
                    failStreak = 0
                }
            } else {
                failStreak = 0
            }

            // 4. 사건이 보이면 집중 폴링 창을 연다.
            //    위치 캐시도 버린다 — 탑승 직전 실패(null)가 캐시에 남아 있으면
            //    "출발지 근처" 조건이 탑승 순간(1회성 엣지)에 오판되어 매크로가 영영 안 터진다
            if (isWakeEvent(previous?.snapshot, merged)) {
                activeUntil = now() + settings.activeWindowSeconds * 1000L
                locationCachedAt = 0L
            }

            // 5. 매크로 판정 + 실행.
            //    GPS는 위치 조건이 실제로 걸려 있을 때만 읽는다 — 매 폴링마다 켜면 배터리를 먹는다
            val location = if (needsLocation()) cachedLocation() else null
            val current = Reading(merged, TimeContext.of(now()), location)
            latestReading.value = current

            // 재시작해도 "직전 값"을 알 수 있게 남긴다. 바뀔 때만 쓴다 —
            // 15초마다 DataStore를 두드릴 이유가 없다
            val presenceNow = merged.isUserPresent
            if (presenceNow != null && presenceNow != lastSavedPresence) {
                lastSavedPresence = presenceNow
                settingsStore.savePresence(presenceNow)
            }

            if (settings.automationEnabled) {
                engine.evaluate(
                    rules = ruleStore.rules.value,
                    previous = previous,
                    current = current,
                    lastFiredAtMillis = lastFiredAt,
                    knownPresenceBeforeRestart = presenceBeforeRestart,
                    // 트리거는 발동했는데 조건이 막았으면 무엇이 막았는지 남긴다
                    onBlocked = { rule, unmet ->
                        com.wemade.teslable.DiagLog.add(
                            "매크로 [${rule.name}] 보류 — " +
                                unmet.joinToString(", ") {
                                    describe(it) + blockedDetail(it, current)
                                } + " 불충족"
                        )
                    },
                ).forEach { rule ->
                    lastFiredAt[rule.id] = current.time.epochMillis
                    runner.launch(rule, current.time.epochMillis)
                }
            }

            previous = current
            // 이번 사이클에 사건이 감지돼 창이 열렸으면 바로 짧은 주기로 — 낡은 판정을 쓰면
            // 문 열림 직후 한 사이클(기본 30초)을 통째로 기다리게 된다
            val interval = nextIntervalSeconds(
                inActiveWindow = now() < activeUntil,
                snapshot = merged,
                activeSeconds = settings.activePollSeconds,
                idleSeconds = settings.idlePollSeconds,
            )
            // 읽기에 쓴 시간을 빼서 주기를 일정하게 유지한다. 밑바닥 1초는 폭주 방지.
            // 단 실패가 낀 사이클은 경과를 빼지 않는다 — 타임아웃(8초×N)이 주기를 넘으면
            // 하한 1초로 떨어져 "느린 차일수록 쉼 없이 재시도"가 된다
            val elapsed = if (results.any { it.second.isFailure }) 0L else now() - cycleStart
            sleep((interval * 1000L - elapsed).coerceAtLeast(1_000L))
        }
    }

    // ---- 폴러 깨우기 ----
    // 깊은 유휴(120초) 중에 사용자가 타면 다음 주기까지 화면이 낡아 보인다.
    // 앱이 전면에 오는 순간(대개 탑승) nudge()로 잠을 끊고 즉시 한 사이클 돈다
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    // ---- 재연결 백오프 상태 ----
    // 필드인 이유: nudge()가 백오프를 풀어야 한다 (사용자가 앱을 열었으면 즉시 붙어봐야 한다)
    private var reconnectStrikes = 0

    @Volatile
    private var reconnectHoldUntil = 0L

    /** 자고 있는 폴러를 지금 깨운다. 돌고 있는 중이면 다음 잠만 짧아질 뿐 부작용 없다 */
    fun nudge() {
        reconnectHoldUntil = 0L   // 사용자가 왔다 — 백오프 무시하고 즉시 시도
        // 스트라이크도 리셋 — 안 하면 깨운 뒤 첫 실패가 곧장 30초 무음으로 돌아간다
        reconnectStrikes = 0
        nudges.trySend(Unit)
    }

    // ---- 명령 후 확인 읽기 요청 ----
    // AtomicReference인 이유: UI 스레드의 추가 요청과 폴러의 비우기(getAndSet)가
    // 경합해도 요청이 유실되지 않는다
    private val pendingFocus =
        java.util.concurrent.atomic.AtomicReference<Set<StateCategory>>(emptySet())

    /** 수동 실행(목록·음성)도 쿨다운에 기록한다 — 방금 돈 매크로가 트리거로 곧장 또 돌지 않게 */
    fun recordFired(ruleId: String) {
        lastFiredAt[ruleId] = now()
    }

    /** 명령 성공 직후 호출 — 해당 카테고리를 집중 폴링에 태우고 폴러를 즉시 깨운다 */
    fun focusOn(category: StateCategory) {
        pendingFocus.updateAndGet { it + category }
        nudge()
    }

    /** delay 대신 쓰는 잠 — nudge가 오면 즉시 깬다 */
    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms) { nudges.receive() }
    }

    // 앱이 주행 중에 재시작되면 그 시점부터 다시 세므로 실제보다 짧게 나올 수 있다 (감수)
    private val rideMeter = RideSessionMeter(now)

    /** 탑승 시작~지금까지, 하차 후엔 직전 세션 길이를 스냅샷에 싣는다 */
    private fun withRideMinutes(snapshot: VehicleSnapshot): VehicleSnapshot =
        snapshot.copy(rideMinutes = rideMeter.update(snapshot.isUserPresent))

    // ---- 위치 캐시 ----
    // 측위 실패가 8초를 먹는다. 매 사이클 부르면 집중 폴링(2초)이 10초 주기가 돼버려
    // 성공이든 실패든 60초 동안은 같은 답을 다시 쓴다
    private var locationCache: GeoPoint? = null
    private var locationCachedAt = 0L

    /** 마지막으로 DataStore에 쓴 탑승 상태. 같은 값을 되풀이해 쓰지 않기 위한 것 */
    private var lastSavedPresence: Boolean? = null

    /**
     * 재시작 전에 마지막으로 본 탑승 상태. 신선할 때만 채운다.
     *
     * 오래된 값(밤새 꺼져 있던 태블릿)은 의미가 없다 — 그 사이 타고 내렸을 수 있으니
     * null로 두어 예전처럼 1회 발동에 맡긴다.
     */
    private var presenceBeforeRestart: Boolean? = null

    private companion object {
        /**
         * 재시작 전 탑승 기록을 믿는 시간. 업데이트 설치·프로세스 재시작은 초 단위로 끝나므로
         * 넉넉히 5분이면 충분하고, 그보다 오래된 기록은 그 사이 타고 내렸을 수 있다
         */
        const val PRESENCE_TRUST_MILLIS = 5 * 60 * 1000L

        const val LOCATION_TTL_MS = 60_000L

        /** 이 횟수만큼 사이클 전멸이 이어지면 좀비 연결로 보고 끊는다 */
        const val FAIL_STREAK_LIMIT = 3

        /**
         * 명령 후 확인 읽기 창. 집중 창(기본 300초) 내내 인포테인먼트를 2초마다
         * 읽으면 차량 수면·배터리에 부담이라, 확인은 이 짧은 창으로 끝낸다
         */
        const val FOCUS_CONFIRM_MS = 10_000L
    }

    private suspend fun cachedLocation(): GeoPoint? {
        if (now() - locationCachedAt < LOCATION_TTL_MS) return locationCache
        locationCache = locationReader()
        locationCachedAt = now()
        // 위치 조건이 필요한 순간의 측위 실패는 매크로 오판 원인 1순위 — 흔적을 남긴다
        if (locationCache == null) {
            com.wemade.teslable.DiagLog.add("위치 읽기 실패 — 위치 조건은 불충족으로 처리")
        }
        return locationCache
    }

    // 위치 조건이 막았을 땐 "얼마나 벗어났는지"까지 남긴다 — 반경 조정 판단의 근거가 된다.
    // 좌표 원문은 남기지 않는다 (진단 로그는 공유되는 개인정보 통로다)
    private fun blockedDetail(condition: Condition, reading: Reading): String {
        if (condition !is Condition.NearLocation) return ""
        val here = reading.location ?: return " (위치 정보 없음)"
        val lat = condition.latitude ?: return " (저장 위치 없음)"
        val lng = condition.longitude ?: return " (저장 위치 없음)"
        val meters = ConditionEvaluator.distanceMeters(here.latitude, here.longitude, lat, lng)
        return " (거리 ${meters.toInt()}m / 반경 ${condition.radiusMeters}m)"
    }

    /** 켜져 있는 매크로 중 위치 조건(조건 또는 조건 대기)을 쓰는 게 하나라도 있는지 */
    private fun needsLocation(): Boolean = ruleStore.rules.value.any { rule ->
        rule.enabled && (
            rule.conditions.any { it is Condition.NearLocation } ||
                rule.actions.any {
                    it is ActionStep.WaitUntil && it.condition is Condition.NearLocation
                }
            )
    }

    /** 켜져 있는 매크로가 실제로 필요로 하는 카테고리만 읽는다 */
    private fun requiredCategories(): Set<StateCategory> =
        ruleStore.rules.value
            .filter { it.enabled }
            .flatMap { it.requiredCategories }
            .toSet()
            .ifEmpty { setOf(StateCategory.BODY_CONTROLLER) }

    /** 문 열림·탑승 변화 = 사람이 차에 접근했다는 신호 */
    private fun isWakeEvent(previous: VehicleSnapshot?, current: VehicleSnapshot): Boolean {
        if (previous == null) return false
        return previous.doorOpen != current.doorOpen ||
            previous.isUserPresent != current.isUserPresent ||
            previous.isLocked != current.isLocked
    }

    /** 카테고리별 부분 응답을 누적 스냅샷에 덮어쓴다. null인 필드는 기존 값을 지키다 */
    private fun merge(
        base: VehicleSnapshot,
        category: StateCategory,
        incoming: VehicleSnapshot,
    ) = base.copy(
        timestampMillis = incoming.timestampMillis,
        categoryReadAt = base.categoryReadAt + (category to incoming.timestampMillis),
        insideTempC = incoming.insideTempC ?: base.insideTempC,
        outsideTempC = incoming.outsideTempC ?: base.outsideTempC,
        driverTempSettingC = incoming.driverTempSettingC ?: base.driverTempSettingC,
        isClimateOn = incoming.isClimateOn ?: base.isClimateOn,
        isPreconditioning = incoming.isPreconditioning ?: base.isPreconditioning,
        // 탑승·잠금은 VCSEC 소유 필드 — VCSEC 읽기가 성공했는데 null(UNKNOWN)이면
        // "모름"이 진실이다. 기존 값을 유지하면 true가 동결돼 깊은 유휴에 영영 못 들고
        // 빈 차의 인포테인먼트를 계속 깨운다 (isCharging에만 있던 가드를 확장)
        isUserPresent = if (ownsPresence(category)) incoming.isUserPresent else base.isUserPresent,
        isLocked = if (ownsPresence(category)) incoming.isLocked else base.isLocked,
        shiftState = if (incoming.shiftState != ShiftState.UNKNOWN) incoming.shiftState
        else base.shiftState,
        doorOpen = base.doorOpen + incoming.doorOpen,
        seatHeater = base.seatHeater + incoming.seatHeater,
        seatCooler = base.seatCooler + incoming.seatCooler,
        batteryLevelPercent = incoming.batteryLevelPercent ?: base.batteryLevelPercent,
        isCharging = incoming.isCharging ?: base.isCharging,
        chargeLimitPercent = incoming.chargeLimitPercent ?: base.chargeLimitPercent,
        chargingAmps = incoming.chargingAmps ?: base.chargingAmps,
        rangeKm = incoming.rangeKm ?: base.rangeKm,
        isChargePortOpen = incoming.isChargePortOpen ?: base.isChargePortOpen,
        speedKph = incoming.speedKph ?: base.speedKph,
    )
}

/** 탑승·잠금 필드를 보고하는 카테고리인가 (VCSEC 상태 응답 계열) */
private fun ownsPresence(category: StateCategory): Boolean =
    category == StateCategory.BODY_CONTROLLER || category == StateCategory.CLOSURES

/** 깊은 유휴 주기. 사용자가 평상시를 이보다 길게 잡았으면 그 값을 따른다 */
internal const val DEEP_IDLE_SECONDS = 120

/**
 * 다음 폴링까지 몇 초 쉴지 정한다 — 순수 함수라 단위 테스트로 검증한다.
 *
 * 우선순위: 집중 창 > 깊은 유휴 > 평상시.
 * 깊은 유휴 = 잠기고 · 비었고 · 충전도 아님 — 차를 재우기 가장 좋은 상태라 더 아껴 읽는다.
 * 충전 중은 예외다: 차가 어차피 안 자고, 스텔스 충전·배터리 감시가 신선한 값을 원한다
 */
internal fun nextIntervalSeconds(
    inActiveWindow: Boolean,
    snapshot: VehicleSnapshot,
    activeSeconds: Int,
    idleSeconds: Int,
): Int = when {
    inActiveWindow -> activeSeconds
    snapshot.isLocked == true &&
        snapshot.isUserPresent != true &&
        snapshot.isCharging != true -> maxOf(DEEP_IDLE_SECONDS, idleSeconds)
    else -> idleSeconds
}

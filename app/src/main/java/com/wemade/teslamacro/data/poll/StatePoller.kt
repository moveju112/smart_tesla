package com.wemade.teslamacro.data.poll

import com.wemade.teslamacro.data.macro.RuleStore
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.GeoPoint
import com.wemade.teslamacro.domain.macro.MacroEngine
import com.wemade.teslamacro.domain.macro.MacroRunner
import com.wemade.teslamacro.domain.macro.Reading
import com.wemade.teslamacro.domain.macro.TimeContext
import com.wemade.teslamacro.domain.model.ShiftState
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
        var previous: Reading? = null
        var activeUntil = 0L
        var needFullRead = true   // 연결 직후 한 번은 전부 읽어 대시보드를 채운다

        while (isActive) {
            val settings = settingsStore.settings.first()

            // 1. 연결이 안 됐으면 붙이고 다시 돈다.
            //    키 등록까지 끝난 차만 — 등록 중인 차를 백그라운드가 건드리면 안 되고,
            //    등록 실패 상태로 집에서 30초마다 스캔을 돌려서도 안 된다
            if (gateway.linkState.value !is LinkState.Ready) {
                if (settings.isReady) gateway.connect(settings.vin)
                needFullRead = true   // 다음 연결 때 다시 전체 읽기
                delay(settings.idlePollSeconds * 1000L)
                continue
            }

            // 2. 카테고리 선택:
            //    - 연결 직후: 전부 (온도·배터리까지 화면에 한 번 채운다)
            //    - 집중 폴링 창: 룰이 요구하는 것
            //    - 사람이 타고 있음: 공조·배터리도 갱신 — 타고 있는 동안 차는 어차피 안 잔다.
            //      (접속 시 한 번만 읽으면 주행 중 배터리가 화면에서 멈춰 있는다 — 실차 제보)
            //    - 평상시(빈 차): VCSEC만 (차를 재우기 위해)
            val isActiveWindow = now() < activeUntil
            val categories = when {
                needFullRead -> setOf(
                    StateCategory.BODY_CONTROLLER,
                    StateCategory.CLIMATE,
                    StateCategory.CHARGE,
                    StateCategory.DRIVE,
                )
                isActiveWindow -> requiredCategories()
                _snapshot.value.isUserPresent == true -> setOf(
                    StateCategory.BODY_CONTROLLER,
                    StateCategory.CLIMATE,
                    StateCategory.CHARGE,
                )
                else -> setOf(StateCategory.BODY_CONTROLLER)
            }
            needFullRead = false

            // 3. 카테고리는 하나씩 읽어 병합한다 (한 번에 여러 개는 응답 크기 초과)
            val merged = categories.fold(_snapshot.value) { acc, category ->
                gateway.read(category).getOrNull()?.let { merge(acc, it) } ?: acc
            }.let { withRideMinutes(it) }
            _snapshot.value = merged

            // 4. 사건이 보이면 집중 폴링 창을 연다
            if (isWakeEvent(previous?.snapshot, merged)) {
                activeUntil = now() + settings.activeWindowSeconds * 1000L
            }

            // 5. 매크로 판정 + 실행.
            //    GPS는 위치 조건이 실제로 걸려 있을 때만 읽는다 — 매 폴링마다 켜면 배터리를 먹는다
            val location = if (needsLocation()) cachedLocation() else null
            val current = Reading(merged, TimeContext.of(now()), location)
            latestReading.value = current

            if (settings.automationEnabled) {
                engine.evaluate(ruleStore.rules.value, previous, current, lastFiredAt)
                    .forEach { rule ->
                        lastFiredAt[rule.id] = current.time.epochMillis
                        runner.launch(rule, current.time.epochMillis)
                    }
            }

            previous = current
            // 이번 사이클에 사건이 감지돼 창이 열렸으면 바로 짧은 주기로 — 낡은 판정을 쓰면
            // 문 열림 직후 한 사이클(기본 30초)을 통째로 기다리게 된다
            val interval =
                if (now() < activeUntil) settings.activePollSeconds else settings.idlePollSeconds
            delay(interval * 1000L)
        }
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

    private companion object {
        const val LOCATION_TTL_MS = 60_000L
    }

    private suspend fun cachedLocation(): GeoPoint? {
        if (now() - locationCachedAt < LOCATION_TTL_MS) return locationCache
        locationCache = locationReader()
        locationCachedAt = now()
        return locationCache
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
    private fun merge(base: VehicleSnapshot, incoming: VehicleSnapshot) = base.copy(
        timestampMillis = incoming.timestampMillis,
        insideTempC = incoming.insideTempC ?: base.insideTempC,
        outsideTempC = incoming.outsideTempC ?: base.outsideTempC,
        driverTempSettingC = incoming.driverTempSettingC ?: base.driverTempSettingC,
        isClimateOn = incoming.isClimateOn ?: base.isClimateOn,
        isPreconditioning = incoming.isPreconditioning ?: base.isPreconditioning,
        isUserPresent = incoming.isUserPresent ?: base.isUserPresent,
        isLocked = incoming.isLocked ?: base.isLocked,
        shiftState = if (incoming.shiftState != ShiftState.UNKNOWN) incoming.shiftState
        else base.shiftState,
        doorOpen = base.doorOpen + incoming.doorOpen,
        seatHeater = base.seatHeater + incoming.seatHeater,
        seatCooler = base.seatCooler + incoming.seatCooler,
        batteryLevelPercent = incoming.batteryLevelPercent ?: base.batteryLevelPercent,
        isCharging = incoming.isCharging ?: base.isCharging,
        chargeLimitPercent = incoming.chargeLimitPercent ?: base.chargeLimitPercent,
        rangeKm = incoming.rangeKm ?: base.rangeKm,
        isChargePortOpen = incoming.isChargePortOpen ?: base.isChargePortOpen,
        speedKph = incoming.speedKph ?: base.speedKph,
    )
}

package com.wemade.teslamacro.di

import android.content.Context
import com.wemade.teslamacro.data.gateway.BleVehicleGateway
import com.wemade.teslamacro.data.gateway.SimulatedVehicleGateway
import com.wemade.teslamacro.data.gateway.SwitchingVehicleGateway
import com.wemade.teslamacro.data.macro.RuleStore
import com.wemade.teslamacro.data.poll.StatePoller
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.macro.MacroRunner
import com.wemade.teslamacro.domain.macro.Reading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers

/**
 * 의존성 그래프. 객체가 스무 개도 안 되는 규모라 DI 프레임워크를 쓰지 않는다.
 * 모듈이 늘어 그래프가 손으로 못 볼 정도가 되면 그때 Hilt로 옮긴다.
 */
class AppContainer(private val context: Context) {

    /** 앱 생애주기 스코프. 서비스가 죽어도 상태는 유지된다 */
    val appScope = CoroutineScope(SupervisorJob()) + Dispatchers.Default

    val settingsStore = SettingsStore(context)
    val seatStore = com.wemade.teslamacro.data.settings.SeatStore(context)

    /** 하이브리드 정밀 인식용 — 호출어 감지 후 문장을 받는 내장(구글) 인식기 */
    val voiceRecognizer = com.wemade.teslamacro.data.voice.VoiceRecognizer(context)

    /** 매크로의 "지도 안내" 걸음을 처리한다 */
    val navigator = com.wemade.teslamacro.data.nav.NaverNavigator(context)

    /** 매크로의 "출발지 근처" 조건용 태블릿 위치 */
    val tabletLocation = com.wemade.teslamacro.data.location.TabletLocation(context)

    /** 상시 대기용. 기기 안에서만 도는 오프라인 인식 */
    val voiceModelStore = com.wemade.teslamacro.data.voice.VoiceModelStore(context)
    val hotwordListener = com.wemade.teslamacro.data.voice.HotwordListener(voiceModelStore)

    /** 등록 화면 진단용 스캐너. 게이트웨이와 별개로 주변을 그냥 훑는다 */
    val scanner = com.wemade.teslable.TeslaBleScanner(context)

    val ruleStore = RuleStore(context)

    /** 폴러가 쓰고 러너가 읽는다. 조건 대기가 최신 상태를 봐야 해서 공유한다 */
    private val latestReading = MutableStateFlow<Reading?>(null)

    lateinit var gateway: SwitchingVehicleGateway
        private set
    lateinit var runner: MacroRunner
        private set
    lateinit var poller: StatePoller
        private set
    lateinit var stealthCharge: com.wemade.teslamacro.data.charge.StealthChargeController
        private set

    /** VIN 등록 여부에 따라 실차/시뮬레이터를 고른다 */
    suspend fun initialize() {
        ruleStore.load()

        val settings = settingsStore.settings.first()
        // 차를 등록했으면 실차, 아니면 시뮬레이터로 시작한다.
        // 등록 도중에 실차로 갈아끼울 수 있게 껍데기를 씌운다
        gateway = SwitchingVehicleGateway(
            initial = if (settings.isPaired) BleVehicleGateway(context, settingsStore, appScope)
            else SimulatedVehicleGateway(),
            scope = appScope,
        )

        runner = MacroRunner(gateway, appScope, latestReading, navigator::navigate)
        poller = StatePoller(
            gateway, ruleStore, settingsStore, runner, latestReading,
            locationReader = ::readLocationWithFallback,
        )
        stealthCharge = com.wemade.teslamacro.data.charge.StealthChargeController(
            gateway, poller, settingsStore,
        )
    }

    val isSimulated: Boolean get() = gateway.current is SimulatedVehicleGateway

    /**
     * 측위 성공 좌표는 저장하고, 실패하면 마지막 성공 좌표로 대체한다.
     * 태블릿은 차에 상주하므로 마지막 좌표가 곧 차의 위치다 —
     * 탑승 순간(1회성 트리거)의 GPS 콜드스타트 실패로 위치 매크로가 통째로 빠지는 걸 막는다.
     */
    private suspend fun readLocationWithFallback(): com.wemade.teslamacro.domain.macro.GeoPoint? {
        val fresh = tabletLocation.read()
        if (fresh != null) {
            settingsStore.saveLastGeo(fresh.latitude, fresh.longitude)
            return fresh
        }
        val saved = settingsStore.lastGeo() ?: return null
        val ageMinutes = (System.currentTimeMillis() - saved.second) / 60_000L
        com.wemade.teslable.DiagLog.add("측위 실패 — 저장된 마지막 위치로 대체 (${ageMinutes}분 전)")
        return saved.first
    }

    /**
     * VIN이 들어왔으니 실차로 갈아끼운다.
     * 이걸 안 하면 시뮬레이터가 가짜로 "연결 성공"을 돌려줘 등록이 그냥 통과해버린다.
     */
    suspend fun useRealVehicle() {
        if (gateway.current is BleVehicleGateway) return
        gateway.switchTo(BleVehicleGateway(context, settingsStore, appScope))
    }
}

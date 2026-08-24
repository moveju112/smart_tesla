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

    /** ViewModel에서 파일 저장·인텐트 발사가 필요할 때 쓰는 앱 컨텍스트 */
    val appContext: Context = context.applicationContext

    init {
        // 진단 로그를 파일에도 남긴다. 이 앱이 사는 곳은 차내 태블릿이라
        // adb를 붙일 수 없고, 메모리에만 두면 앱이 재시작하는 순간
        // 정작 알고 싶은 "죽기 직전"이 통째로 사라진다
        val logDir = java.io.File(appContext.filesDir, "diag")
        com.wemade.teslable.DiagLog.attachFile(
            logFile = java.io.File(logDir, "diag.log"),
            previousFile = java.io.File(logDir, "diag-prev.log"),
        )
    }

    val settingsStore = SettingsStore(context)
    val seatStore = com.wemade.teslamacro.data.settings.SeatStore(context)

    /** 하이브리드 정밀 인식용 — 호출어 감지 후 문장을 받는 내장(구글) 인식기 */
    val voiceRecognizer = com.wemade.teslamacro.data.voice.VoiceRecognizer(context)

    /** 매크로의 "지도 안내" 걸음을 처리한다 */
    val navigator = com.wemade.teslamacro.data.nav.NaverNavigator(context)

    /** HUD 속도. 차량 폴링보다 빠르고 차를 안 깨운다 */
    val speedMeter = com.wemade.teslamacro.data.location.SpeedMeter(context)

    /**
     * 위치 권한이 방금 바뀌었다는 신호.
     *
     * GPS 스트림은 **구독하는 순간** 권한을 본다 — 권한이 없으면 그 자리에서 닫히고,
     * 나중에 사용자가 허용해도 아무도 다시 구독하지 않는다. 설정값은 그대로라
     * 설정 흐름도 다시 흐르지 않는다. 그래서 "허용했다"를 흘려보낼 통로를 하나 둔다.
     */
    val locationPermissionRevision = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 사용자가 위치 권한 화면에서 돌아왔을 때 부른다. 값이 뭐든 바뀌기만 하면 된다 */
    fun notifyLocationPermissionChanged() {
        locationPermissionRevision.value += 1
    }

    /**
     * 과속·구간단속·보호구역 안내. 앱 키는 local.properties에서 BuildConfig로 온다 —
     * 키가 비어 있으면 스스로 비활성으로 남는다
     */
    val safeDrive = com.wemade.teslamacro.data.safety.SafeDriveGuide(
        context.applicationContext as android.app.Application,
        com.wemade.teslamacro.BuildConfig.KAKAO_NATIVE_APP_KEY,
    )

    /** 매크로의 "출발지 근처" 조건용 태블릿 위치 */
    val tabletLocation = com.wemade.teslamacro.data.location.TabletLocation(context)

    /** 상시 대기용. 기기 안에서만 도는 오프라인 인식 */
    val voiceModelStore = com.wemade.teslamacro.data.voice.VoiceModelStore(context)
    val hotwordListener = com.wemade.teslamacro.data.voice.HotwordListener(voiceModelStore)

    /** 등록 화면 진단용 스캐너. 게이트웨이와 별개로 주변을 그냥 훑는다 */
    val scanner = com.wemade.teslable.TeslaBleScanner(context)

    val ruleStore = RuleStore(context)

    /** 예보. 계정도 키도 없는 Open-Meteo를 쓴다 */
    val weatherClient = com.wemade.teslamacro.data.weather.OpenMeteoClient()

    /**
     * 초기화 때 읽어 둔 설정.
     *
     * ViewModel이 DataStore를 새로 구독하면 첫 값은 빈 기본값(vin="")이 되고,
     * 그 한 프레임 때문에 등록이 끝난 차에서도 VIN 입력 화면이 스친다 —
     * 느린 태블릿에선 그게 눈에 보인다. 이미 읽어 둔 값을 첫 값으로 쓰게 넘겨준다.
     */
    var initialSettings: com.wemade.teslamacro.data.settings.AppSettings =
        com.wemade.teslamacro.data.settings.AppSettings()
        private set

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
        initialSettings = settings
        // 차를 등록했으면 실차, 아니면 시뮬레이터로 시작한다.
        // 등록 도중에 실차로 갈아끼울 수 있게 껍데기를 씌운다
        gateway = SwitchingVehicleGateway(
            initial = if (settings.isPaired) BleVehicleGateway(context, settingsStore, appScope)
            else SimulatedVehicleGateway(),
            scope = appScope,
        )

        // 어느 내비로 보낼지는 실행 순간의 설정을 따른다 — 컨테이너 조립 시점에 굳히면
        // 사용자가 설정을 바꿔도 재시작 전까지 옛 앱으로 나간다
        runner = MacroRunner(
            gateway, appScope, latestReading,
            navigator = { name, address ->
                val chosen = com.wemade.teslamacro.data.nav.NavigatorApp.of(
                    settingsStore.settings.first().navigatorApp
                )
                navigator.navigate(name, address, chosen)
            },
        )
        poller = StatePoller(
            gateway, ruleStore, settingsStore, runner, latestReading,
            locationReader = ::readLocationWithFallback,
            forecastReader = weatherClient::forecast,
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

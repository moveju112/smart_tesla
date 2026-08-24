package com.wemade.teslamacro.data.safety

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafetyCode
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.wemade.teslable.DiagLog
import com.wemade.teslamacro.domain.safety.SafetyAlert
import com.wemade.teslamacro.domain.safety.SafetyKind
import com.wemade.teslamacro.domain.safety.SafetyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 과속·구간단속·보호구역 안내. 카카오내비 SDK(KNSDK)를 감싼다.
 *
 * **경로 없이 돈다** — `startWithoutTrip()`이 목적지 없는 안전운전 전용 모드다.
 * 우리는 길안내를 외부 내비 앱에 넘기므로, 여기서 필요한 건 안내가 아니라 경고뿐이다.
 *
 * 앱 키가 없으면 아무것도 안 한다. 그때 [state]의 `ready`가 false로 남아
 * 화면이 "안내 못 하는 중"임을 드러낸다 — 침묵으로 감추면 운전자가 안내를 믿어버린다.
 */
class SafeDriveGuide(
    private val application: Application,
    private val appKey: String,
) {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    private var installed = false
    @Volatile private var running = false

    /**
     * 설정이 "켜짐"인가. `running`과 따로 두는 이유 —
     * 초기화는 망을 타서 수백 ms가 걸리고, 그 사이에 사용자가 설정을 끄면
     * `running`이 아직 false라 `stop()`이 물러나고, 뒤늦게 도착한 콜백이
     * **꺼진 기능을 시작한다.** 그래서 "하고 싶은가"와 "돌고 있는가"를 가른다.
     */
    private var wanted = false

    /** 초기화가 진행 중인가. `running`은 아직 false라 재진입을 못 막는다 */
    private var initializing = false

    /**
     * 켜짐 회차 번호. `stop()`이 오를 때마다 1 늘어난다.
     *
     * SDK 초기화와 측위 타임아웃은 **되돌릴 수 없는 비동기 요청**이다 —
     * 요청을 취소할 방법이 없으니, 늦게 돌아온 콜백이 자기 회차를 확인하고
     * 스스로 물러나게 한다. 이게 없으면 권한 변경의 stop→start 사이에
     * 옛 콜백이 새 회차의 안내를 두 번 세우거나, 옛 60초 타이머가
     * 갓 시작한 안내에 "위치 없음" 적색을 거짓으로 씌운다.
     */
    @Volatile private var generation = 0

    val hasKey: Boolean get() = appKey.isNotBlank()

    /**
     * 소리 설정. SDK가 "이 음성을 재생할까?"를 묻는 자리는 콜백 안이라
     * Flow를 구독해 기다릴 시간이 없다 — 서비스가 값을 미리 밀어 넣어 둔다.
     * SDK 스레드에서 읽히므로 @Volatile이 필요하다.
     */
    @Volatile private var soundEnabled = true

    @Volatile private var volumeLevel = 2

    /** 설정이 바뀔 때마다 서비스가 부른다. 안내가 도는 중에도 즉시 반영된다 */
    fun setSound(enabled: Boolean, level: Int) {
        soundEnabled = enabled
        volumeLevel = level.coerceIn(1, 3)
    }

    /**
     * SDK를 깨우고 안전운전 안내를 시작한다.
     *
     * 설치·초기화는 처음 한 번만 한다. 초기화는 망을 타므로 실패할 수 있고,
     * 실패하면 조용히 물러난다 — 안내가 없는 건 불편이지만, 여기서 앱이 죽으면 재앙이다.
     */
    fun start() {
        if (!hasKey) {
            DiagLog.add("안전운전 안내 · 카카오내비 앱 키가 없어 시작하지 않음")
            return
        }
        wanted = true
        // 초기화는 망을 타서 수백 ms가 걸린다. 그 사이에 설정이 또 흘러오면
        // (음량만 바꿔도 흘러온다) initializeWithAppKey가 두 번 돌고
        // beginGuidance도 두 번 실행된다
        if (running || initializing) return

        if (!installed) {
            val ok = runCatching {
                KNSDK.install(application, "${application.filesDir}/$KNSDK_DIR")
            }.isSuccess
            if (!ok) {
                DiagLog.add("안전운전 안내 · SDK 설치 실패")
                return
            }
            installed = true
        }

        initializing = true
        val startedAt = generation
        runCatching {
            KNSDK.initializeWithAppKey(
                appKey,
                com.wemade.teslamacro.BuildConfig.VERSION_NAME,
                null,
                null,
                KNLanguageType.KNLanguageType_KOREAN,
            ) { error ->
                // 이 요청이 시작된 회차가 이미 지났으면 지금 여기서 손을 뗀다 —
                // initializing 플래그는 새 회차의 것이라 건드리면 안 된다
                if (startedAt != generation) {
                    DiagLog.add("안전운전 안내 · 지난 회차의 초기화 응답을 버립니다")
                    return@initializeWithAppKey
                }
                initializing = false
                if (error != null) {
                    DiagLog.add("안전운전 안내 · 초기화 실패 (${error.code}) ${error.msg}")
                    return@initializeWithAppKey
                }
                // 초기화를 기다리는 동안 사용자가 껐을 수 있다 — 그때는 시작하지 않는다
                if (!wanted) {
                    DiagLog.add("안전운전 안내 · 초기화 끝났지만 그 사이 꺼졌어요")
                    return@initializeWithAppKey
                }
                beginGuidance()
            }
        }.onFailure {
            if (startedAt == generation) initializing = false
            DiagLog.add("안전운전 안내 · 초기화 예외 ${it.message}")
        }
    }

    /** 초기화가 끝난 뒤 free-drive 가이던스를 붙인다 */
    private fun beginGuidance() {
        runCatching {
            val guidance = KNSDK.sharedGuidance() ?: error("가이던스를 얻지 못했다")
            guidance.guideStateDelegate = stateDelegate
            guidance.locationGuideDelegate = locationDelegate
            guidance.safetyGuideDelegate = safetyDelegate
            guidance.voiceGuideDelegate = voiceDelegate
            guidance.startWithoutTrip()
            running = true
            _state.value = _state.value.copy(ready = true, stalled = false)
            DiagLog.add("안전운전 안내 시작 (경로 없이 감시만)")
            watchForFirstFix()
        }.onFailure {
            DiagLog.add("안전운전 안내 · 가이던스 시작 실패 ${it.message}")
        }
    }

    /**
     * 첫 측위가 안 오는 상태를 침묵으로 두지 않는다.
     *
     * "켜짐"인데 위성을 못 잡으면 화면도 로그도 조용해서, 사용자에겐
     * **기능이 없는 것과 구별되지 않는다.** 이 앱이 여기서 한 번 크게 데었다.
     * 그래서 제한시간이 지나면 로그에 남기고 화면이 "안내 못 함"을 말하게 한다.
     */
    private fun watchForFirstFix() {
        val startedAt = generation
        Handler(Looper.getMainLooper()).postDelayed(
            {
                // 지난 회차의 타이머는 지금 안내와 무관하다.
                // 안 걸러내면 갓 시작한 안내에 거짓 적색이 씌워진다
                if (startedAt != generation || !running || locationSeen) return@postDelayed
                _state.value = _state.value.copy(stalled = true)
                DiagLog.add(
                    "안전운전 안내 · ${FIRST_FIX_TIMEOUT_MS / 1000}초째 위치를 못 받았어요" +
                        " (위치 권한·실외 여부를 확인하세요)"
                )
            },
            FIRST_FIX_TIMEOUT_MS,
        )
    }

    /** 세우거나 설정을 끄면 멈춘다. GPS와 망을 계속 쓰는 기능이라 놔두면 안 된다 */
    fun stop() {
        wanted = false
        reArmed = false
        // 회차를 올린다 — 진행 중인 초기화와 측위 타이머는 취소할 수 없으니,
        // 돌아왔을 때 자기 회차가 지난 걸 보고 물러나게 한다.
        // 잠금도 함께 푼다: 옛 콜백은 이제 이 값을 안 만진다
        generation += 1
        initializing = false
        if (!running) return
        running = false
        runCatching { KNSDK.sharedGuidance()?.stop() }
        releaseTimer.removeCallbacks(releaseFocus)
        clearGuideData()
        _state.value = SafetyState()
        abandonFocus()
        DiagLog.add("안전운전 안내 정지")
    }

    // ---- KNSDK 콜백 ----

    /** 지금 위치. 안전물까지 거리를 재는 기준점이 된다 */
    private var currentPos: Pair<Double, Double>? = null

    /** 위치가 한 번이라도 왔는지. 안 오면 SDK가 GPS를 못 잡은 것이고, 그건 로그에 남아야 한다 */
    @Volatile private var locationSeen = false

    private val locationDelegate = object : KNGuidance_LocationGuideDelegate {
        override fun guidanceDidUpdateLocation(
            aGuidance: KNGuidance,
            aLocationGuide: KNGuide_Location,
        ) {
            // `location`은 **경로에 붙인 위치**다 — 경로 없이 도는 free-drive에서는
            // 비어 있을 수 있고, 그러면 거리를 영영 못 재 경보가 하나도 안 뜬다.
            // 그래서 원시 GPS로 물러난다. 셋 다 같은 KATEC 좌표라 섞어 써도 된다
            // 경로에 붙인 좌표 → 도로에 정합된 GPS → 원시 GPS 순으로 물러난다
            val fix = aLocationGuide.location?.pos?.let { "경로" to it }
                ?: aLocationGuide.gpsMatched?.takeIf { it.valid }?.pos?.let { "정합" to it }
                ?: aLocationGuide.gpsOrigin?.takeIf { it.valid }?.pos?.let { "원시" to it }
                ?: return
            val (source, pos) = fix
            currentPos = pos.x to pos.y
            if (!locationSeen) {
                locationSeen = true
                // 좌표 원문은 남기지 않는다 — 진단 로그는 공유되는 통로다.
                // 어느 소스에서 왔는지는 남긴다: 실차에서 "경로"가 영영 안 오는지를
                // 이 한 줄로 가릴 수 있다
                DiagLog.add("안전운전 안내 · 위치 수신 시작 ($source 좌표)")
                // 늦게라도 잡혔으면 "못 받는 중" 표시를 거둔다
                if (_state.value.stalled) _state.value = _state.value.copy(stalled = false)
                // 측위 전에 도착한 안전물은 거리를 못 재 전부 버려졌다 — 이제 다시 잰다
                recomputeAlert()
            }
        }
    }

    /**
     * 안내 대상 목록과 주변 목록을 **따로** 들고 있다가 합친다.
     *
     * 전에는 두 콜백이 같은 한 칸을 덮어썼다. `safetiesOnGuide`는 **경로 기반**이라
     * 경로 없이 도는 free-drive에서는 늘 빈 목록인데, 그 빈 목록이 주변 목록에서 찾은
     * 경보를 즉시 지웠다 — 경보가 1초 간격으로 뜨고 지워져 화면에는 사실상 안 보였다.
     */
    private var onGuideSafeties: List<KNSafety> = emptyList()
    private var aroundSafeties: List<KNSafety> = emptyList()

    /** 마지막으로 알린 안전물 번호. 같은 것에 로그를 반복해 찍지 않기 위한 기준 */
    private var loggedSafetyId: Int? = null

    private val safetyDelegate = object : KNGuidance_SafetyGuideDelegate {
        override fun guidanceDidUpdateSafetyGuide(
            aGuidance: KNGuidance,
            aSafetyGuide: KNGuide_Safety?,
        ) {
            onGuideSafeties = aSafetyGuide?.safetiesOnGuide.orEmpty()
            recomputeAlert()
        }

        override fun guidanceDidUpdateAroundSafeties(
            aGuidance: KNGuidance,
            aSafeties: List<KNSafety>?,
        ) {
            aroundSafeties = aSafeties.orEmpty()
            recomputeAlert()
        }
    }

    /**
     * 지금 알릴 경보 한 건을 다시 계산한다.
     *
     * 안내 대상이 있으면 그게 정확하고, 없으면 주변 목록으로 물러선다 —
     * free-drive에서는 사실상 항상 주변 목록이다.
     */
    private fun recomputeAlert() {
        // `ifEmpty`로 가르면 안 된다 — 안내 목록에 이미 지난 것만 남았거나
        // 전부 2km 밖이어도 목록이 "비어 있지 않아" 주변 목록을 막는다.
        // 그리고 거리를 모르는 항목은 아예 고르지 않는다: 측위 전에 5km 앞 카메라를
        // "거리 미상"으로 띄우면 경보가 아니라 소음이다
        val nearest = nearestValid(onGuideSafeties) ?: nearestValid(aroundSafeties)
        val alert = nearest?.let { (safety, distance) ->
            SafetyAlert(
                kind = kindOf(safety.code),
                distanceMeters = distance,
                speedLimitKph = (safety as? KNSafety_Camera)?.speedLimit?.takeIf { it > 0 },
            )
        }
        _state.value = _state.value.copy(alert = alert)

        // 같은 안전물에 매 초 로그를 찍으면 진단 로그가 이거로만 찬다 — 바뀔 때만 한 줄
        val id = nearest?.first?.safetyId
        if (id == loggedSafetyId) return
        loggedSafetyId = id
        if (alert == null) {
            DiagLog.add("안전운전 안내 · 다가오는 안전물 없음 (주변 ${aroundSafeties.size}건)")
        } else {
            DiagLog.add(
                "안전운전 안내 · ${alert.kind.label}" +
                    (alert.speedLimitKph?.let { " ${it}km/h" } ?: "") +
                    (alert.distanceMeters?.let { " ${it}m 앞" } ?: " 거리 미상")
            )
        }
    }

    /** 이번 켜짐에서 이미 한 번 되살렸는가. 끊자마자 또 끊는 무한 재기동을 막는다 */
    private var reArmed = false

    /**
     * 아직 안 지났고 거리를 아는 것 중 가장 가까운 하나. 없으면 null.
     * 거리를 여기서 한 번만 재서 함께 돌려준다 — 아래에서 또 재면 그 사이 위치가 바뀐다.
     */
    private fun nearestValid(safeties: List<KNSafety>): Pair<KNSafety, Int>? = safeties
        .asSequence()
        .filterNot { it.passed }
        .mapNotNull { safety -> distanceTo(safety)?.let { safety to it } }
        .minByOrNull { it.second }

    /** 안내가 끝나면 들고 있던 목록도 버린다. 남겨두면 다음 시작 때 낡은 경보가 먼저 뜬다 */
    private fun clearGuideData() {
        onGuideSafeties = emptyList()
        aroundSafeties = emptyList()
        currentPos = null
        locationSeen = false
        loggedSafetyId = null
    }

    private val stateDelegate = object : KNGuidance_GuideStateDelegate {
        override fun guidanceGuideStarted(aGuidance: KNGuidance) {
            _state.value = _state.value.copy(ready = true)
        }

        override fun guidanceGuideEnded(aGuidance: KNGuidance) {
            // running을 안 내리면 SDK가 스스로 끝낸 뒤 start()가 "이미 돌고 있다"며
            // 물러나고 stop()도 물러나, 설정을 껐다 켜도 영영 안 살아난다
            running = false
            clearGuideData()
            _state.value = SafetyState()
            abandonFocus()
            DiagLog.add("안전운전 안내 · SDK가 안내를 종료했어요")

            // 설정은 그대로 켜져 있는데 SDK가 끊은 경우, 설정 흐름은
            // distinctUntilChanged라 다시 흘러오지 않는다 — 아무도 안 살려주면
            // 기능이 조용히 죽는다. 딱 한 번만 다시 세운다(끊자마자 또 끊으면 포기)
            if (!wanted || reArmed) return
            reArmed = true
            val startedAt = generation
            Handler(Looper.getMainLooper()).postDelayed(
                // 이 5초 사이에 껐다 켜졌으면 새 회차가 알아서 세운다 —
                // 여기서 또 세우면 안내가 둘이 된다
                { if (startedAt == generation && wanted && !running) beginGuidance() },
                RE_ARM_DELAY_MS,
            )
        }

        // 경로가 없으니 경로 관련 콜백은 올 일이 없다
        override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) = Unit
        override fun guidanceRouteUnchanged(aGuidance: KNGuidance) = Unit
        override fun guidanceOutOfRoute(aGuidance: KNGuidance) = Unit
        override fun guidanceRouteUnchangedWithError(
            aGuidance: KNGuidance,
            aError: com.kakaomobility.knsdk.common.objects.KNError,
        ) = Unit

        override fun guidanceRouteChanged(
            aGuidance: KNGuidance,
            aFromRoute: KNRoute,
            aFromLocation: KNLocation,
            aToRoute: KNRoute,
            aToLocation: KNLocation,
            aChangeReason: com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason,
        ) = Unit

        override fun guidanceDidUpdateRoutes(
            aGuidance: KNGuidance,
            aRoutes: List<KNRoute>,
            aMultiRouteInfo: com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo?,
        ) = Unit

        override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) = Unit
    }

    /**
     * 안전물까지 남은 거리(m).
     *
     * KATEC 좌표는 미터 기반이라 두 점의 직선거리가 곧 미터에 가깝다 —
     * 도로를 따라간 실제 거리보다 짧게 나오지만, 경고 시점을 잡는 데는 충분하다.
     */
    private fun distanceTo(safety: KNSafety): Int? {
        val (nowX, nowY) = currentPos ?: return null
        val pos = safety.location.pos
        return hypot(pos.x - nowX, pos.y - nowY).roundToInt().takeIf { it in 0..MAX_DISTANCE_M }
    }

    // ---- 소리 ----

    /**
     * 경보 음성. **오디오는 KNSDK가 직접 재생한다** — 우리가 하는 일은
     * "재생할까?"에 답하고, 음량과 오디오 속성을 정하고, 포커스를 빌려주는 것뿐이다.
     *
     * 델리게이트를 안 걸어두면 SDK는 물어볼 곳이 없어 아무 소리도 내지 않는다.
     * 지금까지 경보가 조용했던 이유의 절반이 이것이다.
     */
    private val voiceDelegate = object : KNGuidance_VoiceGuideDelegate {
        override fun shouldPlayVoiceGuide(
            aGuidance: KNGuidance,
            aVoiceGuide: KNGuide_Voice,
            aNewData: MutableList<ByteArray>,
        ): Boolean {
            if (!soundEnabled) return false
            // 포커스를 여기서 빌린다 — 재생 여부를 되돌릴 수 있는 자리가 여기뿐이다.
            // willPlay에서 요청하면 실패해도 재생은 이미 시작돼, 다른 앱 소리와 겹친다
            if (!requestFocus()) {
                DiagLog.add("안전운전 안내 · 오디오 포커스를 얻지 못해 음성을 건너뜀")
                return false
            }
            // 완료 콜백이 한 번이라도 빠지면 포커스를 쥔 채로 남아
            // 내비·음악이 영영 낮은 볼륨이 된다. 재생 길이만큼 기다렸다 스스로 놓는다
            scheduleFocusRelease(runCatching { aVoiceGuide.duration }.getOrDefault(0))
            runCatching {
                aVoiceGuide.setVolume(volumeOf(volumeLevel))
                // 길안내 음성으로 신고한다 — 음악으로 신고하면 내비 안내를 밀어내고,
                // 차 스피커가 "미디어 정지"로 반응하는 기기도 있다
                aVoiceGuide.setAudioAttribute(guidanceAttributes)
            }
            return true
        }

        override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) = Unit

        override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
            releaseTimer.removeCallbacks(releaseFocus)
            abandonFocus()
        }
    }

    private val audioManager: AudioManager? by lazy {
        application.getSystemService(AudioManager::class.java)
    }

    private val guidanceAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private var focusRequest: AudioFocusRequest? = null

    private val releaseTimer = Handler(Looper.getMainLooper())

    private val releaseFocus = Runnable {
        if (focusRequest == null) return@Runnable
        DiagLog.add("안전운전 안내 · 재생 완료 통지가 없어 오디오 포커스를 스스로 반납")
        abandonFocus()
    }

    /**
     * 안전망 타이머. 재생 길이(ms)를 모르면 넉넉히 잡는다 —
     * 너무 일찍 놓으면 말하는 도중에 다른 앱이 볼륨을 올린다.
     */
    private fun scheduleFocusRelease(durationMillis: Int) {
        releaseTimer.removeCallbacks(releaseFocus)
        val wait = durationMillis.takeIf { it > 0 }?.toLong() ?: DEFAULT_VOICE_MS
        releaseTimer.postDelayed(releaseFocus, wait + FOCUS_RELEASE_GRACE_MS)
    }

    /**
     * 재생 직전 포커스를 빌린다.
     *
     * `GAIN_TRANSIENT_MAY_DUCK` — 내비 음성을 **끊지 않고 잠깐 낮춘다.**
     * 단속 경보 때문에 "300m 앞 우회전"을 놓치면 바꾼 게 손해다.
     */
    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return false
        // 이미 빌려둔 상태면 그대로 쓴다 — 경보가 연달아 나올 때 매번 반납·재요청하면
        // 내비 음성이 두 번 출렁인다
        if (focusRequest != null) return true
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(guidanceAttributes)
            .build()
        val granted = runCatching { manager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    /** 반납. 안 하면 내비·음악이 계속 낮은 볼륨으로 남는다 */
    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    private companion object {
        /**
         * SDK가 데이터를 두는 폴더. **절대 경로**로 줘야 한다 —
         * 상대 이름만 주면 SDK가 그걸 DB 파일명으로 넘겨 초기화가 통째로 죽는다
         * (`File knsdk/KNSDK/NetworkLink/network.sqlite contains a path separator`).
         */
        const val KNSDK_DIR = "knsdk"

        /** 이보다 먼 것은 안내하지 않는다. 2km 앞 카메라는 지금 볼 정보가 아니다 */
        const val MAX_DISTANCE_M = 2_000

        /**
         * 첫 측위를 이만큼 기다린다. 콜드 스타트 GPS는 30초를 넘기기도 하지만,
         * 60초가 지나도 안 오면 그건 기다림이 아니라 고장이다
         */
        const val FIRST_FIX_TIMEOUT_MS = 60_000L

        /** SDK가 스스로 안내를 끊었을 때 다시 세워보는 간격 */
        const val RE_ARM_DELAY_MS = 5_000L

        /** 재생 길이를 모를 때 가정하는 안내 길이 */
        const val DEFAULT_VOICE_MS = 5_000L

        /** 그 위에 얹는 여유. 말이 끝나기 전에 포커스를 놓으면 안 된다 */
        const val FOCUS_RELEASE_GRACE_MS = 2_000L

        /** 음량 1~3을 재생 배율로. 1에서도 들려야 하니 0에서 시작하지 않는다 */
        fun volumeOf(level: Int): Float = when (level) {
            1 -> 0.4f
            3 -> 1.0f
            else -> 0.7f
        }
    }
}

/** KNSDK의 50여 가지 코드를 사람이 반응을 바꾸는 단위로 묶는다 */
internal fun kindOf(code: KNSafetyCode): SafetyKind = when (code) {
    KNSafetyCode.KNSafetyCode_ChildrenProtectionZone,
    KNSafetyCode.KNSafetyCode_ChildrenAccidentPos,
    -> SafetyKind.PROTECTION_ZONE

    KNSafetyCode.KNSafetyCode_SharpTurnSection,
    KNSafetyCode.KNSafetyCode_FallingRocksArea,
    KNSafetyCode.KNSafetyCode_FogArea,
    KNSafetyCode.KNSafetyCode_FogAreaLive,
    KNSafetyCode.KNSafetyCode_SlippingRoad,
    KNSafetyCode.KNSafetyCode_FrozenRoad,
    KNSafetyCode.KNSafetyCode_Hump,
    KNSafetyCode.KNSafetyCode_RoadNarrows,
    KNSafetyCode.KNSafetyCode_SteepDownhillSection,
    KNSafetyCode.KNSafetyCode_UphillSection,
    KNSafetyCode.KNSafetyCode_RailroadCrossing,
    KNSafetyCode.KNSafetyCode_AnimalsAppearingCaution,
    KNSafetyCode.KNSafetyCode_FallingCaution,
    -> SafetyKind.ROAD_HAZARD

    KNSafetyCode.KNSafetyCode_TrafficAccidentPos,
    KNSafetyCode.KNSafetyCode_CarAccidentPos,
    KNSafetyCode.KNSafetyCode_PedestrianAccidentPos,
    KNSafetyCode.KNSafetyCode_DrowsyDrivingAccidentPos,
    KNSafetyCode.KNSafetyCode_IntentTrafficAccident,
    -> SafetyKind.ACCIDENT_SPOT

    else -> {
        // 이름으로 가른다 — 카메라 코드는 종류가 많고 버전마다 늘어난다.
        // 못 알아본 코드를 "안전 구간"으로 뭉뚱그리면 단속을 놓친다
        val name = code.name
        when {
            name.contains("Section", ignoreCase = true) &&
                name.contains("Speed", ignoreCase = true) -> SafetyKind.SECTION_CAMERA
            name.contains("Camera", ignoreCase = true) ||
                name.contains("Speed", ignoreCase = true) -> SafetyKind.SPEED_CAMERA
            else -> SafetyKind.OTHER
        }
    }
}

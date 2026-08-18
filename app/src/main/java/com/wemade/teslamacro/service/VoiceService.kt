package com.wemade.teslamacro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.R
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.voice.VoiceEvent
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.command.confirmCategory
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.voice.QueryTopic
import com.wemade.teslamacro.domain.voice.VoiceCommandParser
import com.wemade.teslamacro.domain.voice.VoiceIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 음성 상시 대기.
 *
 * 마이크는 두 조건이 모두 맞을 때만 열린다 — 설정이 켜져 있고, 차량에 연결돼 있을 때.
 * 집에 있을 때는 열리지 않는다.
 *
 * 들은 말은 기기 밖으로 나가지 않는다. 인식도 실행도 전부 기기 안에서 끝난다.
 */
class VoiceService : LifecycleService() {

    private var speaker: TextToSpeech? = null

    /** 호출 응답용 효과음. 말("네")보다 짧아 마이크 전환이 빨라진다 */
    private var tone: android.media.ToneGenerator? = null

    /** 같은 말이 연달아 들어올 때 두 번 실행하는 걸 막는다 */
    private var lastSpoken: String = ""
    private var lastSpokenAtMillis: Long = 0L

    /** 침묵으로 갈라진 직전 조각 ("통풍" / "켜줘"). 잠깐 들고 있다가 이어붙여 해석한다 */
    private var fragmentText: String = ""
    private var fragmentAtMillis: Long = 0L

    /** 정밀 인식 중이면 vosk가 마이크를 내려놓는다 — 마이크는 한 번에 하나만 잡을 수 있다 */
    private val preciseMode = MutableStateFlow(false)
    private var preciseJob: Job? = null

    /**
     * 마이크 부활 틱. 내비·전화가 마이크를 뺏어 vosk가 죽으면 조용히 끝이었다 —
     * 설정이나 연결이 바뀌기 전엔 combine이 재발화하지 않아 상시 대기가 영영 침묵했다.
     * retryable 실패 시 이 값을 올려 listen 재구독을 강제한다
     */
    private val restartTick = MutableStateFlow(0)
    private var retryJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 마이크 권한 없이 microphone 타입 포그라운드를 올리면 SecurityException으로 프로세스가 죽고,
        // voiceAlwaysOn이 저장돼 있어 앱을 열 때마다 다시 죽는 루프가 된다 — 못 올리면 스스로 내린다
        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMic || runCatching { startInForeground() }.isFailure) {
            com.wemade.teslable.DiagLog.add(
                "음성 대기 시작 실패 — " + if (!hasMic) "마이크 권한 없음" else "포그라운드 승격 거부"
            )
            stopSelf()
            return
        }
        speaker = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) speaker?.language = Locale.KOREAN
        }
        tone = runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, TONE_VOLUME)
        }.getOrNull()

        val app = application as TeslaMacroApplication
        lifecycleScope.launch {
            app.ready.first { it }
            val container = app.container
            val parser = VoiceCommandParser { container.ruleStore.rules.value }

            // 하이브리드 가능 여부를 처음에 한 번 남긴다 — 기기(iPlay 60 등) 판별용
            com.wemade.teslable.DiagLog.add(
                "내장 음성 인식기: " +
                    if (container.voiceRecognizer.isAvailable) "사용 가능 — 하이브리드 켜짐"
                    else "없음 — vosk 단독"
            )

            // 1. 설정 + 연결 상태 + 정밀 인식 여부 + 매크로 목록을 보고 vosk 마이크를 연다.
            //    매크로 목록이 combine에 들어가야 이름을 바꾸는 즉시 어휘가 갱신된다 —
            //    빼면 "출근 준비"로 개명해도 옛 문법이 남아 그 말을 영영 못 알아듣는다
            combine(
                container.settingsStore.settings,
                container.gateway.linkState,
                preciseMode,
                container.ruleStore.rules,
                restartTick,
            ) { settings, link, precise, rules, tick ->
                val active = settings.voiceAlwaysOn && link is LinkState.Ready && !precise
                // tick을 결과에 실어야 distinctUntilChanged를 뚫고 재구독이 일어난다
                if (!active) null
                else rules.flatMap { it.name.split(" ") }.filter { it.isNotBlank() } to tick
            }
                .distinctUntilChanged()
                .flatMapLatest { keyed ->
                    if (keyed == null) emptyFlow()
                    else container.hotwordListener.listen(
                        VoiceCommandParser.VOCABULARY + keyed.first
                    )
                }
                .collect { event -> handle(event, parser, app) }
        }
    }

    /** 마이크 재구독 예약. 이미 예약돼 있으면 중복 예약하지 않는다 */
    private fun scheduleMicRetry() {
        if (retryJob?.isActive == true) return
        retryJob = lifecycleScope.launch {
            delay(RETRY_DELAY_MS)
            restartTick.value += 1
        }
    }

    private fun handle(
        event: VoiceEvent,
        parser: VoiceCommandParser,
        app: TeslaMacroApplication,
    ) {
        when (event) {
            // 알림은 절대 건드리지 않는다 — 문구가 바뀌는 것 자체가 소음이다. 피드백은 TTS로만
            is VoiceEvent.Heard -> execute(event.candidates, parser, app)
            // 마이크가 죽으면 로그를 남기고, 살릴 수 있는 실패면 잠시 뒤 재구독한다
            is VoiceEvent.Failed -> {
                com.wemade.teslable.DiagLog.add(
                    "상시 대기 실패: ${event.reason}" +
                        if (event.retryable) " — ${RETRY_DELAY_MS / 1000}초 후 재시도" else ""
                )
                if (event.retryable) scheduleMicRetry()
            }
            else -> Unit
        }
    }

    /**
     * 들은 말을 실행한다. 호출어("테슬라")가 들린 말만 상대한다.
     *
     * "테슬라 통풍 켜"처럼 명령까지 한 번에 들리면 즉시 실행하고,
     * "테슬라"만 들리면(또는 뒷말을 vosk가 못 알아들으면) 정밀 인식으로 넘어간다.
     */
    private fun execute(
        candidates: List<String>,
        parser: VoiceCommandParser,
        app: TeslaMacroApplication,
    ) {
        val spoken = candidates.firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        // 직전에 호출어 조각을 들었으면("테슬라 앞" → "열어") 뒷조각은 호출어가 없어도 통과시킨다.
        // 이 예외가 없으면 이어붙이기가 죽은 코드가 된다 — 뒷조각이 여기서 다 버려지니까
        val fragmentOpen = fragmentText.isNotBlank() &&
            now - fragmentAtMillis < FRAGMENT_WINDOW_MILLIS
        if (REQUIRE_WAKE_WORD && !fragmentOpen && candidates.none { parser.hasWakeWord(it) }) return

        if (spoken == lastSpoken && now - lastSpokenAtMillis < REPEAT_GUARD_MILLIS) return
        lastSpoken = spoken
        lastSpokenAtMillis = now

        // 문장이 침묵 기준으로 두 조각으로 갈라진다 ("앞" → "열어" — 실차 로그).
        // 직전 조각과 이어붙인 해석을 먼저 시도하고, 안 되면 이번 조각만으로 해석한다
        val stitched = if (fragmentOpen) candidates.map { "$fragmentText $it" } else emptyList()

        val intent = parser.parseCandidates(stitched + candidates, requireWake = REQUIRE_WAKE_WORD)

        // 해석 실패한 조각은 다음 조각을 위해 남겨두고, 성공하면 비운다
        if (intent is VoiceIntent.NotUnderstood) {
            fragmentText = spoken
            fragmentAtMillis = now
        } else {
            fragmentText = ""
        }

        // 뭐라고 들었고 뭘로 해석했는지 남긴다. 오작동 신고가 오면 이 로그가 유일한 단서다
        com.wemade.teslable.DiagLog.add("음성 $candidates → ${describeIntent(intent)}")

        // 하이브리드: vosk가 해석 못 했는데 호출어가 들렸다면,
        // 사용자가 말을 걸었다는 뜻이다 — 정확한 내장 인식기로 문장을 다시 받는다
        if (intent is VoiceIntent.NotUnderstood && candidates.any { parser.hasWakeWord(it) }) {
            startPrecise(parser, app)
            return
        }

        // vosk 단독 판정은 잡음일 수 있어 실패해도 조용히 넘어간다
        act(intent, app, announceFailure = false)
    }

    /** 해석 결과를 실제 동작으로 옮긴다. vosk 빠른 길과 정밀 인식 길이 여기로 합류한다 */
    private fun act(intent: VoiceIntent, app: TeslaMacroApplication, announceFailure: Boolean) {
        when (intent) {
            is VoiceIntent.RunCommand -> {
                announce(intent.command.label)
                lifecycleScope.launch {
                    // 사람 말이 매크로보다 우선이다
                    app.container.runner.cancelAll()
                    val result = app.container.gateway.send(intent.command)
                    // 운전 중엔 화면을 못 본다. 차단 사유("P단에서만…")까지 소리로 알려준다
                    if (result.isFailure) {
                        announce(result.exceptionOrNull()?.message ?: "${intent.command.label} 실패")
                    } else {
                        // 말로 시킨 것도 화면(태블릿)은 보인다 — 결과를 즉시 다시 읽어 확정한다
                        app.container.poller.focusOn(intent.command.confirmCategory())
                    }
                }
            }

            is VoiceIntent.RunMacro -> {
                announce("${intent.rule.name} 실행")
                // 음성 실행도 수동 — 기존 실행을 끊고 처음부터 + 쿨다운 기록
                app.container.runner.launch(intent.rule, System.currentTimeMillis(), restartIfRunning = true)
                app.container.poller.recordFired(intent.rule.id)
            }

            // 상대 조절 — 현재 값은 폴러 스냅샷이 안다. 여기서 절대값 명령으로 바꿔 재귀한다
            is VoiceIntent.AdjustTemp -> {
                val current = app.container.poller.snapshot.value.driverTempSettingC ?: 22.0
                val target = (current + intent.deltaC).coerceIn(15.0, 28.0)
                act(
                    VoiceIntent.RunCommand(VehicleCommand.SetTemperature(target), intent.spoken),
                    app, announceFailure,
                )
            }

            is VoiceIntent.AdjustSeat -> {
                val snapshot = app.container.poller.snapshot.value
                val current = when (intent.mode) {
                    SeatMode.COOL -> snapshot.seatCooler[intent.seat]
                    SeatMode.HEAT -> snapshot.seatHeater[intent.seat]
                } ?: Level.OFF
                val level = Level.fromStep((current.ordinal + intent.delta).coerceIn(0, 3))
                val command = when (intent.mode) {
                    SeatMode.COOL -> VehicleCommand.SetSeatCooler(intent.seat, level)
                    SeatMode.HEAT -> VehicleCommand.SetSeatHeater(intent.seat, level)
                }
                act(VoiceIntent.RunCommand(command, intent.spoken), app, announceFailure)
            }

            // 상태 질문 — 명령이 아니라 대답이다. 폴러가 들고 있는 최신 값으로 답한다
            is VoiceIntent.Ask -> {
                val snapshot = app.container.poller.snapshot.value
                announce(
                    when (intent.topic) {
                        QueryTopic.BATTERY ->
                            snapshot.batteryLevelPercent?.let { "배터리 ${it}퍼센트예요" }
                                ?: "배터리를 아직 못 읽었어요"
                        QueryTopic.TEMPERATURE ->
                            snapshot.insideTempC?.let { inside ->
                                "실내 ${"%.0f".format(inside)}도" +
                                    (snapshot.outsideTempC?.let { ", 바깥 ${"%.0f".format(it)}도예요" } ?: "예요")
                            } ?: "온도를 아직 못 읽었어요"
                        QueryTopic.LOCK -> when (snapshot.isLocked) {
                            true -> "잠겨 있어요"
                            false -> "열려 있어요"
                            null -> "잠금 상태를 아직 못 읽었어요"
                        }
                    }
                )
            }

            is VoiceIntent.NotUnderstood -> if (announceFailure) announce("못 알아들었어요")
        }
    }

    /**
     * 하이브리드 정밀 인식.
     *
     * vosk 마이크를 잠깐 내려놓고 내장(구글) 인식기로 문장을 받는다.
     * 소형 vosk 모델이 못 적는 자연어("보닛 열어줘")를 여기서 받아낸다.
     * 마이크는 한 번에 하나만 잡을 수 있어 preciseMode로 vosk를 먼저 세운다.
     */
    private fun startPrecise(parser: VoiceCommandParser, app: TeslaMacroApplication) {
        if (preciseJob?.isActive == true) return
        // 내장 인식기가 없는 기기는 vosk 단독으로 산다
        if (!app.container.voiceRecognizer.isAvailable) return

        preciseJob = lifecycleScope.launch {
            com.wemade.teslable.DiagLog.add("호출어 감지 → 내장 인식기로 전환")
            preciseMode.value = true
            delay(MIC_HANDOVER_MILLIS)   // vosk가 마이크를 실제로 반납할 시간을 준다
            chime()                      // "네" 대신 효과음 — 짧아서 전환이 빠르다
            delay(TTS_GAP_MILLIS)        // 효과음이 인식기에 섞이지 않게 잠깐 띄운다

            try {
                var handled = false
                withTimeoutOrNull(PRECISE_TIMEOUT_MILLIS) {
                    // 말 시작이 조금만 늦어도 인식기가 침묵으로 보고 포기한다(실차 로그 2.4초).
                    // 재시도 가능한 실패면 한 번 더 듣는다
                    for (attempt in 1..PRECISE_ATTEMPTS) {
                        var retryable = false
                        app.container.voiceRecognizer.listenOnce().collect { event ->
                            when (event) {
                                is VoiceEvent.Heard -> {
                                    handled = true
                                    val intent = parser.parseCandidates(event.candidates)
                                    com.wemade.teslable.DiagLog.add(
                                        "정밀 인식 ${event.candidates.take(2)} → ${describeIntent(intent)}"
                                    )
                                    act(intent, app, announceFailure = true)
                                }
                                is VoiceEvent.Failed -> {
                                    com.wemade.teslable.DiagLog.add("정밀 인식 실패: ${event.reason}")
                                    retryable = event.retryable
                                }
                                else -> Unit
                            }
                        }
                        if (handled || !retryable) break
                        com.wemade.teslable.DiagLog.add("정밀 인식 재시도 ($attempt/${PRECISE_ATTEMPTS})")
                        // 곧바로 다시 열면 인식기가 정리되기 전이라 즉사한다 (실차 code=11, 5ms 간격)
                        delay(RETRY_GAP_MILLIS)
                    }
                }
                // 포기했으면 소리로 알린다. 조용히 끝나면 사용자는 기다리기만 한다
                if (!handled) announce("못 들었어요. 다시 불러 주세요")
            } finally {
                preciseMode.value = false   // vosk 상시 대기 복귀
            }
        }
    }

    private fun describeIntent(intent: VoiceIntent): String = when (intent) {
        is VoiceIntent.RunCommand -> intent.command.label
        is VoiceIntent.RunMacro -> "매크로 ${intent.rule.name}"
        is VoiceIntent.AdjustTemp -> "온도 ${if (intent.deltaC > 0) "올리기" else "내리기"}"
        is VoiceIntent.AdjustSeat -> "${intent.mode.label} ${if (intent.delta > 0) "올리기" else "내리기"}"
        is VoiceIntent.Ask -> "상태 질문 (${intent.topic})"
        is VoiceIntent.NotUnderstood -> "해석 실패"
    }

    /** 차 안에서는 화면을 못 본다. 알아들었는지 소리로만 알려준다 — 알림 갱신은 소음이라 뺐다 */
    private fun announce(text: String) {
        speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice")
    }

    /** 호출 확인 "삑". 톤 생성이 실패한 기기에서는 조용히 넘어간다 */
    private fun chime() {
        runCatching {
            tone?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, TONE_LENGTH_MILLIS)
        }
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        // 기기는 한 번 만든 채널의 중요도를 기억한다. 낮추려면 새 ID로 만들어야 적용된다
        manager.deleteNotificationChannel("voice_listen")
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_channel_name),
            NotificationManager.IMPORTANCE_MIN,   // 상태바 아이콘 없이 목록 맨 아래로
        )
        manager.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    // 문구 한 줄짜리 고정 알림. 마이크 서비스는 알림 없이는 못 돌아서 이게 최소치다
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_title))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice_listen_min"
        private const val NOTIFICATION_ID = 1002
        private const val REPEAT_GUARD_MILLIS = 3_000L

        // "테슬라"를 불러야만 반응한다. 호출어 없이 풀었더니 잡담 오작동이 잦았다 (0.4.11~0.4.23 실험)
        private const val REQUIRE_WAKE_WORD = true

        // 이 안에 도착한 조각은 한 문장으로 취급한다. 실측: 조각 간격 ~1.8초
        private const val FRAGMENT_WINDOW_MILLIS = 3_000L

        // 정밀 인식(내장 인식기) 관련. 상한이 없으면 마이크를 영영 못 돌려받는다
        private const val PRECISE_TIMEOUT_MILLIS = 15_000L
        private const val PRECISE_ATTEMPTS = 2
        private const val MIC_HANDOVER_MILLIS = 300L
        private const val TTS_GAP_MILLIS = 350L   // 효과음(120ms)이 끝나고 남을 만큼만
        private const val RETRY_GAP_MILLIS = 400L
        private const val TONE_VOLUME = 80        // 0~100
        private const val TONE_LENGTH_MILLIS = 120
        /** 마이크 부활 대기. 내비 음성안내·전화가 마이크를 놓을 시간을 준다 */
        private const val RETRY_DELAY_MS = 15_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}

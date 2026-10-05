package com.wemade.teslamacro.feature.settings

import android.content.Intent
import com.wemade.teslamacro.data.nav.NavigationPairingService
import com.wemade.teslamacro.data.nav.NavigationSetup
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.ui.component.*
import com.wemade.teslamacro.ui.theme.*

/** 페어링 코드는 화면 메모리에만 두며 자동 실행은 사용자가 따로 선택한다. */
@Composable
internal fun WirelessNavigationPanel(settings: AppSettings, controls: NavigationControls) {
    val context = LocalContext.current
    val state = controls.wirelessState
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    val owner = LocalLifecycleOwner.current
    val prerequisites = rememberOnResume { NavigationSetup.read(context) }
    var setupRequest by remember { mutableIntStateOf(0) }
    var waitingStep by remember { mutableStateOf<NavigationSetup.Step?>(null) }
    var setupMessage by remember { mutableStateOf<String?>(null) }
    var checkingSetup by remember { mutableStateOf(false) }
    LaunchedEffect(prerequisites) {
        if (prerequisites.completed(waitingStep)) setupRequest++
    }
    // 복귀 상태 갱신이 사용자가 누른 연결 확인을 중간에 취소하지 않게 요청 수명과 분리한다.
    LaunchedEffect(setupRequest) {
        if (setupRequest == 0) return@LaunchedEffect
        waitingStep = null
        checkingSetup = true
        try {
            val step = NavigationSetup.resolve(context)
            setupMessage = step.message
            if (step != NavigationSetup.Step.READY) {
                // 연결 대기 중 앱을 벗어나면 복귀할 때 열어 백그라운드 화면 실행 제한을 피한다.
                owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                waitingStep = step
                android.widget.Toast.makeText(context, step.message, android.widget.Toast.LENGTH_LONG).show()
                try {
                    if (step == NavigationSetup.Step.PAIR) NavigationPairingService.begin(context)
                    else context.startActivity(NavigationSetup.intent(context, step))
                } catch (error: Exception) {
                    waitingStep = null
                    setupMessage = step.message + " · 설정 화면에서 직접 선택해 주세요"
                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        .onFailure { setupMessage = "설정 화면을 열 수 없어요 · 휴대폰 설정에서 직접 확인해 주세요" }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            setupMessage = "연결 설정을 확인하지 못했어요 · 다시 눌러 주세요"
            com.wemade.teslable.DiagLog.add("네이버 안심주행 · 설정 확인 실패 ${error.javaClass.simpleName}")
        } finally { checkingSetup = false }
    }
    val supported = Build.VERSION.SDK_INT >= 31
    Spacer(Modifier.height(Space.lg))
    HelpTitle("네이버 안심주행 · 실험", "Android 12 이상에서 자체 무선 디버깅으로 별도 화면 실행을 시도해요. " +
        "휴대폰 잠금을 해제하지 않으며 실제 음성 출력은 기기에서 확인해야 해요.\n\n" +
        "‘연결 설정’을 누르면 개발자 옵션·USB 디버깅·Wi-Fi·알림 중 필요한 설정 화면을 열어요. 설정을 마치고 돌아오면 다음 단계로 이어져요. " +
        "무선 디버깅을 켜고 ‘페어링 코드로 기기 페어링’을 연 다음, 화면을 닫지 말고 알림의 ‘코드 입력’에 6자리를 입력해 주세요. " +
        "IP·포트는 자동으로 찾으며 코드는 저장하지 않아요. 알림 입력을 위해 앱 알림을 허용해 주세요. " +
        "저장된 인증이 있으면 코드 입력 없이 연결을 먼저 시도해요. 자동 탐색이 안 되면 아래 수동 입력을 사용해 주세요.\n\n" +
        "명령을 보내기 전에 꺼진 무선 디버깅을 다시 켜고, 자동 실행을 켠 상태로 차량에 연결되어 있으면 계속 켜 둬요. 연결 해제 30초 뒤 지도 종료를 마치면 꺼요. USB 디버깅도 켜 두고 무선 디버깅에 사용할 Wi-Fi에 연결해 주세요. 케이블 연결은 필요 없어요. 자동 실행을 켜면 재부팅 후 Wi-Fi 연결 때 준비 복구를 시도해요. " +
        "처음 Wi-Fi 허용 창에서 ‘이 네트워크에서 항상 허용’을 선택하면 다음 준비 때 확인을 줄일 수 있어요. 프로세스가 종료되면 Wi-Fi에서 다시 준비해 주세요.\n\n" +
        "실행 전 네이버지도 초기 설정·위치·음량 설정을 마쳐 주세요. 기존 네이버지도 실행이 있으면 실험을 시작하지 않아요. " +
        "테스트 버튼을 누른 뒤 화면을 잠가 음성을 확인해 주세요.\n\n" +
        "자동 실행은 아래에서 직접 선택한 차량 오디오 연결을 사용해요. 연결 해제 30초 뒤 실험을 종료해요. " +
        "종료 시 네이버지도를 강제 종료하므로 실험 도중 직접 시작한 길안내도 함께 종료돼요. " +
        "이 실험은 이 휴대폰의 네이버지도에만 명령을 보내요.")
    Spacer(Modifier.height(Space.sm))
    TCard {
        if (!supported) {
            Text("Android 12 이상에서 사용할 수 있어요", style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
        } else {
            Text(if (checkingSetup) "연결 설정 확인 중…" else if (state.busy || state.prepared) state.message else setupMessage ?: state.message, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
            // 최초 설정만 전면에 두고 완료 후에는 다른 설정과 같은 상세 시트로 관리한다.
            if (!state.prepared && !state.running) {
                Spacer(Modifier.height(Space.md))
                TButton("연결 설정", ButtonTone.Secondary, enabled = !checkingSetup && !state.busy) {
                    setupRequest++
                }
            }
            if (state.busy || state.running) {
                Spacer(Modifier.height(Space.sm))
                TButton("종료", ButtonTone.Ghost, enabled = !checkingSetup, onClick = controls.onWirelessStop)
            }
            Spacer(Modifier.height(Space.md))
            SettingToggleRow("차량 오디오 연결 시 자동 실행", checked = state.enabled,
                onCheckedChange = controls.onWirelessEnabled)
            SettingsDetails("차량 오디오 선택") { VehicleAudioPicker(settings, controls) }
            if (state.enabled && settings.vehicleAudioAddress.isBlank()) {
                Text("자동 실행할 차량 오디오를 직접 선택해 주세요", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            }
            SettingsDetails("연결 관리", if (state.prepared) "연결됨" else "준비 필요") {
                TButton("다시 연결 설정", ButtonTone.Secondary, enabled = !checkingSetup && !state.busy && !state.running) {
                    setupRequest++
                }
                Spacer(Modifier.height(Space.sm))
                TButton("연결 준비 / 복구", ButtonTone.Secondary, enabled = !state.busy && !state.running,
                    onClick = { setupMessage = null; controls.onWirelessPrepare() })
                Spacer(Modifier.height(Space.sm))
                SettingsDetails("수동 페어링 / 연결 포트") {
                    TButton("개발자 옵션 열기", ButtonTone.Secondary) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                            .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }
                    Spacer(Modifier.height(Space.sm))
                    DraftField(pairingPort, { pairingPort = it.filter(Char::isDigit).take(5) }, "페어링 포트 · 비우면 자동 탐색",
                        enabled = !state.busy && !state.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.height(Space.sm))
                    DraftField(pairingCode, { pairingCode = it.filter(Char::isDigit).take(6) }, "페어링 코드 6자리",
                        enabled = !state.busy && !state.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(Space.sm))
                    TButton("페어링", enabled = !state.busy && !state.running && pairingCode.length == 6) {
                        setupMessage = null
                        controls.onWirelessPair(pairingPort, pairingCode)
                        pairingCode = ""
                    }
                    Spacer(Modifier.height(Space.md))
                    DraftField(state.port, controls.onWirelessPort, "연결 포트",
                        enabled = !state.busy && !state.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            SettingsDetails("실행 점검") {
                TButton("10초 뒤 테스트", ButtonTone.Secondary,
                    enabled = !checkingSetup && !state.busy && !state.running, onClick = controls.onWirelessTest)
            }
        }
    }
}

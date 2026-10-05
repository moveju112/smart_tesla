package com.wemade.teslamacro.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.wemade.teslamacro.data.nav.NavigationPairingService
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
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) NavigationPairingService.begin(context)
    }
    val supported = Build.VERSION.SDK_INT >= 31
    Spacer(Modifier.height(Space.lg))
    HelpTitle("네이버 안심주행 · 실험", "Android 12 이상에서 자체 무선 디버깅으로 별도 화면 실행을 시도해요. " +
        "휴대폰 잠금을 해제하지 않으며 실제 음성 출력은 기기에서 확인해야 해요.\n\n" +
        "처음에는 개발자 옵션·USB 디버깅을 켜고 Wi-Fi에 연결한 뒤 ‘연결 설정’을 눌러 주세요. " +
        "무선 디버깅을 켜고 ‘페어링 코드로 기기 페어링’을 연 다음, 화면을 닫지 말고 알림의 ‘코드 입력’에 6자리를 입력해 주세요. " +
        "IP·포트는 자동으로 찾으며 코드는 저장하지 않아요. 알림 입력을 위해 앱 알림을 허용해 주세요. " +
        "저장된 인증이 있으면 코드 입력 없이 연결을 먼저 시도해요. 자동 탐색이 안 되면 아래 수동 입력을 사용해 주세요.\n\n" +
        "준비가 끝나면 USB 디버깅을 켜 둔 상태에서 Wi-Fi 없이 실행·종료할 수 있어요. 케이블 연결은 필요 없어요. 자동 실행을 켜면 재부팅 후 Wi-Fi 연결 때 준비 복구를 시도해요. " +
        "처음 허용하지 않은 네트워크는 시스템 확인이 필요할 수 있어요. 프로세스가 종료되면 Wi-Fi에서 다시 준비해 주세요.\n\n" +
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
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = T.Ink)
            Spacer(Modifier.height(Space.md))
            TButton("연결 설정", ButtonTone.Secondary, enabled = !state.busy && !state.running) {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else NavigationPairingService.begin(context)
            }
            Spacer(Modifier.height(Space.sm))
            TButton("연결 준비 / 복구", ButtonTone.Secondary, enabled = !state.busy && !state.running,
                onClick = controls.onWirelessPrepare)
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
                    controls.onWirelessPair(pairingPort, pairingCode)
                    pairingCode = ""
                }
                Spacer(Modifier.height(Space.md))
                DraftField(state.port, controls.onWirelessPort, "연결 포트",
                    enabled = !state.busy && !state.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TButton("10초 뒤 테스트", ButtonTone.Secondary, modifier = Modifier.weight(1f),
                    enabled = !state.busy && !state.running, onClick = controls.onWirelessTest)
                TButton("종료", ButtonTone.Ghost, modifier = Modifier.weight(1f),
                    enabled = state.busy || state.running, onClick = controls.onWirelessStop)
            }
            Spacer(Modifier.height(Space.md))
            SettingToggleRow("차량 오디오 연결 시 자동 실행", checked = state.enabled,
                onCheckedChange = controls.onWirelessEnabled)
            SettingsDetails("차량 오디오 선택") { VehicleAudioPicker(settings, controls) }
            if (state.enabled && settings.vehicleAudioAddress.isBlank()) {
                Text("자동 실행할 차량 오디오를 직접 선택해 주세요", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            }
        }
    }
}

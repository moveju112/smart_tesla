package com.wemade.teslamacro.feature.settings

import android.content.Intent
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
    val supported = Build.VERSION.SDK_INT >= 31
    Spacer(Modifier.height(Space.lg))
    HelpTitle("네이버 안심주행 · 실험", "Android 12 이상에서 자체 무선 디버깅으로 별도 화면 실행을 시도해요. " +
        "휴대폰 잠금을 해제하지 않으며 실제 음성 출력은 기기에서 확인해야 해요.\n\n" +
        "Wi-Fi에 연결한 뒤 개발자 옵션에서 무선 디버깅을 켜 주세요. 분할 화면으로 이 앱과 설정을 함께 열고 " +
        "‘페어링 코드로 기기 페어링’의 포트와 코드를 입력해요. 페어링 뒤에는 무선 디버깅 첫 화면의 연결 포트를 입력해 주세요. " +
        "재부팅이나 Wi-Fi 변경으로 꺼지거나 포트가 바뀌면 다시 확인해야 해요.\n\n" +
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
            SettingsDetails("무선 디버깅 연결") {
                TButton("개발자 옵션 열기", ButtonTone.Secondary) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                Spacer(Modifier.height(Space.sm))
                DraftField(pairingPort, { pairingPort = it.filter(Char::isDigit).take(5) }, "페어링 포트",
                    enabled = !state.busy && !state.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(Space.sm))
                DraftField(pairingCode, { pairingCode = it.filter(Char::isDigit).take(6) }, "페어링 코드 6자리",
                    enabled = !state.busy && !state.running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation())
                Spacer(Modifier.height(Space.sm))
                TButton("페어링", enabled = !state.busy && !state.running && pairingCode.length == 6 && pairingPort.isNotBlank()) {
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
                    enabled = !state.busy && !state.running && state.port.isNotBlank(), onClick = controls.onWirelessTest)
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

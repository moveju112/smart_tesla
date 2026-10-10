package com.wemade.teslamacro.feature.settings

import com.wemade.teslamacro.data.nav.NavigatorApp
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.ui.component.*
import com.wemade.teslamacro.ui.theme.*

/** 페어링 코드는 화면 메모리에만 두며 자동 실행은 사용자가 따로 선택한다. */
@Composable
internal fun WirelessNavigationPanel(settings: AppSettings, controls: NavigationControls) {
    val state = controls.wirelessState
    val supported = Build.VERSION.SDK_INT >= 31
    Spacer(Modifier.height(Space.lg))
    HelpTitle("안심주행 · 실험", "네이버 지도·티맵·카카오내비 중 고른 내비의 안심운전을 실행해요. Android 12 이상에서 자체 무선 디버깅으로 별도 화면 실행을 시도해요. " +
        "휴대폰 잠금을 해제하지 않으며 실제 음성 출력은 기기에서 확인해야 해요.\n\n" +
        "‘설정 → 기기 → 권한 점검’에서 무선 ADB 연결을 누르면 개발자 옵션·USB 디버깅·Wi-Fi·알림 중 필요한 설정 화면을 열어요. 설정을 마치고 돌아오면 무선 ADB 연결을 다시 눌러 다음 단계를 확인해 주세요. " +
        "무선 디버깅을 켜고 ‘페어링 코드로 기기 페어링’을 연 다음, 화면을 닫지 말고 알림의 ‘코드 입력’에 6자리를 입력해 주세요. " +
        "IP·포트는 자동으로 찾으며 코드는 저장하지 않아요. 알림 입력을 위해 앱 알림을 허용해 주세요. " +
        "저장된 인증이 있으면 코드 입력 없이 연결을 먼저 시도해요. 자동 탐색이 안 되면 아래 수동 입력을 사용해 주세요.\n\n" +
        "명령을 보내기 전에 꺼진 무선 디버깅을 다시 켜고, 자동 실행을 켠 상태로 차량에 연결되어 있으면 계속 켜 둬요. 연결 해제 30초 뒤 지도 종료를 마치면 꺼요. USB 디버깅도 켜 두고 무선 디버깅에 사용할 Wi-Fi에 연결해 주세요. 케이블 연결은 필요 없어요. 자동 실행을 켜면 재부팅 후 Wi-Fi 연결 때 준비 복구를 시도해요. " +
        "처음 Wi-Fi 허용 창에서 ‘이 네트워크에서 항상 허용’을 선택하면 다음 준비 때 확인을 줄일 수 있어요. 프로세스가 종료되면 Wi-Fi에서 다시 준비해 주세요.\n\n" +
        "‘하차 시 USB 디버깅 끄기 · 탑승 시 켜기’를 켜면 연결 해제 30초 뒤 지도 종료를 마치고 USB 디버깅을 끄고, 다음 차량 연결 때 다시 켜요. 디버깅이 켜져 있으면 막히는 은행 앱을 쓸 때 사용해요. " +
        "다른 앱이 차량 연결에 맞춰 디버깅을 껐다 켜도 차량 연결 중 다시 켜지면 준비를 자동 복구해요. 휴대폰을 재부팅한 뒤에는 Wi-Fi에서 한 번 준비해야 해요.\n\n" +
        "실행 전 선택한 내비의 초기 설정·위치·음량 설정을 마쳐 주세요. 선택한 내비가 이미 실행 중이면 실험을 시작하지 않아요. 최근 앱 목록에만 남은 내비는 실행 중으로 보지 않아요. " +
        "화면이 켜진 상태와 잠긴 상태에서 각각 테스트해 음성을 확인해 주세요.\n\n" +
        "자동 실행은 ‘설정 → 차량 → 탑승 감지 블루투스’에서 직접 선택한 기기 연결을 사용해요. 연결 해제 30초 뒤 실험을 종료해요. " +
        "준비가 끝난 동안에는 ‘탑승 시 내비 안심운전’을 함께 켜 둬도 이 실험만 실행해요. " +
        "종료 시 선택한 내비를 강제 종료하므로 실험 도중 직접 시작한 길안내도 함께 종료돼요. " +
        "이 실험은 이 휴대폰의 선택한 내비에만 명령을 보내요.")
    Spacer(Modifier.height(Space.sm))
    TCard {
        if (!supported) {
            Text("Android 12 이상에서 사용할 수 있어요", style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
        } else {
            if (state.busy || state.running) {
                Spacer(Modifier.height(Space.sm))
                TButton("종료", ButtonTone.Ghost, onClick = controls.onWirelessStop)
            }
            Spacer(Modifier.height(Space.md))
            // 설치된 앱만 고르게 하되, 지운 앱을 고른 상태여도 현재 선택 이름은 보여 준다.
            val apps = NavigatorApp.entries.filter { it.supportsSafeDrive && (it.name in controls.installed || it == state.app) }
            ChoiceSettingRow("내비 앱", apps.map { it.name to it.label }, state.app.name, onSelect = controls.onWirelessApp)
            SettingToggleRow("차량 오디오 연결 시 자동 실행", checked = state.enabled,
                onCheckedChange = rememberPermissionToggle(PermissionFeature.WIRELESS, controls.onWirelessEnabled))
            if (state.enabled && settings.vehicleAudioAddress.isBlank()) {
                Text("설정 → 차량 → 탑승 감지 블루투스에서 기기를 선택해 주세요", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            }
            SettingToggleRow("하차 시 USB 디버깅 끄기 · 탑승 시 켜기", checked = state.toggleUsbDebugging,
                onCheckedChange = controls.onWirelessToggleUsbDebugging)
            SettingsDetails("연결 관리", if (state.prepared) "연결됨" else "준비 필요") {
                TButton("연결 준비 / 복구", ButtonTone.Secondary, enabled = !state.busy && !state.running,
                    onClick = controls.onWirelessPrepare)

            }
            SettingsDetails("실행 점검") {
                TButton("5초 뒤 테스트", ButtonTone.Secondary,
                    enabled = !state.busy && !state.running, onClick = controls.onWirelessTest)
            }
        }
    }
}

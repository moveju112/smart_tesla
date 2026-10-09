package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wemade.teslamacro.data.nav.TeslaNavigationLaunchMode
import com.wemade.teslamacro.data.nav.TeslaShareIntent
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.ui.component.*
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 설명은 제목 도움말에 모으고 설정에는 자동 공유와 실행 경로만 둔다. */
@Composable
internal fun TeslaNavigationPanel(settings: AppSettings, controls: NavigationControls) {
    val context = LocalContext.current
    val installed = rememberOnResume { runCatching { context.packageManager.getPackageInfo(TeslaShareIntent.PACKAGE, 0) }.isSuccess }
    Spacer(Modifier.height(Space.lg))
    HelpTitle("테슬라 내비 연동", "티맵·카카오내비·네이버지도의 길안내가 시작되면 목적지를 공식 테슬라 앱에 공유해요. " +
        "테슬라 앱 설치·로그인과 알림 접근이 필요하고, 네이버·카카오의 화면 목적지를 읽으려면 접근성도 허용해야 해요. " +
        "ADB 우선은 아래 무선 연결 준비가 완료됐을 때 사용하고, 준비되지 않았으면 일반 방식으로 실행해요. " +
        "일반 실행과 주소 입력창은 다른 앱 위에 표시 권한이 필요해요. 휴대폰 잠금은 해제해야 해요. " +
        "원본 좌표는 바로 공유해요. 이름만 있으면 주소를 조회해 내비 경로 거리와 현재 위치로 가장 맞는 한 곳을 자동 공유해요. " +
        "조회 결과가 없으면 현재 시·군·구를 붙인 이름을 테슬라 앱 검색에 넘겨요. 화면에서 목적지를 못 읽었을 때만 주소 입력창을 띄워요. " +
        "공식 앱 전달 후 차량 수신 여부는 차량에서 확인해 주세요. " +
        "임시 테스트 모드는 기본으로 켜져 있어요. 실제 테슬라 앱 공유를 차단하고 테슬라로 보냈을 목적지를 보조창에 표시해요. " +
        "안내가 종료되거나 닫으면 사라져요. 필요한 권한은 설정 → 기기 → 권한 점검에서 허용해 주세요.")
    TCard {
        Column {
            DraftToggle(settings.teslaNavigationShareEnabled, rememberPermissionToggle(PermissionFeature.TESLA_SHARE, controls.onTeslaNavigationShareEnabled), label = "자동 목적지 공유")
            DraftToggle(settings.teslaNavigationTestMode, rememberPermissionToggle(PermissionFeature.TESLA_SHARE, controls.onTeslaNavigationTestMode), label = "테스트 모드 · 차량 전송 안 함")
            if (settings.teslaNavigationShareEnabled || settings.teslaNavigationTestMode) {
                if (!settings.teslaNavigationTestMode) ChoiceSettingRow("실행 방식", TeslaNavigationLaunchMode.entries.map { it.name to it.label },
                    settings.teslaNavigationLaunchMode.name, onSelect = controls.onTeslaNavigationLaunchMode)
                if (!installed && !settings.teslaNavigationTestMode) Text("테슬라 앱을 설치하고 로그인해 주세요", style = MaterialTheme.typography.bodySmall, color = T.Danger)
            }
        }
    }
}

package com.wemade.teslamacro.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.data.nav.NavigatorApp
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.data.update.UpdateState
import com.wemade.teslamacro.data.voice.VoiceModelState
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DiagLogPanel
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.SectionHeader
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.theme.Motion
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T

/**
 * 설정. 폴링 주기가 핵심이다 — 실차에서 방전과 반응속도를 저울질하며 계속 만지게 된다.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onAutomationChange: (Boolean) -> Unit,
    onIdlePollChange: (Int) -> Unit,
    onActivePollChange: (Int) -> Unit,
    onActiveWindowChange: (Int) -> Unit,
    onUnpair: () -> Unit,
    onStartPairing: () -> Unit,
    modifier: Modifier = Modifier,
    simulator: SimulatorControls? = null,
    voice: VoiceControls? = null,
    battery: BatteryControls? = null,
    update: UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onRequestInstallPermission: () -> Unit = {},
    backup: BackupControls? = null,
    navigation: NavigationControls? = null,
    /**
     * 처음 펼칠 칸. 안 주면 상황이 정한다(미등록이면 차량, 아니면 자동화).
     * 특정 칸을 곧바로 보여야 할 때 쓴다 — 스냅샷 검증이 지금의 유일한 사용처다.
     */
    initialGroup: SettingsGroup? = null,
) {
    val compact = LocalPane.current.isCompact
    // 미등록이면 시뮬레이터가 있는 칸을 먼저 펼친다 — 그게 지금 할 일이다.
    // rememberSaveable이라 화면 회전이나 잠깐의 프로세스 종료로 칸이 되돌아가지 않는다
    var group by rememberSaveable(simulator != null, initialGroup) {
        mutableStateOf(
            initialGroup
                ?: if (simulator != null) SettingsGroup.VEHICLE else SettingsGroup.AUTOMATION
        )
    }
    val scroll = rememberScrollState()
    // 칸을 바꾸면 맨 위로 — 스크롤을 공유하니 안 그러면 새 칸의 중간에 떨어진다
    LaunchedEffect(group) { scroll.scrollTo(0) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = Space.lg, vertical = Space.lg),
    ) {
        // 화면 제목을 두지 않는다 — 도면엔 큰 제목이 없고, 어느 시트인지는 좌측 목차가 말한다.
        // 대신 이 시트 안의 목차를 한 줄 둔다. 소분류가 10개까지 늘어 한 장에 다 세우니
        // 스크롤로만 찾게 됐고, 좌우 2단의 좌/우 배분도 기능이 늘면서 무너졌다
        ChoiceRow(
            options = SettingsGroup.entries.map { it.name to it.label },
            selected = group.name,
            onSelect = { picked -> group = SettingsGroup.valueOf(picked) },
        )
        Spacer(Modifier.height(Space.md))
        // 목차와 내용을 가르는 굵은 괘선 — 층은 그림자가 아니라 선으로만 만든다
        Box(Modifier.fillMaxWidth().height(Stroke.bold).background(T.Ink))

        // 넓으면 좌우 2단(설정 시트만의 예외). 자주 만지는 것을 왼쪽에 둔다
        TwoColumns(
            compact = compact,
            left = {
                when (group) {
                    SettingsGroup.DRIVING -> {
                        if (navigation != null) {
                            SectionHeader("길안내", topPadding = Space.md)
                            NavigatorPanel(settings, navigation)
                            SectionHeader("속도 표시")
                            SpeedPanel(settings, navigation)
                        } else {
                            EmptyGroupNote("길안내를 넘길 내비 앱이 이 기기에 없어요.")
                        }
                    }

                    SettingsGroup.AUTOMATION -> {
                        SectionHeader("자동화", topPadding = Space.md)
                        TCard {
                            ToggleRow(
                                title = "매크로 자동 실행",
                                subtitle = "세차·정비 중에는 꺼두세요",
                                checked = settings.automationEnabled,
                                onCheckedChange = onAutomationChange,
                            )
                        }
                    }

                    SettingsGroup.VEHICLE -> {
                        SectionHeader("차량", topPadding = Space.md)
                        VehiclePanel(
                            settings = settings,
                            onUnpair = onUnpair,
                            onStartPairing = onStartPairing,
                        )
                    }

                    SettingsGroup.DEVICE -> {
                        SectionHeader("업데이트", topPadding = Space.md)
                        UpdatePanel(
                            update = update,
                            onCheck = onCheckUpdate,
                            onInstall = onDownloadUpdate,
                            onRequestPermission = onRequestInstallPermission,
                        )

                        if (battery != null) {
                            SectionHeader("절전")
                            BatteryPanel(battery)
                        }
                    }
                }
            },
            right = {
                when (group) {
                    SettingsGroup.DRIVING -> {
                        if (navigation != null) {
                            SectionHeader("과속·단속 안내", topPadding = Space.md)
                            SafeDrivePanel(settings, navigation)
                        }
                    }

                    SettingsGroup.AUTOMATION -> {
                        if (voice != null) {
                            SectionHeader("음성 명령", topPadding = Space.md)
                            VoicePanel(
                                alwaysOn = settings.voiceAlwaysOn,
                                model = voice.model,
                                onAlwaysOnChange = voice.onAlwaysOnChange,
                                onInstall = voice.onInstall,
                                onRemove = voice.onRemove,
                            )
                        }
                    }

                    SettingsGroup.VEHICLE -> {
                        // 상태를 얼마나 자주 읽을지는 차량의 일이다. 이 칸의 오른쪽이
                        // 비어 있으면 도면이 미완성으로 보인다
                        SectionHeader("폴링 주기", topPadding = Space.md)
                        TCard {
                            Text(
                                text = "짧을수록 반응이 빠르지만 차가 잠들지 못해 방전이 빨라져요.\n잠긴 빈 차는 배터리 보호를 위해 항상 2분 주기로 쉬어요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = T.InkFaint,
                            )
                            Spacer(Modifier.height(Space.md))
                            IntervalPicker(
                                // 10초는 차를 못 재워 방전 위험만 키운다 — 최소 15초
                                label = "평상시 (차체 상태만)",
                                current = settings.idlePollSeconds,
                                options = listOf(15, 30, 60, 120),
                                onSelect = onIdlePollChange,
                            )
                            Spacer(Modifier.height(Space.md))
                            IntervalPicker(
                                label = "사건 감지 후 (전체 상태)",
                                current = settings.activePollSeconds,
                                options = listOf(1, 2, 5),
                                onSelect = onActivePollChange,
                            )
                            Spacer(Modifier.height(Space.md))
                            IntervalPicker(
                                label = "집중 폴링 유지 시간",
                                current = settings.activeWindowSeconds,
                                options = listOf(60, 180, 300),
                                onSelect = onActiveWindowChange,
                            )
                        }

                        // 차량 미등록 상태에서만 나온다. 매크로를 실제로 발동시켜볼 유일한 방법
                        if (simulator != null) {
                            SectionHeader("시뮬레이터")
                            SimulatorPanel(
                                insideTemp = simulator.insideTemp,
                                outsideTemp = simulator.outsideTemp,
                                onInsideTempChange = simulator.onInsideTempChange,
                                onOutsideTempChange = simulator.onOutsideTempChange,
                                onBoard = simulator.onBoard,
                                onLeave = simulator.onLeave,
                            )
                        }
                    }

                    SettingsGroup.DEVICE -> {
                        if (backup != null) {
                            SectionHeader("백업", topPadding = Space.md)
                            BackupPanel(backup)
                        }

                        // 실차 문제를 원격으로 전달받는 통로. 공유 버튼은 항상 남긴다.
                        // 줄 목록은 끈다 — 사용자가 읽을 내용이 아니고 여기가 화면을 제일 많이 먹었다.
                        // 공유엔 설정 덤프를 함께 실어 보낸다 — 로그만으론 폴링 주기·토글 상태를 알 수 없다
                        SectionHeader("진단 로그", topPadding = if (backup == null) Space.md else Space.lg)
                        DiagLogPanel(
                            title = null,
                            showLines = false,
                            shareExtra = { settingsDump(settings) },
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(Space.xxl))
    }
}

/**
 * 설정 중분류.
 *
 * 소분류(섹션)가 10개까지 늘면서 한 장에 다 세우니 스크롤로만 찾게 됐고,
 * 좌우 2단의 "자주 만지는 것 / 어쩌다 보는 것" 배분도 기능이 늘어 무너졌다
 * (오른쪽 칸에 업데이트·절전·길안내·백업·차량·음성·진단이 몰렸다).
 * 순서는 주행 중 필요한 것부터다.
 */
enum class SettingsGroup(val label: String) {
    DRIVING("주행"),
    AUTOMATION("자동화"),
    VEHICLE("차량"),
    DEVICE("기기"),
}

/** 칸이 상황 때문에 비었을 때. 빈 화면을 그대로 두면 고장으로 보인다 */
@Composable
private fun EmptyGroupNote(text: String) {
    // 제목 없는 SectionHeader는 빈 줄만 남긴다 — 여백만 띄우고 바로 판을 세운다
    Spacer(Modifier.height(Space.lg))
    TCard {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
    }
}

/** 넓으면 좌우 두 칸, 좁으면 위아래 한 칸. 설정처럼 카드가 줄줄이 쌓이는 화면용 */
@Composable
private fun TwoColumns(
    compact: Boolean,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (compact) {
        Column { left(); right() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Column(modifier = Modifier.weight(1f)) { left() }
            Column(modifier = Modifier.weight(1f)) { right() }
        }
    }
}

/**
 * 절전 제외 안내.
 *
 * 설정돼 있으면 조용한 확인 한 줄, 안 돼 있으면 경고색 + 버튼.
 * 제조사 자체 절전은 표준 인텐트가 없어 코드로 못 켠다 — 어디를 봐야 하는지만 적는다.
 */
@Composable
private fun BatteryPanel(battery: BatteryControls) {
    TCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (battery.unrestricted) {
                    "제한 없음으로 설정돼 있어요.\n매크로 대기와 위치 확인이 밀리지 않아요."
                } else {
                    "절전이 걸려 있어요.\n매크로 대기가 늘어지고 위치·업데이트 확인이 밀릴 수 있어요."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (battery.unrestricted) T.InkFaint else T.WarnText,
                modifier = Modifier.weight(1f),
            )
            if (!battery.unrestricted) {
                Spacer(Modifier.width(Space.md))
                TButton(
                    text = "제한 없음으로",
                    fillWidth = false,
                    small = true,
                    onClick = battery.onOpenSettings,
                )
            }
        }
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))
        Text(
            text = "태블릿 자체 절전은 따로예요. 설정 → 앱 → Smart Tesla에서 " +
                "'자동 시작'과 '백그라운드 실행'도 함께 허용해 주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )
    }
}

/** 현재 버전 표시 + GitHub 최신 릴리스 확인/원클릭 설치 */
@Composable
private fun UpdatePanel(
    update: UpdateState?,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    TCard {
        LabelValueRow(label = "현재 버전", value = com.wemade.teslamacro.BuildConfig.VERSION_NAME)
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (update) {
                    null -> "새 버전이 나왔는지 확인해 보세요."
                    is UpdateState.Checking -> "확인 중…"
                    is UpdateState.UpToDate -> "최신 버전이에요."
                    is UpdateState.Failed -> update.message
                    is UpdateState.NeedsInstallPermission ->
                        "앱 설치 권한이 필요해요.\n허용하고 돌아오면 설치를 자동으로 이어가요."
                    is UpdateState.Available -> "새 버전 ${update.version}이 있어요!"
                    is UpdateState.Downloading -> "내려받는 중… ${update.percent}%"
                    is UpdateState.Installing -> "설치 중…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (update) {
                    is UpdateState.Failed, is UpdateState.NeedsInstallPermission -> T.WarnText
                    is UpdateState.Available, is UpdateState.Downloading,
                    is UpdateState.Installing -> T.Ink
                    else -> T.InkFaint
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.md))
            when (update) {
                // 앱이 스스로를 갈아끼운다. 첫 회만 확인 화면이 뜨고 그 뒤로는 조용히 끝난다
                is UpdateState.Available ->
                    TButton("설치", fillWidth = false, small = true, onClick = onInstall)
                // 진행 중에는 눌러도 할 일이 없다
                is UpdateState.Downloading, is UpdateState.Installing ->
                    TButton("설치", fillWidth = false, small = true, enabled = false, onClick = {})
                // 권한 화면으로 직접 보낸다. 어디서 켜는지 찾게 만들지 않는다
                is UpdateState.NeedsInstallPermission ->
                    TButton(
                        "권한 켜기",
                        fillWidth = false,
                        small = true,
                        onClick = onRequestPermission,
                    )
                else ->
                    TButton(
                        text = "업데이트 확인",
                        tone = ButtonTone.Secondary,
                        fillWidth = false,
                        small = true,
                        enabled = update !is UpdateState.Checking,
                        onClick = onCheck,
                    )
            }
        }

        // 뭐가 바뀌는지 모르고 설치를 누르게 두지 않는다. 릴리스 본문은 이미 받아온 값이다
        val notes = (update as? UpdateState.Available)?.notes
        if (notes != null) {
            Spacer(Modifier.height(Space.sm))
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

/** 라벨 왼쪽, 값 오른쪽 한 줄. 설정 카드의 정보 표시는 이 형태로 통일한다 */
@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = T.InkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = T.Ink,
        )
    }
}

/** 음성 설정에 필요한 값과 콜백 묶음 */
/**
 * 절전 제외 상태와 시스템 다이얼로그로 보내는 길.
 *
 * 상태를 화면이 직접 읽지 않는 이유: 사용자가 시스템 설정에서 바꾸고 돌아오면
 * 다시 읽어야 하는데, 그 시점을 아는 건 호출부(액티비티)뿐이다.
 */
data class BatteryControls(
    val unrestricted: Boolean,
    val onOpenSettings: () -> Unit,
)

/** 길안내를 넘길 내비 앱, HUD 속도 표시, 과속·단속 안내와 그 소리 */
data class NavigationControls(
    val onAppChange: (String) -> Unit,
    val onHudOverlayChange: (Boolean) -> Unit,
    val onSafeDriveChange: (Boolean) -> Unit = {},
    val onSafeDriveSoundChange: (Boolean) -> Unit = {},
    val onSafeDriveVolumeChange: (Int) -> Unit = {},
    /** 앱 키가 있어야 켤 수 있다. 없으면 토글을 잠그고 이유를 적는다 */
    val safeDriveAvailable: Boolean = false,
    /** 이 기기에 실제로 깔려 있는 앱만 고를 수 있다 */
    val installed: Set<String> = emptySet(),
    /**
     * "다른 앱 위에 표시" 권한이 있는가.
     *
     * 없으면 토글을 켜도 창이 안 뜬다 — 켰는데 아무 일도 안 일어나는 스위치가
     * 제일 나쁘다. 그래서 상태를 받아 안내와 버튼을 함께 보여준다.
     */
    val overlayPermitted: Boolean = true,
    val onRequestOverlayPermission: () -> Unit = {},
    /**
     * 위치 권한이 있는가.
     *
     * 안드로이드 12부터 BLE는 위치 권한 없이 돌아서 첫 실행 요청 목록에 위치가 빠졌다.
     * 그런데 HUD 속도도 과속 안내도 GPS가 없으면 **한 글자도 못 띄운다** —
     * 켜도 아무 일이 없던 이유가 이것이라, 주행 기능을 켜는 자리에서 따로 받는다.
     */
    val locationPermitted: Boolean = true,
    val onRequestLocationPermission: () -> Unit = {},
)

/**
 * 위치 권한이 없을 때의 경고 한 줄과 받기 버튼.
 * HUD와 과속 안내가 같은 이유로 죽으므로 둘 다 이걸 쓴다.
 */
@Composable
private fun LocationPermissionNotice(controls: NavigationControls) {
    Spacer(Modifier.height(Space.md))
    Hairline()
    Spacer(Modifier.height(Space.md))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "위치 권한이 없어 속도를 읽지 못해요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.Danger,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.md))
        TButton(
            text = "권한 허용",
            fillWidth = false,
            onClick = controls.onRequestLocationPermission,
        )
    }
}

/** 길안내를 넘길 내비 앱 하나 */
@Composable
private fun NavigatorPanel(settings: AppSettings, controls: NavigationControls) {
    TCard {
        Text(
            text = "매크로의 '지도 안내'를 어느 앱으로 넘길지 고릅니다.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )
        Spacer(Modifier.height(Space.md))
        // 안 깔린 앱을 고르면 매크로가 실행 순간에 실패한다 — 아예 못 고르게 막는다
        val apps = NavigatorApp.entries.filter {
            controls.installed.isEmpty() || it.name in controls.installed
        }
        ChoiceRow(
            options = apps.map { it.name to it.label },
            selected = settings.navigatorApp,
            onSelect = controls.onAppChange,
        )
    }
}

/** 주행 중 속도를 어디에 띄울지 */
@Composable
private fun SpeedPanel(settings: AppSettings, controls: NavigationControls) {
    TCard {
        ToggleRow(
            title = "속도를 다른 앱 위에 표시",
            subtitle = "내비를 띄워도 속도가 보여요. 세우면 사라집니다",
            checked = settings.hudOverlay,
            onCheckedChange = controls.onHudOverlayChange,
        )
        // 권한이 없으면 켜도 창이 안 뜬다. 켰는데 아무 일도 안 일어나면
        // 사용자는 앱이 고장 난 줄 안다 — 여기서 바로 받을 수 있게 한다
        if (settings.hudOverlay && !controls.locationPermitted) {
            LocationPermissionNotice(controls)
        }
        if (settings.hudOverlay && !controls.overlayPermitted) {
            Spacer(Modifier.height(Space.md))
            Hairline()
            Spacer(Modifier.height(Space.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "'다른 앱 위에 표시' 권한이 없어 창이 뜨지 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.Danger,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.md))
                TButton(
                    text = "권한 허용",
                    fillWidth = false,
                    onClick = controls.onRequestOverlayPermission,
                )
            }
        }
    }
}

/** 과속·단속 안내와 그 소리 */
@Composable
private fun SafeDrivePanel(settings: AppSettings, controls: NavigationControls) {
    TCard {
        // 키가 없으면 토글을 아예 안 보여준다 — 눌러도 안 되는 스위치보다
        // 왜 없는지 적힌 한 줄이 낫다
        if (!controls.safeDriveAvailable) {
            Text(
                text = "과속·단속 안내는 카카오내비 앱 키가 있어야 켜져요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
            return@TCard
        }

        ToggleRow(
            title = "안내 받기",
            subtitle = "단속 카메라·구간단속·보호구역을 알려줘요. 주행 중 GPS와 데이터를 씁니다",
            checked = settings.safeDrive,
            onCheckedChange = controls.onSafeDriveChange,
        )

        if (settings.safeDrive && !controls.locationPermitted) {
            LocationPermissionNotice(controls)
        }

        // 안내가 꺼져 있으면 소리 설정은 의미가 없다 — 조작할 수 없는 칸을
        // 흐리게 남겨두는 것보다 접는 게 조용하다
        if (!settings.safeDrive) return@TCard

        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))
        ToggleRow(
            title = "소리로도 알림",
            subtitle = "주행 중엔 화면을 못 볼 때가 있어요. 내비 음성은 끊지 않고 잠깐 낮춥니다",
            checked = settings.safeDriveSound,
            onCheckedChange = controls.onSafeDriveSoundChange,
        )

        if (!settings.safeDriveSound) return@TCard

        Spacer(Modifier.height(Space.md))
        ChoiceRow(
            options = listOf("1" to "작게", "2" to "보통", "3" to "크게"),
            selected = settings.safeDriveVolume.coerceIn(1, 3).toString(),
            onSelect = { controls.onSafeDriveVolumeChange(it.toInt()) },
        )
    }
}

/** 등록된 차량과 등록/해제 */
@Composable
private fun VehiclePanel(
    settings: AppSettings,
    onUnpair: () -> Unit,
    onStartPairing: () -> Unit,
) {
    TCard {
        if (settings.vehicleName.isNotBlank()) {
            LabelValueRow(label = "이름", value = settings.vehicleName)
            Spacer(Modifier.height(Space.sm))
        }
        LabelValueRow(
            label = "VIN",
            value = if (settings.isPaired) settings.vin else "등록된 차량 없음",
        )
        // 다른 기기(태블릿)에 세팅할 때 이 주소를 그대로 입력하면 된다.
        // 폰 블루투스 페어링 목록의 테슬라 주소는 음악용이라 이걸 써야 한다
        if (settings.vehicleAddress.isNotBlank()) {
            Spacer(Modifier.height(Space.sm))
            LabelValueRow(label = "BLE 주소 (키 연결용)", value = settings.vehicleAddress)
        }
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))

        // 등록 해제하면 다시 들어갈 길이 필요하다. 버튼이 상황에 따라 바뀐다
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (settings.isPaired) {
                    "앱에서만 지워져요.\n차량 키는 차량 화면 → 잠금에서 삭제하세요."
                } else {
                    "등록 전에는 가상 차량으로만 동작해요."
                },
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.md))
            if (settings.isPaired) {
                TButton(text = "등록 해제", tone = ButtonTone.Danger, fillWidth = false, onClick = onUnpair)
            } else {
                TButton(text = "등록하기", fillWidth = false, onClick = onStartPairing)
            }
        }
    }
}

/**
 * 매크로·설정 내보내기/되돌리기.
 * 차량 식별자와 키 등록은 백업에 담기지 않는다 — 파일이 밖으로 나가도 차는 안전하다.
 */
data class BackupControls(
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    /** 마지막 시도 결과. 없으면 아무것도 안 뜬다 */
    val message: String? = null,
    val onDismissMessage: () -> Unit = {},
)

@Composable
private fun BackupPanel(backup: BackupControls) {
    TCard {
        Text(
            text = "매크로와 설정을 파일로 내보내고 되돌립니다.\n" +
                "차량 등록과 키는 담기지 않아요 — 기기를 바꾸면 등록은 다시 해야 해요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )
        Spacer(Modifier.height(Space.md))
        Row {
            TButton("내보내기", fillWidth = false, small = true, onClick = backup.onExport)
            Spacer(Modifier.width(Space.sm))
            TButton(
                text = "되돌리기",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                small = true,
                onClick = backup.onImport,
            )
        }
        // 결과는 성공이든 실패든 남긴다 — 조용히 끝나면 됐는지 안 됐는지 알 길이 없다
        backup.message?.let { message ->
            Spacer(Modifier.height(Space.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = T.Ink,
                modifier = Modifier.clickable(onClick = backup.onDismissMessage),
            )
        }
    }
}

data class VoiceControls(
    val model: VoiceModelState,
    val onAlwaysOnChange: (Boolean) -> Unit,
    val onInstall: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * 음성 상시 대기.
 *
 * 모델이 없으면 스위치를 켤 수 없다. 켜지기만 하고 동작은 안 하는 상태가 제일 나쁘다.
 */
@Composable
private fun VoicePanel(
    alwaysOn: Boolean,
    model: VoiceModelState,
    onAlwaysOnChange: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val installed = model is VoiceModelState.Installed
    val installing = model is VoiceModelState.Installing

    TCard {
        ToggleRow(
            title = "상시 대기",
            subtitle = if (installed) "\"테슬라\"로 부른 뒤 말하세요" else "음성 모델을 먼저 설치해 주세요",
            checked = alwaysOn && installed,
            onCheckedChange = { if (installed) onAlwaysOnChange(it) },
        )

        if (installed) {
            Spacer(Modifier.height(Space.md))
            Text(
                text = "예) \"테슬라, 트렁크 열어\"\n나만의 명령어는 그 이름으로 매크로를 만드세요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
            )
            Text(
                text = "차량에 연결된 동안에만 마이크를 켜요.\n들은 말은 기기 밖으로 나가지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
                modifier = Modifier.padding(top = Space.xs),
            )
        }

        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))

        // 모델은 별도 섹션이 아니라 음성 카드의 한 줄. 설정 목록을 짧게 유지한다
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "오프라인 모델",
                    style = MaterialTheme.typography.titleMedium,
                    color = T.Ink,
                )
                Text(
                    text = when (model) {
                        is VoiceModelState.Installed -> "설치됨\n인터넷 없이 동작해요"
                        is VoiceModelState.Installing -> "설치 중… ${model.megabytes}MB"
                        is VoiceModelState.Failed -> model.reason
                        is VoiceModelState.NotInstalled -> "설치되지 않음"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model is VoiceModelState.Failed) T.WarnText else T.InkFaint,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
            Spacer(Modifier.width(Space.md))
            if (installed) {
                TButton(text = "삭제", tone = ButtonTone.Ghost, fillWidth = false, onClick = onRemove)
            } else {
                TButton(
                    text = if (installing) "설치 중…" else "파일 선택",
                    tone = ButtonTone.Secondary,
                    fillWidth = false,
                    enabled = !installing,
                    onClick = onInstall,
                )
            }
        }
        if (!installed && !installing) {
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "vosk-model-small-ko 압축 파일을 받아 두고 여기서 골라 주세요.\n" +
                    "약 250MB를 차지하고, 한 번만 하면 돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

/** 시뮬레이터 조작에 필요한 값과 콜백 묶음. 인자 6개를 화면 시그니처에 늘어놓지 않는다 */
data class SimulatorControls(
    val insideTemp: Double,
    val outsideTemp: Double,
    val onInsideTempChange: (Double) -> Unit,
    val onOutsideTempChange: (Double) -> Unit,
    val onBoard: () -> Unit,
    val onLeave: () -> Unit,
)

/** 주기 선택. 큼직한 버튼 나열 대신 옅은 트랙 위 세그먼트 — 좌석 단계 선택기와 같은 말투다 */
@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.Slate, RoundedCornerShape(Radius.button))
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) T.Ink else Color.Transparent,
                animationSpec = Motion.quick(),
                label = "choiceBackground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 주행 중 태블릿 조작 — 터치 타깃 최소 48dp
                    .height(48.dp)
                    .clip(RoundedCornerShape(Radius.segment))
                    .background(background)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) T.Void else T.InkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun IntervalPicker(
    label: String,
    current: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = T.InkMuted,
            modifier = Modifier.padding(bottom = Space.sm),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(T.Slate, RoundedCornerShape(Radius.button))
                .padding(Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            options.forEach { seconds ->
                val selected = seconds == current
                // 고른 칸은 잉크로 채운다. 예전엔 고른 칸이 더 밝아서
                // 오히려 안 고른 것처럼 보였다 — 매크로 편집의 칩과도 어긋났다
                val background by animateColorAsState(
                    targetValue = if (selected) T.Ink else Color.Transparent,
                    animationSpec = Motion.quick(),
                    label = "intervalBackground",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 주행 중 태블릿 조작 — 터치 타깃 최소 48dp 확보
                        .height(48.dp)
                        // clip을 clickable 앞에 — 리플이 둥근 모서리 밖으로 번지지 않게
                        .clip(RoundedCornerShape(Radius.segment))
                        .background(background)
                        .clickable { onSelect(seconds) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (seconds >= 60) "${seconds / 60}분" else "${seconds}초",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) T.Void else T.InkMuted,
                    )
                }
            }
        }
    }
}

/**
 * 공유용 설정 덤프 한 장.
 * 로그만으론 "폴링이 몇 초였는지, 스텔스가 켜져 있었는지"를 알 수 없어 함께 실어 보낸다.
 * VIN은 개인정보라 앞 3 + 뒤 4만 남기고 가린다.
 */
private fun settingsDump(settings: AppSettings): String = buildString {
    appendLine("[Smart Tesla ${com.wemade.teslamacro.BuildConfig.VERSION_NAME} 설정]")
    appendLine("차량: ${settings.vehicleName.ifBlank { "-" }} · VIN ${maskVin(settings.vin)}")
    appendLine("BLE 주소: ${settings.vehicleAddress.ifBlank { "-" }}")
    appendLine("등록: isPaired=${settings.isPaired} · isEnrolled=${settings.isEnrolled}")
    appendLine(
        "폴링: 평상시 ${settings.idlePollSeconds}초 · 집중 ${settings.activePollSeconds}초" +
            " · 집중 지속 ${settings.activeWindowSeconds}초",
    )
    appendLine(
        "매크로 자동 실행=${settings.automationEnabled}" +
            " · 음성 상시=${settings.voiceAlwaysOn}" +
            " · 스텔스 충전=${settings.stealthCharging}",
    )
    append(
        "내비=${settings.navigatorApp} · HUD 오버레이=${settings.hudOverlay}" +
            " · 과속안내=${settings.safeDrive}" +
            " · 경보소리=${settings.safeDriveSound}(음량 ${settings.safeDriveVolume})",
    )
}

/** VIN 가리기: 5YJ…0000 꼴. 통째로 내보내지 않는다 */
private fun maskVin(vin: String): String =
    if (vin.length < 8) "-" else "${vin.take(3)}…${vin.takeLast(4)}"

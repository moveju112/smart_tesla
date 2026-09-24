package com.wemade.teslamacro.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.wemade.teslamacro.data.charge.StealthChargePlan
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.ThemeMode
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.data.settings.MAX_SMARTTHINGS_COMMAND_TEXT_LENGTH
import com.wemade.teslamacro.data.settings.SmartThingsCommands
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.data.update.UpdateState
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChoiceGrid
import com.wemade.teslamacro.ui.component.DisclosureHeader
import com.wemade.teslamacro.ui.component.SectionTabs
import com.wemade.teslamacro.ui.component.DiagLogPanel
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.HourMinuteStepper
import com.wemade.teslamacro.ui.component.SectionHeader
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 설정. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onAutomationChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onStealthChargingChange: (Boolean) -> Unit = {},
    stealthSecondsUntilNextChange: Int? = null,
    onStealthMaxAmpsChange: (Int) -> Unit = {},
    onStealthMinAmpsChange: (Int?) -> Unit = {},
    chargeHistory: List<com.wemade.teslamacro.data.charge.ChargeBucket> = emptyList(),
    /** 그래프의 "지금". 스냅샷 테스트가 같은 그림을 얻도록 밖에서 넣을 수 있게 둔다 */
    chargeHistoryNowMillis: Long = System.currentTimeMillis(),
    onStealthScheduleEnabledChange: (Boolean) -> Unit = {},
    onStealthStartMinutesChange: (Int) -> Unit = {},
    onStealthEndMinutesChange: (Int) -> Unit = {},
    onProtectPhoneKeyChange: (Boolean) -> Unit = {},
    onDeviceModeChange: (DeviceMode) -> Unit = {},
    onUnpair: () -> Unit,
    onStartPairing: () -> Unit,
    modifier: Modifier = Modifier,
    simulator: SimulatorControls? = null,
    battery: BatteryControls? = null,
    update: UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onRequestInstallPermission: () -> Unit = {},
    backup: BackupControls? = null,
    navigation: NavigationControls? = null,
    smartThings: SmartThingsControls? = null,
    onFleetApiEnabledChange: ((Boolean) -> Unit)? = null,
    fleetCredentials: FleetCredentialControls? = null,
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
            .padding(horizontal = if (compact) Space.md else Space.lg, vertical = Space.md),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
        Spacer(Modifier.height(Space.sm))
        // 탐색은 밑줄로만 표시해 실제 설정값 선택과 구분한다.
        SectionTabs(
            options = SettingsGroup.entries,
            selected = group,
            label = { it.label },
            onSelect = { group = it },
        )
        Spacer(Modifier.height(Space.md))

        // 분류는 고정하고 내용만 스크롤해 긴 설정에서도 이동할 수 있다.
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            // 넓으면 좌우 2단(설정 시트만의 예외). 자주 만지는 것을 왼쪽에 둔다
            TwoColumns(
                compact = compact || group == SettingsGroup.VEHICLE,
                left = {
                    when (group) {
                        SettingsGroup.DRIVING -> {
                            if (navigation != null) {
                                SectionHeader("길안내", topPadding = Space.md)
                                NavigatorPanel(settings, navigation)
                            } else {
                                EmptyGroupNote("길안내를 넘길 내비 앱이 이 기기에 없어요.")
                            }
                        }

                        SettingsGroup.AUTOMATION -> {
                            SectionHeader("매크로", topPadding = Space.sm)
                            TCard {
                                ToggleRow(
                                    title = "매크로 자동 실행",
                                    subtitle = "세차·정비 중에는 꺼두세요",
                                    checked = settings.automationEnabled,
                                    onCheckedChange = onAutomationChange,
                                )
                            }
                            SectionHeader("충전")
                            StealthChargePanel(
                                settings = settings,
                                secondsUntilNextChange = stealthSecondsUntilNextChange,
                                onEnabledChange = onStealthChargingChange,
                                onMaxAmpsChange = onStealthMaxAmpsChange,
                                onMinAmpsChange = onStealthMinAmpsChange,
                                chargeHistory = chargeHistory,
                                chargeHistoryNowMillis = chargeHistoryNowMillis,
                                onScheduleEnabledChange = onStealthScheduleEnabledChange,
                                onStartMinutesChange = onStealthStartMinutesChange,
                                onEndMinutesChange = onStealthEndMinutesChange,
                            )
                        }

                        SettingsGroup.VEHICLE -> {
                            // 연결 설정을 먼저 읽고 등록·해제 카드는 맨 아래에서 찾는다.
                            if (simulator != null) {
                                SectionHeader("시뮬레이터", topPadding = Space.md)
                                SimulatorPanel(
                                    insideTemp = simulator.insideTemp,
                                    outsideTemp = simulator.outsideTemp,
                                    onInsideTempChange = simulator.onInsideTempChange,
                                    onOutsideTempChange = simulator.onOutsideTempChange,
                                    onBoard = simulator.onBoard,
                                    onLeave = simulator.onLeave,
                                )
                            } else if (settings.isPaired) {
                                SectionHeader("연결 안전", topPadding = Space.md)
                                PhoneKeyProtectionPanel(
                                    settings = settings,
                                    onDeviceModeChange = onDeviceModeChange,
                                    onProtectPhoneKeyChange = onProtectPhoneKeyChange,
                                )
                            }
                        }

                        SettingsGroup.DEVICE -> {
                            SectionHeader("화면", topPadding = Space.md)
                            TCard {
                                Text("화면 모드", style = MaterialTheme.typography.titleMedium, color = T.Ink)
                                Spacer(Modifier.height(Space.sm))
                                ChoiceRow(
                                    options = ThemeMode.entries.map { it.name to it.label },
                                    selected = settings.themeMode.name,
                                    onSelect = { onThemeModeChange(ThemeMode.of(it)) },
                                )
                                if (settings.themeMode == ThemeMode.AUTO) {
                                    Text("07~19시 라이트 · 나머지 시간 다크",
                                        style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                                        modifier = Modifier.padding(top = Space.sm))
                                }
                            }
                            SectionHeader("업데이트")
                            UpdatePanel(
                                update = update,
                                onCheck = onCheckUpdate,
                                onInstall = onDownloadUpdate,
                                onRequestPermission = onRequestInstallPermission,
                            )

                            if (battery?.unrestricted == false) {
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
                                SectionHeader("속도 표시", topPadding = Space.md)
                                SpeedPanel(settings, navigation)
                                SectionHeader("단속 안내")
                                SafeDrivePanel(settings, navigation)
                            }
                        }

                        SettingsGroup.AUTOMATION -> {
                            if (smartThings != null) {
                                SectionHeader("음성 명령", topPadding = if (compact) Space.lg else Space.sm)
                                SmartThingsPanel(settings, smartThings)
                            }
                            if (onFleetApiEnabledChange != null) {
                                SectionHeader("음성 명령 전송", topPadding = if (compact || smartThings != null) Space.lg else Space.sm)
                                FleetApiPanel(settings.fleetApiEnabled, onFleetApiEnabledChange, fleetCredentials)
                            }
                        }

                        SettingsGroup.VEHICLE -> {
                            SectionHeader("차량 등록", topPadding = Space.lg)
                            VehiclePanel(
                                settings = settings,
                                onUnpair = onUnpair,
                                onStartPairing = onStartPairing,
                            )
                        }

                        SettingsGroup.DEVICE -> {
                            if (backup != null) {
                                SectionHeader("백업", topPadding = Space.md)
                                BackupPanel(backup)
                            }

                            // 실차 문제를 원격으로 전달받는 통로. 공유 버튼은 항상 남긴다.
                            // 줄 목록은 끈다 — 사용자가 읽을 내용이 아니고 여기가 화면을 제일 많이 먹었다.
                            // 공유엔 설정 덤프를 함께 실어 보낸다 — 로그만으론 토글 상태를 알 수 없다
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
}

/** 제어 화면에서 옮긴 다음 1회 스텔스 충전 설정. */
@Composable
private fun StealthChargePanel(
    settings: AppSettings,
    secondsUntilNextChange: Int?,
    onEnabledChange: (Boolean) -> Unit,
    onMaxAmpsChange: (Int) -> Unit,
    onMinAmpsChange: (Int?) -> Unit,
    chargeHistory: List<com.wemade.teslamacro.data.charge.ChargeBucket>,
    chargeHistoryNowMillis: Long,
    onScheduleEnabledChange: (Boolean) -> Unit,
    onStartMinutesChange: (Int) -> Unit,
    onEndMinutesChange: (Int) -> Unit,
) {
    TCard {
        ToggleRow(
            title = "스텔스 충전 1회",
            subtitle = "다음 충전 1회만 실행하고 완료되면 자동으로 꺼져요",
            checked = settings.stealthCharging,
            onCheckedChange = onEnabledChange,
        )
        // 그래프는 1회 설정과 무관하게 늘 보여준다 — 지난 충전이 어땠는지가 켜기 판단의 근거다
        Spacer(Modifier.height(Space.md))
        ChargeChart(buckets = chargeHistory, nowMillis = chargeHistoryNowMillis)
        if (!settings.stealthCharging) return@TCard

        Spacer(Modifier.height(Space.sm))
        Text(
            text = "충전 중에는 BLE 연결 유지 · 대기 중에는 10분마다 확인",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )
        if (secondsUntilNextChange != null) {
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "다음 전류 변경 · ${formatStealthCountdown(secondsUntilNextChange)} 뒤",
                style = MaterialTheme.typography.labelMedium,
                color = T.Electric,
            )
        }
        Spacer(Modifier.height(Space.md))
        SettingsDetails("충전 설정", stealthSettingsSummary(settings)) {
            Text("최대 전류", style = MaterialTheme.typography.labelLarge, color = T.InkMuted)
            Spacer(Modifier.height(Space.sm))
            com.wemade.teslamacro.ui.component.NumberStepper(
                value = settings.stealthMaxAmps.toDouble(),
                min = 5.0, max = 48.0, step = 1.0, unit = "A",
                onChange = { onMaxAmpsChange(it.toInt()) },
            )
            Text("차량이 허용하는 상한을 넘지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            Spacer(Modifier.height(Space.lg))
            ToggleRow(
                title = "최소 전류 직접 지정",
                subtitle = "끄면 최대 전류의 약 75%로 자동 설정",
                checked = settings.stealthMinAmps != null,
                onCheckedChange = { on ->
                    // 직접 지정도 현재 자동 하한에서 시작해 충전 속도의 급변을 막는다.
                    onMinAmpsChange(if (on) StealthChargePlan.autoMinAmps(5, settings.stealthMaxAmps) else null)
                },
            )
            settings.stealthMinAmps?.let { minAmps ->
                Spacer(Modifier.height(Space.sm))
                com.wemade.teslamacro.ui.component.NumberStepper(
                    value = minAmps.toDouble(),
                    min = 5.0, max = settings.stealthMaxAmps.toDouble(), step = 1.0, unit = "A",
                    onChange = { onMinAmpsChange(it.toInt()) },
                )
            }
            Spacer(Modifier.height(Space.lg))
            ToggleRow(
                title = "시간대 제한",
                subtitle = "선택한 시간 안에서만 전류 조절",
                checked = settings.stealthScheduleEnabled,
                onCheckedChange = onScheduleEnabledChange,
            )
            if (settings.stealthScheduleEnabled) {
                Spacer(Modifier.height(Space.md))
                Text("시작", style = MaterialTheme.typography.labelLarge, color = T.InkMuted)
                Spacer(Modifier.height(Space.sm))
                HourMinuteStepper(settings.stealthStartMinutes, onStartMinutesChange)
                Spacer(Modifier.height(Space.md))
                Text("종료", style = MaterialTheme.typography.labelLarge, color = T.InkMuted)
                Spacer(Modifier.height(Space.sm))
                HourMinuteStepper(settings.stealthEndMinutes, onEndMinutesChange)
                if (settings.stealthStartMinutes == settings.stealthEndMinutes) {
                    Text("시작과 종료가 같으면 하루 종일 적용돼요.",
                        style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                        modifier = Modifier.padding(top = Space.sm))
                }
            }
        }
    }
}

/** 접어도 실제 전류 범위와 자정을 넘는 설정 시간대를 확인할 수 있게 한다. */
internal fun stealthSettingsSummary(settings: AppSettings): String {
    val minimum = settings.stealthMinAmps ?: StealthChargePlan.autoMinAmps(5, settings.stealthMaxAmps)
    val schedule = when {
        !settings.stealthScheduleEnabled -> "시간 제한 없음"
        settings.stealthStartMinutes == settings.stealthEndMinutes -> "하루 종일"
        else -> "%02d:%02d~%02d:%02d".format(
            settings.stealthStartMinutes / 60, settings.stealthStartMinutes % 60,
            settings.stealthEndMinutes / 60, settings.stealthEndMinutes % 60,
        )
    }
    return "$minimum~${settings.stealthMaxAmps}A · $schedule"
}

/** 남은 초를 한눈에 읽히는 분·초 문구로 바꾼다. */
internal fun formatStealthCountdown(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val minutes = safeSeconds / 60
    val remainder = safeSeconds % 60
    return if (minutes > 0) "${minutes}분 ${remainder}초" else "${remainder}초"
}

/** 빈 차에서 앱의 인증 BLE를 놓아 공식 휴대폰 키의 근접 판정을 방해하지 않게 한다. */
@Composable
private fun PhoneKeyProtectionPanel(
    settings: AppSettings,
    onDeviceModeChange: (DeviceMode) -> Unit,
    onProtectPhoneKeyChange: (Boolean) -> Unit,
) {
    TCard {
        Text(
            text = "이 기기 사용 방식",
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
        )
        Text(
            text = "차에 두면 거치 · 들고 다니면 휴대",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.padding(top = Space.xs),
        )
        Spacer(Modifier.height(Space.sm))
        ChoiceRow(
            options = DeviceMode.entries.map { it.name to it.label },
            selected = settings.deviceMode.name,
            onSelect = { onDeviceModeChange(DeviceMode.of(it)) },
        )
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))

        if (settings.deviceMode == DeviceMode.MOUNTED) {
            ToggleRow(
                title = "휴대폰 키 간섭 방지",
                subtitle = "전원이 있어도 빈 차가 확인되면 BLE 연결을 끊어요",
                checked = settings.protectPhoneKey,
                onCheckedChange = onProtectPhoneKeyChange,
            )
        } else {
            Text(
                text = "앱 사용·직접 명령 때만 연결해요. 자동 매크로는 거치 모드 전용이며, 안심운전 자동 시작은 별도 설정을 따라요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
        if (settings.deviceMode == DeviceMode.MOUNTED && settings.protectPhoneKey) {
            Spacer(Modifier.height(Space.md))
            Hairline()
            Spacer(Modifier.height(Space.md))
            Text(
                text = "빈 차에서는 상시 감시·예약 매크로가 멈춰요. 스텔스 충전은 실행 시간대에 연결을 유지해요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

/**
 * 설정 중분류.
 *
 * 소분류(섹션)가 10개까지 늘면서 한 장에 다 세우니 스크롤로만 찾게 됐고,
 * 좌우 2단의 "자주 만지는 것 / 어쩌다 보는 것" 배분도 기능이 늘어 무너졌다
 * (오른쪽 칸에 업데이트·절전·길안내·백업·차량·음성·진단이 몰렸다).
 * 기본 진입점인 자동화부터 주행·차량·기기 순서로 탐색한다.
 */
enum class SettingsGroup(val label: String) {
    AUTOMATION("자동화"),
    DRIVING("주행"),
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
 * 시스템 절전이 걸렸을 때만 경고와 해제 버튼을 보여 준다.
 * 이미 제한이 없으면 해결된 항목을 남기지 않아 기기 설정 화면을 짧게 유지한다.
 */
@Composable
private fun BatteryPanel(battery: BatteryControls) {
    TCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "절전이 걸려 있어요.\n매크로 대기가 늘어지고 위치·업데이트 확인이 밀릴 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.WarnText,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.md))
            TButton(
                text = "제한 없음으로",
                fillWidth = false,
                small = true,
                onClick = battery.onOpenSettings,
            )
        }
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

/** 사용 여부와 토큰 관리만 남겨 설정 카드를 짧게 유지한다. */
@Composable
internal fun FleetApiPanel(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, credentials: FleetCredentialControls? = null) {
    TCard {
        ToggleRow(
            title = "Fleet API 사용",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (credentials != null) {
            Spacer(Modifier.height(Space.md))
            FleetCredentialPanel(credentials)
        }
    }
}

/** 스마트싱스 알림 기반 차량 명령과 시스템 알림 접근 권한을 묶는다. */
data class SmartThingsControls(
    val notificationAccessGranted: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val onCommandTextChange: (String, String) -> Unit,
    val onRequestNotificationAccess: () -> Unit,
    val onValiditySecondsChange: (Int) -> Unit = {},
)

/** 구글 음성에서 넘어온 스마트싱스 알림 문구별로 기존 빠른 차량 동작을 연결한다. */
@Composable
internal fun SmartThingsPanel(
    settings: AppSettings,
    controls: SmartThingsControls,
) {
    var manageCommands by rememberSaveable { mutableStateOf(false) }
    var selectedAction by rememberSaveable { mutableStateOf<String?>(null) }
    var draftText by rememberSaveable { mutableStateOf("") }
    val configured = settings.smartThingsCommandTexts.values.count { it.isNotBlank() }
    TCard {
        ToggleRow(
            title = "스마트싱스 음성 명령",
            subtitle = "알림 문구로 차량 동작 실행",
            checked = settings.smartThingsEnabled,
            onCheckedChange = controls.onEnabledChange,
        )
        if (!settings.smartThingsEnabled) return@TCard
        Spacer(Modifier.height(Space.md))
        if (!controls.notificationAccessGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("명령 수신에 알림 접근 권한이 필요해요.",
                    style = MaterialTheme.typography.bodySmall, color = T.Danger,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.sm))
                TButton("권한 허용", fillWidth = false, small = true, onClick = controls.onRequestNotificationAccess)
            }
            Spacer(Modifier.height(Space.md))
        }
        TButton("명령 관리 · ${configured}개", ButtonTone.Secondary) { manageCommands = true }
        Text("전달한 알림은 삭제돼요. 이미 전송한 명령은 취소할 수 없어요.",
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
            modifier = Modifier.padding(top = Space.sm))
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.sm))
        SettingsDetails("명령 유효시간", "${settings.smartThingsValiditySeconds}초") {
            com.wemade.teslamacro.ui.component.NumberStepper(
                value = settings.smartThingsValiditySeconds.toDouble(),
                min = 10.0, max = 600.0, step = 10.0, unit = "초",
                onChange = { controls.onValiditySecondsChange(it.toInt()) },
            )
            Text("수신 후 유효시간이 지나면 보내지 않은 명령을 취소해요.",
                style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        }
    }
    if (manageCommands) {
        androidx.activity.compose.BackHandler {
            if (selectedAction != null) selectedAction = null else manageCommands = false
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { if (selectedAction != null) selectedAction = null else manageCommands = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            SmartThingsCommandSheet(
                settings = settings,
                selectedAction = selectedAction,
                draftText = draftText,
                onSelect = { selectedAction = it },
                onDraftChange = { draftText = it },
                onSave = controls.onCommandTextChange,
                onDismiss = { manageCommands = false; selectedAction = null },
            )
        }
    }
}

/** 목록과 개별 문구 편집을 분리해 키보드가 떠도 한 명령에 집중한다. */
@Composable
internal fun SmartThingsCommandSheet(
    settings: AppSettings,
    selectedAction: String?,
    draftText: String,
    onSelect: (String?) -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    com.wemade.teslamacro.ui.component.PickerSheet(
        title = if (selectedAction == null) "음성 명령 관리" else "알림 문구 편집",
        onDismiss = onDismiss,
    ) {
        val selected = SmartThingsCommands.all.firstOrNull { it.action == selectedAction }
        if (selected == null) {
            Text("바꿀 동작을 선택하세요. 문구가 없는 동작은 실행하지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            com.wemade.teslamacro.ui.component.PickerList(SmartThingsCommands.all) { command ->
                com.wemade.teslamacro.ui.component.PickerRow(
                    label = command.label,
                    detail = settings.smartThingsCommandTexts[command.action].orEmpty().ifBlank { "사용 안 함" },
                    onClick = {
                        onDraftChange(settings.smartThingsCommandTexts[command.action].orEmpty())
                        onSelect(command.action)
                    },
                )
            }
        } else {
            val duplicate = draftText.isNotBlank() && settings.smartThingsCommandTexts.any {
                it.key != selected.action && it.value.trim() == draftText.trim()
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(selected.label, style = MaterialTheme.typography.titleMedium, color = T.Ink)
                Spacer(Modifier.height(Space.md))
                DraftField(
                    value = draftText,
                    onValueChange = { onDraftChange(it.take(MAX_SMARTTHINGS_COMMAND_TEXT_LENGTH)) },
                    label = "SmartThings가 보낼 알림 문구",
                    isError = duplicate,
                    note = if (duplicate) "다른 동작에서 사용 중인 문구예요" else "알림 한 줄과 정확히 같아야 해요. 비우면 사용하지 않아요.",
                )
                Spacer(Modifier.height(Space.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TButton("취소", ButtonTone.Secondary, modifier = Modifier.weight(1f)) { onSelect(null) }
                    TButton("저장", enabled = !duplicate, modifier = Modifier.weight(1f)) {
                        onSave(selected.action, draftText)
                        onSelect(null)
                    }
                }
            }
        }
    }
}

/** 길안내를 넘길 내비 앱, HUD 속도 표시, 과속·단속 안내와 그 소리 */
data class NavigationControls(
    val onAppChange: (String) -> Unit,
    val onAutoStartSafeDriveChange: (Boolean) -> Unit = {},
    val onOpenTrustedDeviceSettings: () -> Unit = {},
    val onSafeDriveLaunchModeChange: (String) -> Unit = {},
    val onSafeDriveTest: () -> Unit = {},
    val safeDriveTestMessage: String? = null,
    val onHudOverlayChange: (Boolean) -> Unit,
    val onSafeDriveChange: (Boolean) -> Unit = {},
    val onSafeDriveSoundChange: (Boolean) -> Unit = {},
    val onSafeDriveProgressiveSoundChange: (Boolean) -> Unit = {},
    val onSafeDriveVolumeChange: (Int) -> Unit = {},
    val onSafeDriveToleranceChange: (Int) -> Unit = {},
    /** 앱 키가 있어야 켤 수 있다. 없으면 토글을 잠그고 이유를 적는다 */
    val safeDriveAvailable: Boolean = true,
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
    var showTrustedDevicePrompt by rememberSaveable { mutableStateOf(false) }
    TCard {
        ToggleRow(
            title = "탑승 시 안심운전 실행",
            subtitle = "네이버 지도",
            checked = settings.autoStartNavigatorSafeDrive,
            onCheckedChange = { enabled ->
                controls.onAutoStartSafeDriveChange(enabled)
                showTrustedDevicePrompt = enabled
            },
        )
        if (settings.autoStartNavigatorSafeDrive) {
            if (!controls.overlayPermitted) {
                OverlayPermissionNotice(controls)
            }
            Spacer(Modifier.height(Space.md))
            TButton("신뢰 기기 설정 안내", ButtonTone.Secondary) { showTrustedDevicePrompt = true }
            Spacer(Modifier.height(Space.sm))
            SettingsDetails("실행 점검") {
                Text("점검 방식", style = MaterialTheme.typography.labelLarge, color = T.InkMuted)
                Spacer(Modifier.height(Space.sm))
                ChoiceRow(
                    options = com.wemade.teslamacro.data.nav.SafeDriveLaunchMode.entries.map {
                        it.settingValue to it.label
                    },
                    selected = com.wemade.teslamacro.data.nav.SafeDriveLaunchMode
                        .of(settings.navigatorSafeDriveLaunchMode).settingValue,
                    onSelect = controls.onSafeDriveLaunchModeChange,
                )
                Text("전체 진단은 수동 점검에만 적용돼요.",
                    style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                    modifier = Modifier.padding(top = Space.sm))
                Spacer(Modifier.height(Space.md))
                TButton(
                    text = "10초 뒤 실행 점검",
                    tone = ButtonTone.Secondary,
                    enabled = controls.overlayPermitted,
                    onClick = controls.onSafeDriveTest,
                )
                Text("누른 뒤 화면을 잠가 주세요. 인증이 필요하면 직접 잠금을 해제해 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                    modifier = Modifier.padding(top = Space.sm))
            }
            // 점검을 접어도 실행 결과·실패 안내는 가리지 않는다.
            controls.safeDriveTestMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                    modifier = Modifier.padding(top = Space.sm))
            }
        }
    }
    if (showTrustedDevicePrompt) {
        TrustedDevicePrompt(
            onDismiss = { showTrustedDevicePrompt = false },
            onConfirm = {
                showTrustedDevicePrompt = false
                controls.onOpenTrustedDeviceSettings()
            },
        )
    }
}

/** 설정 이동에만 동의를 받고, 취소해도 안심운전 자동 실행은 유지한다. */
@Composable
internal fun TrustedDevicePrompt(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("차량을 신뢰 기기로 설정할까요?") },
        text = {
            Text("차량 블루투스를 신뢰할 수 있는 기기로 등록하면 연결 중 잠금 해제 상태를 유지할 수 있어요.\n\n휴대폰 보안 설정을 여시겠습니까? 'Extend Unlock' 또는 'Smart Lock'에서 차량을 직접 선택해 주세요.\n\n처음에는 직접 잠금을 해제해야 해요. 취소해도 안심운전 자동 실행은 유지됩니다.")
        },
        confirmButton = { TButton("확인", fillWidth = false, small = true, onClick = onConfirm) },
        dismissButton = { TButton("취소", ButtonTone.Ghost, fillWidth = false, small = true, onClick = onDismiss) },
        containerColor = T.Carbon,
        titleContentColor = T.Ink,
        textContentColor = T.InkMuted,
    )
}

/** 배경에서 내비 화면을 띄우는 데 필요한 오버레이 권한 안내 */
@Composable
private fun OverlayPermissionNotice(controls: NavigationControls) {
    Spacer(Modifier.height(Space.md))
    Hairline()
    Spacer(Modifier.height(Space.md))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "'다른 앱 위에 표시' 권한이 없어 자동으로 열 수 없어요.",
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
            OverlayPermissionNotice(controls)
        }
    }
}

/** 과속·단속 안내와 그 소리 */
@Composable
internal fun SafeDrivePanel(settings: AppSettings, controls: NavigationControls) {
    TCard {
        // 목록이 없는 구성에서는 사용할 수 없는 기능을 노출하지 않는다.
        if (!controls.safeDriveAvailable) {
            Text(
                text = "오프라인 단속 목록을 사용할 수 없어요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
            return@TCard
        }

        ToggleRow(
            title = "안내 받기",
            subtitle = if (com.wemade.teslamacro.BuildConfig.ROAD_MATCH_TOKEN.isNotBlank()) {
                "공공데이터·GPS 기반 안내 · 주변 카메라 접근 시 경로 전송"
            } else {
                "공공데이터·GPS 기반 오프라인 안내"
            },
            checked = settings.safeDrive,
            onCheckedChange = controls.onSafeDriveChange,
        )

        Text(
            text = "오인·누락이 있을 수 있어요. 도로 표지를 우선하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkMuted,
        )
        if (com.wemade.teslamacro.BuildConfig.ROAD_MATCH_TOKEN.isNotBlank()) {
            Text(
                "안내를 켜면 약 1km 안에 카메라 후보가 있을 때 GPS 경로를 gps-map.choondoggy.com으로 전송해 도로를 보정해요. 서버 오류·불확실한 결과에서는 오프라인 안내를 유지해요. 단속 도로·방향이 확정되진 않아요.",
                style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
            )
        }
        SettingsDetails("데이터 출처·안내 범위") {
            Text("공공데이터포털 전국무인교통단속카메라표준데이터(data.go.kr/data/15028200/standard.do). 자료 기준일과 번들 수집일은 서로 달라요. 1년 넘은 카메라 자료·6개월 넘은 목록은 주행 화면에 갱신 확인을 표시해요. 반대편·나란한 도로를 오인하거나 새 카메라가 누락될 수 있어요. 단속 방향·모든 도로의 제한속도·구간 평균속도는 알 수 없어요.",
                style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        }
        if (settings.safeDrive && !controls.locationPermitted) {
            LocationPermissionNotice(controls)
        }

        // 안내가 꺼져 있으면 소리 설정은 의미가 없다 — 조작할 수 없는 칸을
        // 흐리게 남겨두는 것보다 접는 게 조용하다
        if (!settings.safeDrive) return@TCard

        Spacer(Modifier.height(Space.md))
        Text("경보 초과속도", style = MaterialTheme.typography.bodyMedium, color = T.Ink)
        com.wemade.teslamacro.ui.component.NumberStepper(
            value = settings.safeDriveToleranceKph.toDouble(),
            min = 0.0, max = 30.0, step = 1.0, unit = "km/h",
            onChange = { controls.onSafeDriveToleranceChange(it.toInt()) },
        )
        Text(
            text = "제한속도 100km/h인 후보는 ${100 + settings.safeDriveToleranceKph}km/h부터 경보해요. 실제 도로 표지를 우선하세요.",
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
        )
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))
        ToggleRow(
            title = "소리로도 알림",
            subtitle = "카메라 앞 과속 시 단발 경고음 · 기기 미디어 음량 적용",
            checked = settings.safeDriveSound,
            onCheckedChange = controls.onSafeDriveSoundChange,
        )

        if (!settings.safeDriveSound) return@TCard

        Spacer(Modifier.height(Space.md))
        ToggleRow(
            title = "과속할수록 빠르게 울리기",
            subtitle = "제한 대비 +10 미만 3초 · +10부터 2초 · +15부터 1초 (끄면 2초 고정)",
            checked = settings.safeDriveProgressiveSound,
            onCheckedChange = controls.onSafeDriveProgressiveSoundChange,
        )
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
        Spacer(Modifier.height(Space.md))
        Hairline()
        Spacer(Modifier.height(Space.md))

        // 등록 해제하면 다시 들어갈 길이 필요하다. 버튼이 상황에 따라 바뀐다
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (settings.isPaired) {
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    text = "등록 전에는 가상 차량으로만 동작해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkFaint,
                    modifier = Modifier.weight(1f),
                )
            }
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
            text = "매크로·설정 백업. 차량 등록과 키는 제외되므로 새 기기에서는 다시 등록해야 해요.",
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

/** 시뮬레이터 조작에 필요한 값과 콜백 묶음. 인자 6개를 화면 시그니처에 늘어놓지 않는다 */
data class SimulatorControls(
    val insideTemp: Double,
    val outsideTemp: Double,
    val onInsideTempChange: (Double) -> Unit,
    val onOutsideTempChange: (Double) -> Unit,
    val onBoard: () -> Unit,
    val onLeave: () -> Unit,
)

/** 큰 글씨에서도 설정값을 자르지 않고 공용 선택기의 행 높이·접근성을 따른다. */
@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    ChoiceGrid(
        options = options,
        selected = options.firstOrNull { it.first == selected },
        label = { it.second },
        onSelect = { onSelect(it.first) },
        columns = options.size.coerceIn(1, 3),
    )
}

/** 드물게 바꾸는 세부 설정은 요약만 남기고 필요할 때 같은 자리에서 펼친다. */
@Composable
private fun SettingsDetails(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        DisclosureHeader(title, expanded, { expanded = !expanded }, subtitle = summary)
        if (expanded) {
            Spacer(Modifier.height(Space.md))
            content()
        }
    }
}

/**
 * 공유용 설정 덤프 한 장.
 * 로그만으론 스텔스 설정 등을 알 수 없어 함께 실어 보낸다.
 * VIN은 개인정보라 앞 3 + 뒤 4만 남기고 가린다.
 */
private fun settingsDump(settings: AppSettings): String = buildString {
    val stealthWindow = "%02d:%02d~%02d:%02d".format(
        settings.stealthStartMinutes / 60,
        settings.stealthStartMinutes % 60,
        settings.stealthEndMinutes / 60,
        settings.stealthEndMinutes % 60,
    )
    appendLine("[Smart Tesla ${com.wemade.teslamacro.BuildConfig.VERSION_NAME} 설정]")
    appendLine("차량: ${settings.vehicleName.ifBlank { "-" }} · VIN ${maskVin(settings.vin)}")
    appendLine("등록: isPaired=${settings.isPaired} · isEnrolled=${settings.isEnrolled}")
    appendLine(
        "스마트싱스 명령=${settings.smartThingsEnabled}" +
            " · 유효시간=${settings.smartThingsValiditySeconds}초" +
            " · 문구=" + SmartThingsCommands.all.joinToString { command ->
                "${command.action}:${settings.smartThingsCommandTexts[command.action].orEmpty().ifBlank { "-" }}"
            },
    )
    appendLine(
        "기기 사용 모드=${settings.deviceMode.label}" +
            " · 매크로 자동 실행=${settings.automationEnabled}" +
            " · 휴대폰 키 간섭 방지=${settings.protectPhoneKey}" +
            " · 스텔스 충전 1회=${settings.stealthCharging}" +
            "(시작=${settings.stealthChargeStarted}, 변경=${settings.stealthChargeModified})" +
            " · 시간대=${settings.stealthScheduleEnabled}($stealthWindow)",
    )
    append(
        "내비=${settings.navigatorApp} · HUD 오버레이=${settings.hudOverlay}" +
            " · 탑승시 내비 안심운전=${settings.autoStartNavigatorSafeDrive}" +
            " · 안심운전 방식=${settings.navigatorSafeDriveLaunchMode}" +
            " · 과속안내=${settings.safeDrive}" +
            " · 경보소리=${settings.safeDriveSound}(음량 ${settings.safeDriveVolume}, 속도별 ${settings.safeDriveProgressiveSound})" +
            " · 경보초과속도=${settings.safeDriveToleranceKph}km/h",
    )
}

/** VIN 가리기: 5YJ…0000 꼴. 통째로 내보내지 않는다 */
private fun maskVin(vin: String): String =
    if (vin.length < 8) "-" else "${vin.take(3)}…${vin.takeLast(4)}"

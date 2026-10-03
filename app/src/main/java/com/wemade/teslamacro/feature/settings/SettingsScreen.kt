package com.wemade.teslamacro.feature.settings

import com.wemade.teslamacro.feature.features.FeatureSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.data.charge.StealthChargePlan
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.FeatureAvailability
import com.wemade.teslamacro.data.settings.ThemeMode
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.data.settings.MAX_SMARTTHINGS_COMMAND_TEXT_LENGTH
import com.wemade.teslamacro.data.settings.SmartThingsCommands
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.data.update.UpdateState
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChoiceGrid
import com.wemade.teslamacro.ui.component.SectionTabs
import com.wemade.teslamacro.ui.component.DiagLogPanel
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.SectionHeader
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.component.SettingRow
import com.wemade.teslamacro.ui.component.SettingToggleRow
import com.wemade.teslamacro.ui.component.SettingActionRow
import com.wemade.teslamacro.ui.component.NumberSettingRow
import com.wemade.teslamacro.ui.component.TimeSettingRow
import com.wemade.teslamacro.ui.component.ChoiceSettingRow
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 설정. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
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
    onSendDestination: (() -> Unit)? = null,
    smartThings: SmartThingsControls? = null,
    onFleetApiEnabledChange: ((Boolean) -> Unit)? = null,
    fleetCredentials: FleetCredentialControls? = null,
    /**
     * 처음 펼칠 칸. 안 주면 상황이 정한다(미등록이면 차량, 아니면 자동화).
     * 기능에서 부족한 준비 항목으로 바로 이동할 때도 사용한다.
     */
    initialGroup: SettingsGroup? = null,
    focusedFeature: FeatureSettings? = null,
    onBackToFeature: (() -> Unit)? = null,
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
    // 설정은 항목을 훑어보고 가끔 바꾸는 화면이라 글자·버튼을 한 단계 작게 해 한 화면에 더 담는다.
    // 대화상자도 같은 합성 트리라 함께 작아진다.
    MaterialTheme(typography = com.wemade.teslamacro.ui.theme.SettingsTypography) {
    CompositionLocalProvider(com.wemade.teslamacro.ui.component.LocalCompactButtons provides true) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) Space.md else Space.lg, vertical = Space.md),
    ) {
        onBackToFeature?.let { back ->
            TButton("기능으로 돌아가기", ButtonTone.Ghost, icon = com.wemade.teslamacro.ui.component.DraftMark.ArrowLeft,
                fillWidth = false, onClick = back)
        }
        Text(focusedFeature?.let { "${it.label} 설정" } ?: "설정", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
        Spacer(Modifier.height(Space.md))
        if (focusedFeature == FeatureSettings.VEHICLE && !settings.isReady) {
            Text("이 기능을 사용하려면 차량 등록과 키 등록을 완료해 주세요.",
                style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
            Spacer(Modifier.height(Space.md))
        }
        // 탐색은 밑줄로만 표시해 실제 설정값 선택과 구분한다.
        if (focusedFeature == null) SectionTabs(
            options = SettingsGroup.entries,
            selected = group,
            label = { it.label },
            onSelect = { group = it },
        )
        Spacer(Modifier.height(Space.md))

        // 분류는 고정하고 내용만 스크롤해 긴 설정에서도 이동할 수 있다.
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            // 넓으면 좌우 2단(설정 시트만의 예외). 자주 만지는 것을 왼쪽에 둔다.
            // 자동화는 충전을 맨 아래에, 주행은 숨긴 기능을 빼면 한 구역뿐이라 기기 칸만 2단으로 둔다.
            TwoColumns(
                compact = compact || group != SettingsGroup.DEVICE,
                left = {
                    when (group) {
                        SettingsGroup.DRIVING -> {
                            onSendDestination?.takeIf { focusedFeature == null }?.let { action ->
                                SectionHeader("목적지 전송", topPadding = Space.sm)
                                TCard {
                                    SettingRow(
                                        label = "연결·수신·유효시간",
                                        onClick = action,
                                    )
                                }
                            }
                            if (navigation == null) {
                                EmptyGroupNote("길안내를 넘길 내비 앱이 이 기기에 없어요.")
                            } else if (FeatureAvailability.NAVIGATOR_SAFE_DRIVE) {
                                SectionHeader("자동 길안내", topPadding = Space.sm)
                                NavigatorPanel(settings, navigation)
                            }
                        }

                        SettingsGroup.AUTOMATION -> {
                            if (smartThings != null && focusedFeature != FeatureSettings.STEALTH_CHARGE) {
                                SectionHeader("스마트싱스", topPadding = Space.sm)
                                TCard { SmartThingsPanel(settings, smartThings, settingsOnly = true) }
                            }
                            if (onFleetApiEnabledChange != null && focusedFeature == null) {
                                SectionHeader("명령 전송", topPadding = if (smartThings != null) Space.lg else Space.sm)
                                TCard { FleetApiPanel(settings.fleetApiEnabled, onFleetApiEnabledChange, fleetCredentials) }
                            }
                        }

                        SettingsGroup.VEHICLE -> {
                            // 차량 등록 전에도 휴대 모드의 탑승 감지 기기를 고를 수 있어야 한다.
                            SectionHeader("사용 방식", topPadding = Space.sm)
                            TCard {
                                PhoneKeyProtectionPanel(
                                    settings = settings,
                                    navigation = navigation,
                                    onDeviceModeChange = onDeviceModeChange,
                                    onProtectPhoneKeyChange = onProtectPhoneKeyChange,
                                )
                            }
                            if (simulator != null) {
                                SectionHeader("시뮬레이터")
                                TCard {
                                    SettingsDetails("탑승 · 온도 시뮬레이터") {
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
                            }
                        }

                        SettingsGroup.DEVICE -> {
                            SectionHeader("화면", topPadding = Space.sm)
                            TCard {
                                SettingsDetails("화면 모드", settings.themeMode.label) {
                                    ChoiceRow(
                                        options = ThemeMode.entries.map { it.name to it.label },
                                        selected = settings.themeMode.name,
                                        onSelect = { onThemeModeChange(ThemeMode.of(it)) },
                                    )
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
                                if (FeatureAvailability.HUD_OVERLAY) {
                                    SectionHeader("실시간 속도", topPadding = Space.lg)
                                    SpeedPanel(settings, navigation)
                                }
                                SectionHeader("단속 안내",
                                    topPadding = if (FeatureAvailability.NAVIGATOR_SAFE_DRIVE || FeatureAvailability.HUD_OVERLAY) Space.lg else Space.sm)
                                TCard { SafeDrivePanel(settings, navigation, settingsOnly = true) }
                            }
                        }

                        SettingsGroup.AUTOMATION -> {
                            if (focusedFeature != FeatureSettings.SMARTTHINGS) {
                                // 자주 만지지 않는 1회 충전 예약이라 음성 명령·전송 경로 아래 맨 끝에 둔다.
                                SectionHeader("충전",
                                    topPadding = if (smartThings != null || onFleetApiEnabledChange != null) Space.lg else Space.sm)
                                TCard {
                                    StealthChargePanel(
                                        settings = settings,
                                        settingsOnly = true,
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
                                }
                        }

                        SettingsGroup.VEHICLE -> {
                            // 제목은 카드 한 줄 안에 들어 있어 별도 구역 제목을 두지 않는다.
                            Spacer(Modifier.height(Space.lg))
                            VehiclePanel(
                                settings = settings,
                                onUnpair = onUnpair,
                                onStartPairing = onStartPairing,
                            )
                        }

                        SettingsGroup.DEVICE -> {
                            if (backup != null) {
                                SectionHeader("백업", topPadding = if (compact) Space.lg else Space.sm)
                                BackupPanel(backup)
                            }

                            // 실차 문제를 원격으로 전달받는 통로. 공유 버튼은 항상 남긴다.
                            // 줄 목록은 끈다 — 사용자가 읽을 내용이 아니고 여기가 화면을 제일 많이 먹었다.
                            // 공유엔 설정 덤프를 함께 실어 보낸다 — 로그만으론 토글 상태를 알 수 없다
                            SectionHeader("진단 로그", topPadding = if (compact || backup != null) Space.lg else Space.sm)
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
    }
}

/** 제어 화면에서 옮긴 다음 1회 스텔스 충전 설정. */
@Composable
internal fun StealthChargePanel(
    settings: AppSettings,
    secondsUntilNextChange: Int?,
    onEnabledChange: (Boolean) -> Unit,
    onMaxAmpsChange: (Int) -> Unit = {},
    onMinAmpsChange: (Int?) -> Unit = {},
    chargeHistory: List<com.wemade.teslamacro.data.charge.ChargeBucket> = emptyList(),
    chargeHistoryNowMillis: Long = System.currentTimeMillis(),
    onScheduleEnabledChange: (Boolean) -> Unit = {},
    onStartMinutesChange: (Int) -> Unit = {},
    onEndMinutesChange: (Int) -> Unit = {},
    settingsOnly: Boolean = false,
    executionOnly: Boolean = false,
) {
    Column {
        ExpandableToggle(
            title = "스텔스 충전 1회 · 전류 자동 조절",
            settingsOnly = settingsOnly, executionOnly = executionOnly,
            checked = settings.stealthCharging,
            onCheckedChange = onEnabledChange,
            summary = stealthSettingsSummary(settings),
            notices = {
                if (settings.stealthCharging && secondsUntilNextChange != null) {
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "다음 전류 변경 · ${formatStealthCountdown(secondsUntilNextChange)} 뒤",
                        style = MaterialTheme.typography.labelMedium,
                        color = T.Electric,
                    )
                }
            },
        ) {
            NumberSettingRow(
                label = "최대 전류",
                value = settings.stealthMaxAmps.toDouble(),
                min = 5.0, max = 48.0, step = 1.0, unit = "A",
                onChange = { onMaxAmpsChange(it.toInt()) },
            )
            SettingToggleRow(
                label = "최소 전류 직접 지정",
                checked = settings.stealthMinAmps != null,
                onCheckedChange = { on ->
                    // 직접 지정도 현재 자동 하한에서 시작해 충전 속도의 급변을 막는다.
                    onMinAmpsChange(if (on) StealthChargePlan.autoMinAmps(5, settings.stealthMaxAmps) else null)
                },
            )
            settings.stealthMinAmps?.let { minAmps ->
                NumberSettingRow(
                    label = "최소 전류",
                    value = minAmps.toDouble(),
                    min = 5.0, max = settings.stealthMaxAmps.toDouble(), step = 1.0, unit = "A",
                    onChange = { onMinAmpsChange(it.toInt()) },
                )
            }
            Spacer(Modifier.height(Space.md))
            Hairline()
            SettingToggleRow(
                label = "시간대 제한",
                checked = settings.stealthScheduleEnabled,
                onCheckedChange = onScheduleEnabledChange,
            )
            if (settings.stealthScheduleEnabled) {
                TimeSettingRow("시작", settings.stealthStartMinutes, onStartMinutesChange)
                TimeSettingRow("종료", settings.stealthEndMinutes, onEndMinutesChange)
            }
            // 충전 기록은 켜짐과 관계없이 상세 화면에서 확인할 수 있다.
            if (recentChargeBuckets(chargeHistory, chargeHistoryNowMillis).isNotEmpty()) {
                Spacer(Modifier.height(Space.lg))
                ChargeChart(buckets = chargeHistory, nowMillis = chargeHistoryNowMillis)
            }
        }
    }
}

/** 상세 렌더링은 검사 대상의 상위 시트만 열고 하위 시트로 전파하지 않는다. */
internal val LocalExpandSettingsDetails = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 상세 시트에서 하위 명령 편집기로 이동할 때 기존 창을 닫는다. */
private val LocalCloseSettingsSheet = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 실행 화면에는 상태 스위치를, 설정 목록에는 옵션 요약과 편집 시트 진입점을 둔다.
 * 변경은 즉시 저장되므로 닫기를 취소나 되돌리기로 표현하지 않는다.
 */
@Composable
private fun ExpandableToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    settingsOnly: Boolean = false,
    executionOnly: Boolean = false,
    notices: @Composable ColumnScope.() -> Unit = {},
    details: @Composable ColumnScope.() -> Unit,
) {
    val expandInitially = LocalExpandSettingsDetails.current
    var expanded by rememberSaveable { mutableStateOf(expandInitially) }
    Column(Modifier.fillMaxWidth()) {
        if (executionOnly) {
            // 실행 화면은 스위치를 아래에 둬 큰 글씨에서도 요약 폭을 빼앗지 않는다.
            Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink)
            summary?.let {
                Spacer(Modifier.height(Space.sm))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
            }
            Spacer(Modifier.height(Space.md))
            com.wemade.teslamacro.ui.component.DraftToggle(
                checked = checked, onCheckedChange = onCheckedChange,
                label = if (checked) "켜짐" else "꺼짐",
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingRow(
                    label = title, value = summary, onClick = { expanded = true },
                    modifier = Modifier.weight(1f),
                )
                if (!settingsOnly) {
                    Spacer(Modifier.width(Space.sm))
                    com.wemade.teslamacro.ui.component.DraftToggle(
                        checked = checked, onCheckedChange = onCheckedChange,
                        label = if (checked) "켬" else "끔",
                    )
                }
            }
        }
        notices()
    }
    if (expanded) {
        com.wemade.teslamacro.ui.component.PickerSheet(
            title = title,
            onDismiss = { expanded = false },
        ) {
            CompositionLocalProvider(
                LocalCloseSettingsSheet provides { expanded = false },
                LocalExpandSettingsDetails provides false,
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    details()
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
    navigation: NavigationControls?,
    onDeviceModeChange: (DeviceMode) -> Unit,
    onProtectPhoneKeyChange: (Boolean) -> Unit,
) {
    Column {
        SettingsDetails("기기 사용 방식", settings.deviceMode.label) {
            ChoiceRow(
                options = DeviceMode.entries.map { it.name to it.label },
                selected = settings.deviceMode.name,
                onSelect = { onDeviceModeChange(DeviceMode.of(it)) },
            )
        }
        if (settings.deviceMode == DeviceMode.MOUNTED && settings.isPaired) {
            Hairline()
            SettingToggleRow(
                label = "빈 차에서 휴대폰 키 간섭 방지",
                checked = settings.protectPhoneKey,
                onCheckedChange = onProtectPhoneKeyChange,
            )
        } else if (settings.deviceMode == DeviceMode.PORTABLE && navigation != null) {
            Hairline()
            val selectedAudioName = if (settings.vehicleAudioAddress.isBlank()) "자동 선택" else {
                navigation.pairedAudioDevices.firstOrNull {
                    it.address.equals(settings.vehicleAudioAddress, ignoreCase = true)
                }?.name ?: "선택 기기 없음"
            }
            SettingsDetails("탑승 감지 블루투스", selectedAudioName) {
                VehicleAudioPicker(settings, navigation)
            }
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
        Text(
            text = "절전 제한 중 · 매크로·위치·업데이트가 지연될 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.WarnText,
        )
        Spacer(Modifier.height(Space.sm))
        TButton(
            text = "제한 없음으로",
            fillWidth = false,
            small = true,
            onClick = battery.onOpenSettings,
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
        Hairline()
        if (update is UpdateState.Failed || update is UpdateState.NeedsInstallPermission) {
            Text(
                text = if (update is UpdateState.Failed) update.message
                    else "앱 설치 권한이 필요해요.\n허용하고 돌아오면 설치를 자동으로 이어가요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.WarnText,
                modifier = Modifier.padding(vertical = Space.sm),
            )
        }
        SettingActionRow(
            label = when (update) {
                is UpdateState.Checking -> "확인 중…"
                is UpdateState.UpToDate -> "최신 버전"
                is UpdateState.Available -> "새 버전 ${update.version}"
                is UpdateState.Downloading -> "내려받는 중… ${update.percent}%"
                is UpdateState.Installing -> "설치 중…"
                else -> "업데이트"
            },
        ) {
            when (update) {
                is UpdateState.Available ->
                    TButton("설치", icon = Icons.Rounded.SystemUpdate,
                        fillWidth = false, small = true, onClick = onInstall)
                is UpdateState.Downloading, is UpdateState.Installing ->
                    TButton("설치", fillWidth = false, small = true, enabled = false, onClick = {})
                is UpdateState.NeedsInstallPermission ->
                    TButton("권한 켜기", fillWidth = false, small = true, onClick = onRequestPermission)
                else ->
                    TButton("확인", ButtonTone.Secondary, icon = Icons.Rounded.SystemUpdate,
                        fillWidth = false, small = true,
                        enabled = update !is UpdateState.Checking, onClick = onCheck)
            }
        }

        // 뭐가 바뀌는지 모르고 설치를 누르게 두지 않는다. 릴리스 본문은 이미 받아온 값이다.
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
    SettingActionRow(label) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
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
    Column {
        ExpandableToggle(
            title = "Fleet API로 명령 전송",
            checked = enabled,
            onCheckedChange = onEnabledChange,
            summary = credentials?.let { if (it.state.stored) "토큰 저장됨" else "토큰 미등록" },
            notices = {
                credentials?.state?.message?.let { message ->
                    Spacer(Modifier.height(Space.sm))
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = if (message.startsWith("연결 확인 완료") || message.startsWith("토큰을 삭제하고") ||
                            message.startsWith("토큰을 암호화")) T.InkMuted else T.Danger)
                }
                if (credentials != null && !credentials.state.stored) {
                    Text("Tesla Client Secret은 입력하지 마세요.",
                        style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                        modifier = Modifier.padding(top = Space.xs))
                }
            },
        ) {
            if (credentials != null) FleetCredentialPanel(credentials)
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
    settingsOnly: Boolean = false,
    executionOnly: Boolean = false,
) {
    var manageCommands by rememberSaveable { mutableStateOf(false) }
    var selectedAction by rememberSaveable { mutableStateOf<String?>(null) }
    var draftText by rememberSaveable { mutableStateOf("") }
    val configured = settings.smartThingsCommandTexts.values.count { it.isNotBlank() }
    Column {
            ExpandableToggle(
                title = "알림으로 차량 명령 실행",
                settingsOnly = settingsOnly, executionOnly = executionOnly,
                checked = settings.smartThingsEnabled,
                onCheckedChange = controls.onEnabledChange,
                summary = "명령 ${configured}개 · 유효 ${settings.smartThingsValiditySeconds}초",
                notices = {
                    // 알림 삭제와 명령 취소 불가 조건, 권한 부족은 접어 둬도 바로 확인할 수 있게 남긴다.
                    if (settings.smartThingsEnabled) {
                        Spacer(Modifier.height(Space.sm))
                        Text("전달한 알림은 삭제돼요. 이미 전송한 명령은 취소할 수 없어요.",
                            style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                    }
                    if ((settingsOnly || settings.smartThingsEnabled) && !controls.notificationAccessGranted) {
                        Spacer(Modifier.height(Space.md))
                        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text("명령 수신에 알림 접근 권한이 필요해요.",
                                style = MaterialTheme.typography.bodySmall, color = T.Danger)
                            TButton("권한 허용", fillWidth = false, small = true, onClick = controls.onRequestNotificationAccess)
                        }
                    }
                },
            ) {
                val closeSettingsSheet = LocalCloseSettingsSheet.current
                SettingRow(
                    label = "음성 명령 관리",
                    value = "${configured}개 설정됨",
                    onClick = {
                        closeSettingsSheet()
                        manageCommands = true
                    },
                )
                Hairline()
                NumberSettingRow(
                    label = "명령 유효시간",
                    value = settings.smartThingsValiditySeconds.toDouble(),
                    min = 10.0, max = 600.0, step = 10.0, unit = "초",
                    onChange = { controls.onValiditySecondsChange(it.toInt()) },
                )
            }
    }
    if (manageCommands) {
        SmartThingsCommandSheet(
            settings = settings,
            selectedAction = selectedAction,
            draftText = draftText,
            onSelect = { selectedAction = it },
            onDraftChange = { draftText = it },
            onSave = controls.onCommandTextChange,
            onDismiss = {
                if (selectedAction != null) selectedAction = null
                else manageCommands = false
            },
        )
    }
}

/** 목록과 개별 문구 편집을 분리해 키보드가 떠도 한 명령에 집중한다. */
@OptIn(ExperimentalLayoutApi::class)
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
            com.wemade.teslamacro.ui.component.PickerList(SmartThingsCommands.all) { command ->
                SettingRow(
                    label = command.label,
                    value = settings.smartThingsCommandTexts[command.action].orEmpty().ifBlank { "사용 안 함" },
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TButton("목록으로", ButtonTone.Ghost, fillWidth = false) { onSelect(null) }
                    TButton("저장", enabled = !duplicate, fillWidth = false) {
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
    val onSafeDriveAlertDistanceChange: (Int) -> Unit = {},
    val onSafeDriveVoiceChange: (Boolean) -> Unit = {},
    val onSafeDriveStartVoiceChange: (Boolean) -> Unit = {},
    val onTestSafeDriveVoice: () -> Unit = {},
    val onOpenSpeechSettings: () -> Unit = {},
    val safeDriveVoiceStatus: String? = null,
    val automaticSoundStatus: String? = null,
    val vehicleAudioStatus: com.wemade.teslamacro.service.VehicleAudioStatus =
        com.wemade.teslamacro.service.VehicleAudioStatus.CHECKING,
    val pairedAudioDevices: List<com.wemade.teslable.BondedDevice> = emptyList(),
    val onSelectVehicleAudioDevice: (String) -> Unit = {},
    val onSafeDriveProgressiveSoundChange: (Boolean) -> Unit = {},
    val onSafeDriveVolumeChange: (Int) -> Unit = {},
    /** 경고음 종류 저장값. 고르는 즉시 그 소리를 들려준다 */
    val onSafeDriveWarningSoundChange: (String) -> Unit = {},
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
    val activityPermitted: Boolean = true,
    val onRequestActivityPermission: () -> Unit = {},
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
    Text(
        text = "위치 권한이 없어 속도를 읽지 못해요.",
        style = MaterialTheme.typography.bodySmall,
        color = T.Danger,
    )
    Spacer(Modifier.height(Space.sm))
    TButton(
        text = "권한 허용",
        fillWidth = false,
        onClick = controls.onRequestLocationPermission,
    )
}

/** 길안내를 넘길 내비 앱 하나 */
@Composable
private fun NavigatorPanel(settings: AppSettings, controls: NavigationControls) {
    var showTrustedDevicePrompt by rememberSaveable { mutableStateOf(false) }
    Column {
        TCard {
            SettingToggleRow(
                label = "탑승 시 네이버 지도 안심운전 실행",
                checked = settings.autoStartNavigatorSafeDrive,
                onCheckedChange = { enabled ->
                    controls.onAutoStartSafeDriveChange(enabled)
                    showTrustedDevicePrompt = enabled
                },
            )
            if (settings.autoStartNavigatorSafeDrive && !controls.overlayPermitted) {
                OverlayPermissionNotice(controls)
            }
            if (settings.autoStartNavigatorSafeDrive) {
                Spacer(Modifier.height(Space.md))
                Hairline()
                Spacer(Modifier.height(Space.md))
                SettingRow(
                    label = "신뢰 기기 설정",
                    onClick = { showTrustedDevicePrompt = true },
                )
                Spacer(Modifier.height(Space.md))
                Hairline()
                Spacer(Modifier.height(Space.md))
                SettingsDetails("실행 점검") {
                    ChoiceSettingRow(
                        label = "점검 방식",
                        options = com.wemade.teslamacro.data.nav.SafeDriveLaunchMode.entries.map {
                            it.settingValue to it.label
                        },
                        selected = com.wemade.teslamacro.data.nav.SafeDriveLaunchMode
                            .of(settings.navigatorSafeDriveLaunchMode).settingValue,
                        onSelect = controls.onSafeDriveLaunchModeChange,
                    )
                    SettingActionRow("10초 뒤 실행 점검") {
                        TButton("실행", ButtonTone.Secondary, fillWidth = false,
                            enabled = controls.overlayPermitted, onClick = controls.onSafeDriveTest)
                    }
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
    Text(
        text = "'다른 앱 위에 표시' 권한이 없어 자동으로 열 수 없어요.",
        style = MaterialTheme.typography.bodySmall,
        color = T.Danger,
    )
    Spacer(Modifier.height(Space.sm))
    TButton(
        text = "권한 허용",
        fillWidth = false,
        onClick = controls.onRequestOverlayPermission,
    )
}

/** 휴대 모드에서 실제 연결 상태와 페어링 차량을 같은 상세 시트에서 고른다. */
@Composable
private fun VehicleAudioPicker(settings: AppSettings, controls: NavigationControls) {
    if (controls.vehicleAudioStatus != com.wemade.teslamacro.service.VehicleAudioStatus.CONNECTED) {
        Text(controls.vehicleAudioStatus.label,
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
    }
    com.wemade.teslamacro.ui.component.PickerRow(
        label = "자동 선택",
        value = if (settings.vehicleAudioAddress.isBlank()) "선택됨" else null,
        onClick = { controls.onSelectVehicleAudioDevice("") },
    )
    if (controls.pairedAudioDevices.isEmpty()) {
        Text("페어링된 블루투스 기기가 없어요.",
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
    } else {
        Hairline()
        com.wemade.teslamacro.ui.component.PickerList(controls.pairedAudioDevices) { device ->
            com.wemade.teslamacro.ui.component.PickerRow(
                label = "${device.name} · ${device.address.takeLast(5)}",
                value = if (settings.vehicleAudioAddress.equals(device.address, ignoreCase = true))
                    "선택됨" else null,
                onClick = { controls.onSelectVehicleAudioDevice(device.address) },
            )
        }
    }
}

/** 주행 중 속도를 어디에 띄울지 */
@Composable
private fun SpeedPanel(settings: AppSettings, controls: NavigationControls) {
    TCard {
        SettingToggleRow(
            label = "다른 앱 위에 실시간 속도 표시",
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
internal fun SafeDrivePanel(
    settings: AppSettings, controls: NavigationControls,
    settingsOnly: Boolean = false, executionOnly: Boolean = false,
) {
    var showLocationTransferPrompt by rememberSaveable { mutableStateOf(false) }
    Column {
            // 목록이 없는 구성에서는 사용할 수 없는 기능을 노출하지 않는다.
            if (!controls.safeDriveAvailable) {
                Text("오프라인 단속 목록을 사용할 수 없어요.",
                    style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
                return@Column
            }
            val soundLabel = com.wemade.teslamacro.data.safety.WarningSound.of(settings.safeDriveWarningSound).label
            ExpandableToggle(
                title = "단속 카메라 안내",
                settingsOnly = settingsOnly, executionOnly = executionOnly,
                checked = settings.safeDrive,
                onCheckedChange = { enabled ->
                    // 서버 전송을 수반하는 빌드에서는 기능을 켜기 전에 반드시 확인받는다.
                    if (enabled && com.wemade.teslamacro.BuildConfig.ROAD_MATCH_TOKEN.isNotBlank()) {
                        showLocationTransferPrompt = true
                    } else {
                        controls.onSafeDriveChange(enabled)
                    }
                },
                summary = "${settings.safeDriveAlertDistanceMeters}m 전 안내 · 초과 +${settings.safeDriveToleranceKph}km/h · " +
                    if (settings.safeDriveSound) "소리 $soundLabel" else "소리 끔",
                notices = {
                    // 서버 전송 고지는 켤 때 확인 창에서 받으므로 스위치 아래에 상시 표시하지 않는다.
                    // 권한 부족은 접어 둬도 안내가 멈춘 이유라 항상 보인다.
                    if ((settingsOnly || settings.safeDrive) && !controls.locationPermitted) {
                        LocationPermissionNotice(controls)
                    }
                    if ((settingsOnly || settings.safeDrive) && settings.safeDriveSound &&
                        settings.deviceMode == DeviceMode.MOUNTED && !controls.activityPermitted) {
                        Spacer(Modifier.height(Space.md))
                        Text("활동 인식 권한이 없어 자동 카메라 소리가 보류돼요.",
                            style = MaterialTheme.typography.bodySmall, color = T.Danger)
                        Spacer(Modifier.height(Space.sm))
                        TButton("권한 허용", fillWidth = false, onClick = controls.onRequestActivityPermission)
                    }
                    if (settings.safeDrive && settings.safeDriveSound) {
                        controls.automaticSoundStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                                modifier = Modifier.padding(top = Space.xs))
                        }
                    }
                    if (settings.safeDrive && (settings.safeDriveVoice || settings.safeDriveStartVoice)) {
                        controls.safeDriveVoiceStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                                modifier = Modifier.padding(top = Space.xs))
                        }
                    }
                },
            ) {
                // 현재 값은 행에 남기고 바꿀 항목만 선택 시트에서 편집한다.
                ChoiceSettingRow(
                    label = "카메라 안내 시작 거리",
                    options = listOf("300" to "300m", "500" to "500m", "700" to "700m"),
                    selected = settings.safeDriveAlertDistanceMeters.toString(),
                    onSelect = { controls.onSafeDriveAlertDistanceChange(it.toInt()) },
                )
                NumberSettingRow(
                    label = "경보 초과속도",
                    value = settings.safeDriveToleranceKph.toDouble(),
                    min = 0.0, max = 30.0, step = 1.0, unit = "km/h",
                    onChange = { controls.onSafeDriveToleranceChange(it.toInt()) },
                )
                Spacer(Modifier.height(Space.md))
                Hairline()
                SettingToggleRow(
                    label = "과속 경고음·음성 안내",
                    checked = settings.safeDriveSound,
                    onCheckedChange = controls.onSafeDriveSoundChange,
                )
                if (settings.safeDriveSound) {
                    WarningSoundPicker(settings, controls)
                    ChoiceSettingRow(
                        label = "경고음 크기",
                        options = listOf("1" to "작게", "2" to "보통", "3" to "크게"),
                        selected = settings.safeDriveVolume.coerceIn(1, 3).toString(),
                        onSelect = { controls.onSafeDriveVolumeChange(it.toInt()) },
                    )
                    SettingToggleRow(
                        label = "과속 정도에 따라 경고음 간격 조절",
                        checked = settings.safeDriveProgressiveSound,
                        onCheckedChange = controls.onSafeDriveProgressiveSoundChange,
                    )
                    Spacer(Modifier.height(Space.md))
                    Hairline()
                    SettingToggleRow(
                        label = "카메라 접근 음성 안내",
                        checked = settings.safeDriveVoice,
                        onCheckedChange = controls.onSafeDriveVoiceChange,
                    )
                    SettingToggleRow(
                        label = "안내 시작 음성",
                        checked = settings.safeDriveStartVoice,
                        onCheckedChange = controls.onSafeDriveStartVoiceChange,
                    )
                    Text("안내가 시작되면 ‘안전운전하세요’를 한 번 말해요.",
                        style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                        modifier = Modifier.padding(top = Space.xs))
                    if (settings.safeDriveVoice || settings.safeDriveStartVoice) {
                        SettingActionRow("음성 점검") {
                            TButton("재생", ButtonTone.Secondary, fillWidth = false,
                                onClick = controls.onTestSafeDriveVoice)
                        }
                        SettingRow("음성 설정", onClick = controls.onOpenSpeechSettings)
                    }
                }
            }
    }
    if (showLocationTransferPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLocationTransferPrompt = false },
            title = { Text("GPS 경로 전송 확인") },
            text = { Text("카메라 근처에서 GPS 경로를 gps-map.choondoggy.com에 전송해요.") },
            confirmButton = {
                TButton("켜기", fillWidth = false, small = true, onClick = {
                    showLocationTransferPrompt = false
                    controls.onSafeDriveChange(true)
                })
            },
            dismissButton = {
                TButton("취소", ButtonTone.Ghost, fillWidth = false, small = true,
                    onClick = { showLocationTransferPrompt = false })
            },
            containerColor = T.Carbon,
            titleContentColor = T.Ink,
            textContentColor = T.InkMuted,
        )
    }
}

/** 경고음 종류를 모달에서 고른다. 누를 때마다 그 소리를 들려주고 창은 열어 둬 여러 소리를 이어서 비교하게 한다. */
@Composable
private fun WarningSoundPicker(settings: AppSettings, controls: NavigationControls) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val selected = com.wemade.teslamacro.data.safety.WarningSound.of(settings.safeDriveWarningSound)
    SettingRow(
        label = "경고음 종류",
        value = selected.label,
        onClick = { showPicker = true },
    )
    if (showPicker) {
        WarningSoundSheet(selected, controls.onSafeDriveWarningSoundChange) { showPicker = false }
    }
}

/** 모달 본문. 화면 상태 없이 그려 스냅샷으로 목록·선택 표시를 검증한다. */
@Composable
internal fun WarningSoundSheet(
    selected: com.wemade.teslamacro.data.safety.WarningSound,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    com.wemade.teslamacro.ui.component.PickerSheet(title = "경고음 종류", onDismiss = onDismiss) {
        Text("누르면 실제 경고음을 들려줘요. 마음에 드는 소리를 고르고 닫으세요.",
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        Spacer(Modifier.height(Space.sm))
        com.wemade.teslamacro.ui.component.PickerList(com.wemade.teslamacro.data.safety.WarningSound.entries) { sound ->
            com.wemade.teslamacro.ui.component.PickerRow(
                label = sound.label,
                value = if (sound == selected) "선택됨" else null,
                onClick = { onSelect(sound.settingValue) },
            )
        }
    }
}

/** 차량 식별값과 등록 동작을 한 그룹에 두고 좁은 화면에서는 VIN만 줄인다. */
@Composable
private fun VehiclePanel(
    settings: AppSettings,
    onUnpair: () -> Unit,
    onStartPairing: () -> Unit,
) {
    TCard {
        SettingActionRow("차량 등록") {
            if (settings.isPaired) {
                TButton("해제", ButtonTone.Danger, fillWidth = false, onClick = onUnpair)
            } else {
                TButton("등록", fillWidth = false, onClick = onStartPairing)
            }
        }
        Text(
            text = if (settings.isPaired) settings.vin else "등록된 차량 없음",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkMuted,
            modifier = Modifier.padding(top = Space.xs),
        )
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

/** 백업 동작은 이름 옆에 두고 제외 항목과 실행 결과는 아래에 남긴다. */
@Composable
internal fun BackupPanel(backup: BackupControls) {
    TCard {
        SettingActionRow("백업 내보내기") {
            TButton("내보내기", icon = Icons.Rounded.Backup,
                fillWidth = false, small = true, onClick = backup.onExport)
        }
        Hairline()
        SettingActionRow("백업 가져오기") {
            TButton("가져오기", ButtonTone.Secondary, icon = Icons.Rounded.Restore,
                fillWidth = false, small = true, onClick = backup.onImport)
        }
        Text(
            text = "차량 등록·키는 제외돼요. 새 기기에서는 다시 등록해야 해요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.padding(top = Space.sm),
        )
        // 결과는 성공이든 실패든 남긴다 — 조용히 끝나면 됐는지 안 됐는지 알 길이 없다
        backup.message?.let { message ->
            Spacer(Modifier.height(Space.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("되돌리지 못했어요") || message.startsWith("내보내지 못했어요")) T.Danger else T.Ink,
            )
            TButton("알림 닫기", tone = ButtonTone.Ghost, fillWidth = false, small = true,
                onClick = backup.onDismissMessage)
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

/** 드문 설정은 현재 값을 목록에 남기고 입력만 상세 시트에서 바꾼다. */
@Composable
private fun SettingsDetails(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val expandInitially = LocalExpandSettingsDetails.current
    var expanded by rememberSaveable { mutableStateOf(expandInitially) }
    SettingRow(
        label = title, value = summary, onClick = { expanded = true },
    )
    if (expanded) {
        com.wemade.teslamacro.ui.component.PickerSheet(
            title = title,
            onDismiss = { expanded = false },
        ) {
            CompositionLocalProvider(LocalExpandSettingsDetails provides false) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
            }
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
            " · 경보소리=${settings.safeDriveSound}(${settings.safeDriveWarningSound}, 음량 ${settings.safeDriveVolume}, 속도별 ${settings.safeDriveProgressiveSound}, 접근 음성 ${settings.safeDriveVoice}, 시작 음성 ${settings.safeDriveStartVoice})" +
            " · 경보거리=${settings.safeDriveAlertDistanceMeters}m · 경보초과속도=${settings.safeDriveToleranceKph}km/h",
    )
}

/** VIN 가리기: 5YJ…0000 꼴. 통째로 내보내지 않는다 */
private fun maskVin(vin: String): String =
    if (vin.length < 8) "-" else "${vin.take(3)}…${vin.takeLast(4)}"

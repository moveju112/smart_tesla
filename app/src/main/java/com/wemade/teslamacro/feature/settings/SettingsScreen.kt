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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    backup: BackupControls? = null,
) {
    val compact = LocalPane.current.isCompact
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.lg),
    ) {
        // 화면 제목을 두지 않는다 — 도면엔 큰 제목이 없고, 어느 시트인지는 좌측 목차가 말한다
        // 가로 태블릿에서 한 칸으로 쌓으면 폭의 절반이 비고 스크롤만 길어진다.
        // 자주 만지는 것(자동화·폴링)을 왼쪽에, 어쩌다 보는 것(차량·업데이트)을 오른쪽에 둔다
        TwoColumns(
            compact = compact,
            left = {
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

        SectionHeader("자동화")
        TCard {
            ToggleRow(
                title = "매크로 자동 실행",
                subtitle = "세차·정비 중에는 꺼두세요",
                checked = settings.automationEnabled,
                onCheckedChange = onAutomationChange,
            )
        }

        SectionHeader("폴링 주기")
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

            },
            right = {
        SectionHeader("업데이트")
        UpdatePanel(update = update, onCheck = onCheckUpdate, onInstall = onDownloadUpdate)

        if (battery != null) {
            SectionHeader("절전")
            BatteryPanel(battery)
        }

        if (backup != null) {
            SectionHeader("백업")
            BackupPanel(backup)
        }

        SectionHeader("차량")
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

        if (voice != null) {
            SectionHeader("음성")
            VoicePanel(
                alwaysOn = settings.voiceAlwaysOn,
                model = voice.model,
                onAlwaysOnChange = voice.onAlwaysOnChange,
                onInstall = voice.onInstall,
                onRemove = voice.onRemove,
            )
        }

        // 실차 문제를 원격으로 전달받는 통로. 공유 버튼은 항상 남긴다.
        // 줄 목록은 끈다 — 사용자가 읽을 내용이 아니고 여기가 화면을 제일 많이 먹었다.
        // 공유엔 설정 덤프를 함께 실어 보낸다 — 로그만으론 폴링 주기·토글 상태를 알 수 없다
        // 제목은 다른 섹션과 같이 카드 밖 SectionHeader로 — 패널 내부 제목은 끈다
        SectionHeader("진단 로그")
        DiagLogPanel(
            title = null,
            showLines = false,
            shareExtra = { settingsDump(settings) },
        )

            },
        )

        Spacer(Modifier.height(Space.xxl))
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
private fun UpdatePanel(update: UpdateState?, onCheck: () -> Unit, onInstall: () -> Unit) {
    val context = LocalContext.current
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
                        "앱 설치 권한이 필요해요.\n한 번만 켜주면 다음부터는 저절로 끝나요."
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
                    TButton("권한 켜기", fillWidth = false, small = true) {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
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
    append(
        "매크로 자동 실행=${settings.automationEnabled}" +
            " · 음성 상시=${settings.voiceAlwaysOn}" +
            " · 스텔스 충전=${settings.stealthCharging}",
    )
}

/** VIN 가리기: 5YJ…0000 꼴. 통째로 내보내지 않는다 */
private fun maskVin(vin: String): String =
    if (vin.length < 8) "-" else "${vin.take(3)}…${vin.takeLast(4)}"

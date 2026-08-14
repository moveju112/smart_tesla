package com.wemade.teslamacro.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    onUnpair: () -> Unit,
    onStartPairing: () -> Unit,
    modifier: Modifier = Modifier,
    simulator: SimulatorControls? = null,
    voice: VoiceControls? = null,
    update: UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.lg),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, color = T.Ink)

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

        SectionHeader("폴링 주기")
        TCard {
            Text(
                text = "짧을수록 반응이 빠르지만 차가 잠들지 못해 방전이 빨라져요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
            Spacer(Modifier.height(Space.md))
            IntervalPicker(
                label = "평상시 (차체 상태만)",
                current = settings.idlePollSeconds,
                options = listOf(10, 30, 60, 120),
                onSelect = onIdlePollChange,
            )
            Spacer(Modifier.height(Space.md))
            IntervalPicker(
                label = "사건 감지 후 (전체 상태)",
                current = settings.activePollSeconds,
                options = listOf(1, 2, 5, 10),
                onSelect = onActivePollChange,
            )
        }

        // 실차 문제를 원격으로 전달받는 통로. 항상 노출한다.
        // 공유엔 설정 덤프를 함께 실어 보낸다 — 로그만으론 폴링 주기·토글 상태를 알 수 없다
        // 제목은 다른 섹션과 같이 카드 밖 SectionHeader로 — 패널 내부 제목은 끈다
        SectionHeader("진단 로그")
        DiagLogPanel(title = null, shareExtra = { settingsDump(settings) })

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

        SectionHeader("업데이트")
        UpdatePanel(update = update, onCheck = onCheckUpdate)

        Spacer(Modifier.height(Space.xxl))
    }
}

/** 현재 버전 표시 + GitHub 최신 릴리스 확인/다운로드 */
@Composable
private fun UpdatePanel(update: UpdateState?, onCheck: () -> Unit) {
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
                    is UpdateState.Failed -> "확인에 실패했어요.\n인터넷 연결을 봐주세요."
                    is UpdateState.Available -> "새 버전 ${update.version}이 있어요!"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (update) {
                    is UpdateState.Failed -> T.WarnText
                    is UpdateState.Available -> T.Ink
                    else -> T.InkFaint
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.md))
            if (update is UpdateState.Available) {
                // 브라우저가 APK를 바로 내려받는다. 받은 뒤 알림에서 설치
                TButton("다운로드", fillWidth = false, small = true) {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(update.apkUrl),
                        )
                    )
                }
            } else {
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
                val background by animateColorAsState(
                    targetValue = if (selected) T.Carbon else Color.Transparent,
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
                        color = if (selected) T.Ink else T.InkMuted,
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

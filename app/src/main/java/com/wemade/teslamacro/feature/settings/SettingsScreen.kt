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

        // 실차 문제를 원격으로 전달받는 통로. 항상 노출한다
        Spacer(Modifier.height(Space.md))
        DiagLogPanel()

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
            Spacer(Modifier.height(Space.md))
            Hairline()
            Spacer(Modifier.height(Space.md))

            // 등록 해제하면 다시 들어갈 길이 필요하다. 버튼이 상황에 따라 바뀐다
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (settings.isPaired) {
                        "앱에서만 지워져요. 차량 키는 차량 화면 → 잠금에서 삭제하세요."
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
                    is UpdateState.Failed -> "확인에 실패했어요. 인터넷 연결을 봐주세요."
                    is UpdateState.Available -> "새 버전 ${update.version}이 있어요!"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (update) {
                    is UpdateState.Failed -> T.Warn
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
                text = "예) \"테슬라, 트렁크 열어\" · 나만의 명령어는 그 이름으로 매크로를 만드세요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
            )
            Text(
                text = "차량에 연결된 동안에만 마이크를 켜요. 들은 말은 기기 밖으로 나가지 않아요.",
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
                        is VoiceModelState.Installed -> "설치됨 · 인터넷 없이 동작해요"
                        is VoiceModelState.Installing -> "설치 중… ${model.megabytes}MB"
                        is VoiceModelState.Failed -> model.reason
                        is VoiceModelState.NotInstalled -> "설치되지 않음"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model is VoiceModelState.Failed) T.Warn else T.InkFaint,
                    modifier = Modifier.padding(top = 2.dp),
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
                text = "vosk-model-small-ko 압축 파일을 받아 두고 여기서 골라 주세요. " +
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
                    targetValue = if (selected) Color.White else Color.Transparent,
                    animationSpec = Motion.quick(),
                    label = "intervalBackground",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(background, RoundedCornerShape(Radius.button - Space.xs))
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

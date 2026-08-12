package com.wemade.teslamacro.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.wemade.teslable.TeslaBleSpec
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DiagLogPanel
import com.wemade.teslamacro.ui.component.StatusPill
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 등록 절차 3단계. 사용자는 지금 어디쯤인지 항상 알아야 한다 */
enum class PairingStep(val title: String, val hint: String) {
    EnterVin(
        "차량 식별번호 입력",
        "차량 화면 → 제어 → 소프트웨어, 또는 테슬라 앱에서 확인할 수 있어요",
    ),
    FindVehicle(
        "차량 검색",
        "블루투스가 닿아야 해요. 차에 탄 상태에서 진행하세요",
    ),
    TapCard(
        "카드키 태그",
        "센터콘솔에 카드키를 대고 차량 화면에서 확인을 누르세요",
    ),
    Done("등록 완료", "이제 매크로가 동작해요"),
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    onVinChange: (String) -> Unit,
    onFindVehicle: () -> Unit,
    onRequestEnrollment: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onScanNearby: () -> Unit = {},
    onLoadBonded: () -> Unit = {},
    onConnectDirect: (String) -> Unit = {},
) {
    val compact = LocalPane.current.isCompact

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) Space.lg else Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
    ) {
        Column(modifier = Modifier.widthIn(max = 520.dp)) {

            Text(
                text = "차량 등록",
                style = MaterialTheme.typography.headlineLarge,
                color = T.Ink,
            )

            Spacer(Modifier.height(Space.md))
            BoardingNotice()

            // 페어링 목록에서 차를 찾았으면 알려준다. 별칭이 곧 내 차라는 확인이다
            if (state.detectedName != null && state.step == PairingStep.EnterVin) {
                Spacer(Modifier.height(Space.md))
                DetectedVehicleNotice(state.detectedName)
            }

            Spacer(Modifier.height(Space.lg))
            StepIndicator(state.step)
            Spacer(Modifier.height(Space.lg))

            TCard {
                OutlinedTextField(
                    value = state.vin,
                    onValueChange = onVinChange,
                    label = { Text("VIN (17자)") },
                    singleLine = true,
                    enabled = state.step == PairingStep.EnterVin,
                    isError = state.vin.isNotEmpty() && !state.isVinValid,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T.Electric,
                        unfocusedBorderColor = T.Hairline,
                        focusedTextColor = T.Ink,
                        unfocusedTextColor = T.Ink,
                        cursorColor = T.Electric,
                        focusedLabelColor = T.Electric,
                        unfocusedLabelColor = T.InkFaint,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(Space.md))
                OpenTeslaAppButton(enabled = state.step == PairingStep.EnterVin)

                Spacer(Modifier.height(Space.md))
                VinPrivacyNotice()
            }

            if (state.message != null) {
                Spacer(Modifier.height(Space.md))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isError) T.Danger else T.InkMuted,
                )
            }

            // 못 찾았을 때만 나온다. 차가 안 보이는 건지 VIN이 다른 건지 가려준다
            if (state.isError && state.step == PairingStep.FindVehicle) {
                Spacer(Modifier.height(Space.md))
                NearbyPanel(
                    nearby = state.nearby,
                    busy = state.isBusy,
                    onScan = onScanNearby,
                    onLoadBonded = onLoadBonded,
                )
                Spacer(Modifier.height(Space.md))
                DirectConnectPanel(busy = state.isBusy, onConnect = onConnectDirect)
                Spacer(Modifier.height(Space.md))
                DiagLogPanel()
            }

            Spacer(Modifier.height(Space.lg))
            PrimaryActions(compact) {
                TButton(
                    text = state.primaryLabel,
                    enabled = state.isPrimaryEnabled,
                    modifier = if (compact) Modifier.fillMaxWidth() else Modifier.weight(1f),
                    onClick = {
                        when (state.step) {
                            PairingStep.EnterVin -> onFindVehicle()
                            PairingStep.FindVehicle -> onFindVehicle()
                            PairingStep.TapCard -> onRequestEnrollment()
                            PairingStep.Done -> onSkip()
                        }
                    },
                )
                TButton(
                    text = "나중에",
                    tone = ButtonTone.Ghost,
                    fillWidth = compact,
                    onClick = onSkip,
                )
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

/**
 * 차에 타서 진행하라는 안내.
 *
 * BLE는 수 미터 안에서만 닿는다. 집에서 VIN만 넣고 "안 된다"고 하는 걸 미리 막는다.
 */
@Composable
private fun BoardingNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.ElectricFaint, RoundedCornerShape(Radius.button))
            .padding(horizontal = Space.md, vertical = Space.md),
    ) {
        Column {
            Text(
                text = "차량에 탑승한 뒤 진행하세요",
                style = MaterialTheme.typography.titleSmall,
                color = T.Ink,
            )
            Text(
                text = "블루투스 조회, 카드키 태그가 필요합니다",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/**
 * 페어링 목록에서 감지한 차를 알려주는 카드.
 *
 * 별칭이 곧 "이 폰이 이미 이 차를 안다"는 뜻이다.
 * VIN은 여기서 못 얻으니 여전히 입력이 필요하다는 것도 같이 안내한다.
 */
@Composable
private fun DetectedVehicleNotice(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.ElectricFaint, RoundedCornerShape(Radius.button))
            .padding(Space.md),
    ) {
        Column {
            Text(
                text = "이 폰에 연결된 차: $name",
                style = MaterialTheme.typography.titleSmall,
                color = T.Ink,
            )
            Text(
                text = "블루투스에 저장된 이름이에요. 제어를 위해 아래에 VIN을 넣어 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/**
 * 테슬라 앱으로 건너가는 버튼.
 *
 * VIN은 차량 화면이나 테슬라 앱에 있다. 앱을 직접 찾아 들어가게 두면
 * 등록 흐름이 거기서 끊긴다.
 */
@Composable
private fun OpenTeslaAppButton(enabled: Boolean) {
    val context = LocalContext.current
    val installed = remember { TeslaAppLauncher.isInstalled(context) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column {
        TButton(
            text = if (installed) "테슬라 앱에서 VIN 확인" else "테슬라 앱 설치",
            tone = ButtonTone.Secondary,
            enabled = enabled,
            onClick = {
                notice = if (TeslaAppLauncher.open(context)) null
                else "테슬라 앱을 열 수 없어요. 차량 화면에서 확인해 주세요"
            },
        )
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
                modifier = Modifier.padding(top = Space.sm),
            )
        }
    }
}

/**
 * VIN을 어떻게 다루는지 밝힌다.
 *
 * 차대번호는 민감한 식별정보다. 어디로 가는지 안 밝히면 입력하기 꺼려진다.
 * 여기 적힌 내용은 전부 검증 가능한 사실이어야 한다 —
 * 앱에 인터넷 권한이 없어서 애초에 전송이 불가능하다.
 */
@Composable
private fun VinPrivacyNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.Carbon, RoundedCornerShape(Radius.button))
            .padding(Space.md),
    ) {
        Text(
            text = "VIN은 어디에 쓰이나요",
            style = MaterialTheme.typography.titleSmall,
            color = T.InkMuted,
        )
        Spacer(Modifier.height(Space.sm))
        // 네 줄이 결국 같은 말을 반복했다. 서로 다른 사실 세 가지만 남긴다
        NoticeLine("차량 연동 목적으로만 사용합니다")
        NoticeLine("이 앱은 인터넷을 사용하지 않습니다")
        NoticeLine("외부에 저장되지 않습니다")
    }
}

@Composable
private fun NoticeLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.padding(end = Space.sm),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )
    }
}

/** 좁으면 세로로 쌓고 넓으면 한 줄에 둔다 */
@Composable
private fun PrimaryActions(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) { content() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            // Row 안에서 weight를 쓰려면 RowScope가 필요해 그대로 흘려보낸다
            content()
        }
    }
}

/**
 * 진행 단계 표시.
 *
 * 넓으면 네 단계를 나란히, 좁으면 점 + 현재 단계 이름만.
 * 좁은 화면에서 칩 4개를 억지로 넣으면 글자가 세로로 쪼개진다.
 */
@Composable
private fun StepIndicator(current: PairingStep) {
    if (LocalPane.current.isCompact) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PairingStep.entries.forEach { step ->
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .weight(1f)
                            .background(
                                color = when {
                                    step == current -> T.Electric
                                    step.ordinal < current.ordinal -> T.Ok
                                    else -> T.Slate
                                },
                                shape = RoundedCornerShape(Radius.pill),
                            ),
                    )
                }
            }
            Text(
                text = "${current.ordinal + 1}/${PairingStep.entries.size} · ${current.title}",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
                modifier = Modifier.padding(top = Space.sm),
            )
            // 단계별 안내는 여기 붙인다. 제목 아래 큰 덩어리로 두면 화면 위가 비어 보인다
            Text(
                text = current.hint,
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
        return
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PairingStep.entries.forEach { step ->
                val isCurrent = step == current
                val isPast = step.ordinal < current.ordinal
                StatusPill(
                    text = step.title,
                    color = when {
                        isCurrent -> T.Electric
                        isPast -> T.Ok
                        else -> T.InkFaint
                    },
                )
            }
        }
        Text(
            text = current.hint,
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.padding(top = Space.sm),
        )
    }
}

/** 주변 스캔에서 잡힌 기기 하나 */
data class NearbyDevice(val name: String, val rssi: Int, val isTesla: Boolean = false)

/**
 * BLE 주소로 직접 붙는 고급 진단.
 *
 * 스캔으로 못 잡을 때, nRF Connect 같은 도구에서 확인한 차 주소를 넣어 바로 연결한다.
 */
@Composable
private fun DirectConnectPanel(busy: Boolean, onConnect: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }

    TCard(outlined = true) {
        Text(
            text = "주소로 직접 연결 (고급)",
            style = MaterialTheme.typography.titleSmall,
            color = T.Ink,
        )
        Text(
            text = "nRF Connect에서 본 차 주소를 넣으면 스캔 없이 바로 붙어요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.padding(top = Space.xs, bottom = Space.md),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.uppercase() },
            label = { Text("BLE 주소 (예 AA:BB:CC:11:22:33)") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = T.Electric,
                unfocusedBorderColor = T.Hairline,
                focusedTextColor = T.Ink,
                unfocusedTextColor = T.Ink,
                cursorColor = T.Electric,
                focusedLabelColor = T.Electric,
                unfocusedLabelColor = T.InkFaint,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.md))
        TButton(
            text = if (busy) "연결 중…" else "이 주소로 연결",
            tone = ButtonTone.Secondary,
            fillWidth = false,
            enabled = !busy && address.isNotBlank(),
            onClick = { onConnect(address) },
        )
    }
}

/**
 * 차를 못 찾았을 때 원인을 가르는 패널.
 *
 * 내 차가 목록에 뜨는데 이름이 다르면 VIN이 틀린 것이고,
 * 아무것도 안 뜨면 스캔 자체가 막힌 것이다. 둘은 대처가 완전히 다르다.
 */
@Composable
private fun NearbyPanel(
    nearby: List<NearbyDevice>?,
    busy: Boolean,
    onScan: () -> Unit,
    onLoadBonded: () -> Unit,
) {
    TCard(outlined = true) {
        Text(
            text = "차가 안 보이나요",
            style = MaterialTheme.typography.titleSmall,
            color = T.Ink,
        )
        Text(
            text = "주변 기기를 훑거나(★=테슬라), 이미 폰에 페어링된 기기 목록을 봅니다. " +
                "페어링 목록은 차가 없어도 읽혀요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
            modifier = Modifier.padding(top = Space.xs),
        )
        // 실사용 함정: 페어링 목록의 테슬라 주소로 직접 연결을 시도하다 30초 타임아웃만 반복했다
        Text(
            text = "주의 — 페어링 목록에 보이는 테슬라는 음악·통화용 주소예요. " +
                "키 연결용 BLE 주소가 아니라 직접 연결이 안 돼요. " +
                "기존 기기가 있으면 그 앱의 설정 → 차량 → \"BLE 주소\"를 그대로 입력하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = T.Warn,
            modifier = Modifier.padding(top = Space.xs),
        )

        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TButton(
                text = if (busy) "훑는 중…" else "주변 기기 확인",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                enabled = !busy,
                onClick = onScan,
            )
            TButton(
                text = "페어링된 기기",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                enabled = !busy,
                onClick = onLoadBonded,
            )
        }

        if (nearby != null) {
            Spacer(Modifier.height(Space.md))
            if (nearby.isEmpty()) {
                Text(
                    text = "한 건도 잡히지 않았어요. 스캔 자체가 막힌 상태예요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = T.Warn,
                )
            } else {
                nearby.forEach { device ->
                    Text(
                        text = "${if (device.isTesla) "★ " else ""}${device.name}  ·  ${device.rssi}dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (device.isTesla) T.Electric else T.InkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

data class PairingUiState(
    val step: PairingStep = PairingStep.EnterVin,
    val vin: String = "",
    val message: String? = null,
    val isError: Boolean = false,
    val isBusy: Boolean = false,
    /** 주변 스캔 결과. null이면 아직 훑지 않았다 */
    val nearby: List<NearbyDevice>? = null,
    /** 페어링 목록에서 감지한 테슬라 별칭. 없으면 null */
    val detectedName: String? = null,
) {
    val isVinValid: Boolean get() = TeslaBleSpec.isValidVin(vin)

    val primaryLabel: String
        get() = when {
            isBusy -> "진행 중…"
            step == PairingStep.EnterVin -> "차량 찾기"
            step == PairingStep.FindVehicle -> "다시 찾기"
            step == PairingStep.TapCard -> "키 등록 요청"
            else -> "시작하기"
        }

    val isPrimaryEnabled: Boolean get() = !isBusy && isVinValid
}

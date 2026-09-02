package com.wemade.teslamacro.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import com.wemade.teslamacro.ui.component.DraftField
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
import com.wemade.teslamacro.ui.component.CalloutNumber
import com.wemade.teslamacro.ui.component.draftBlock
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 등록 절차 4단계. 사용자는 지금 어디쯤인지 항상 알아야 한다 */
enum class PairingStep(val title: String, val hint: String) {
    EnterVin(
        "VIN 입력",
        "",
    ),
    FindVehicle(
        "차량 검색",
        "차량 가까이에서 연결합니다",
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

    // 넓으면 안내(왼쪽)와 입력(오른쪽)을 나란히 둔다. 한 기둥으로 세우면
    // 가로 태블릿에서 좌우가 통째로 비고, 지금 뭘 해야 하는지가 스크롤 아래로 밀린다
    TwoPaneOrColumn(
        compact = compact,
        modifier = modifier,
        guide = {
            BoardingNotice()

            // 페어링 목록에서 차를 찾았으면 알려준다. 별칭이 곧 내 차라는 확인이다
            if (state.detectedName != null && state.step == PairingStep.EnterVin) {
                DetectedVehicleNotice(state.detectedName)
            }

            Spacer(Modifier.height(Space.md))
            StepIndicator(state.step)
        },
        form = {
            // VIN 입력을 끝낸 뒤에는 이미 완료한 입력·앱 이동을 다시 보여주지 않는다.
            if (state.step == PairingStep.EnterVin) {
                TCard {
                    DraftField(
                        value = state.vin,
                        onValueChange = onVinChange,
                        label = "VIN (17자)",
                        singleLine = true,
                        isError = state.vin.isNotEmpty() && !state.isVinValid,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(Space.md))
                    OpenTeslaAppButton()

                    Spacer(Modifier.height(Space.sm))
                    VinPrivacyNotice()
                }
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
        },
        actions = {
            PrimaryActions {
                TButton(
                    text = state.primaryLabel,
                    enabled = state.isPrimaryEnabled,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
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
                    fillWidth = false,
                    onClick = onSkip,
                )
            }
        },
    )
}

/**
 * 넓으면 좌우 두 칸, 좁으면 위아래 한 기둥.
 *
 * 등록은 "읽고 → 입력하고 → 누르는" 흐름이라 안내와 입력을 갈라 두면
 * 넓은 화면에서 눈이 왼쪽에서 오른쪽으로 한 번만 움직이면 된다.
 */
@Composable
private fun TwoPaneOrColumn(
    compact: Boolean,
    modifier: Modifier,
    guide: @Composable ColumnScope.() -> Unit,
    form: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    if (compact) {
        Column(
            modifier = modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md),
            ) {
                guide()
                Spacer(Modifier.height(Space.md))
                form()
                Spacer(Modifier.height(Space.md))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .draftBlock()
                    .padding(vertical = Space.sm),
                content = actions,
            )
        }
        return
    }
    Row(
        modifier = modifier.fillMaxSize().padding(Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.xxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            content = guide,
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            form()
            Spacer(Modifier.height(Space.lg))
            actions()
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .draftBlock(tone = T.Warn)
            .padding(horizontal = Space.md, vertical = Space.md),
    ) {
        Text(
            text = "차량에서 진행해 주세요",
            style = MaterialTheme.typography.titleLarge,
            color = T.WarnText,
        )
        Text(
            text = "차량 화면과 카드키가 필요합니다",
            style = MaterialTheme.typography.bodySmall,
            color = T.Ink,
            modifier = Modifier.padding(top = Space.xs),
        )
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
    Text(
        text = "감지된 차량 · $name",
        style = MaterialTheme.typography.bodySmall,
        color = T.InkMuted,
        modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
    )
}

/**
 * 테슬라 앱으로 건너가는 버튼.
 *
 * VIN은 차량 화면이나 테슬라 앱에 있다. 앱을 직접 찾아 들어가게 두면
 * 등록 흐름이 거기서 끊긴다.
 */
@Composable
private fun OpenTeslaAppButton() {
    val context = LocalContext.current
    val installed = remember { TeslaAppLauncher.isInstalled(context) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column {
        TButton(
            text = if (installed) "테슬라 앱에서 VIN 확인" else "테슬라 앱 설치",
            tone = ButtonTone.Secondary,
            onClick = {
                notice = if (TeslaAppLauncher.open(context)) null
                else "테슬라 앱을 열 수 없어요.\n차량 화면에서 확인해 주세요"
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
 * 차대번호는 민감한 식별정보다. 입력을 망설이지 않도록 필요한 약속만 한 줄로 밝힌다.
 * 인터넷 기능과 별개로 VIN은 로컬 차량 연결에만 사용한다.
 */
@Composable
private fun VinPrivacyNotice() {
    Text(
        text = "VIN은 차량 연결에만 쓰고 외부로 보내지 않아요.",
        style = MaterialTheme.typography.bodySmall,
        color = T.InkMuted,
    )
}

/** 주 동작과 나중에를 한 줄에 두어 어느 화면에서도 함께 보이게 한다 */
@Composable
private fun PrimaryActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        content()
    }
}

/**
 * 진행 단계 표시.
 *
 * 넓으면 네 단계를 나란히, 좁으면 점 + 현재 단계 이름만.
 * 좁은 화면에서 칩 4개를 억지로 넣으면 글자가 세로로 쪼개진다.
 */
@OptIn(ExperimentalLayoutApi::class)
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
                            // 각진 눈금. 알약은 도면 문법이 아니다
                            .background(
                                when {
                                    step == current -> T.Ink
                                    step.ordinal < current.ordinal -> T.InkFaint
                                    else -> T.Slate
                                }
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
            if (current.hint.isNotBlank()) {
                Text(
                    text = current.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = T.InkMuted,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
        return
    }

    Column {
        // 중간 폭 화면에서 칩 4개가 오른쪽으로 넘친다 — 줄바꿈되는 FlowRow로 감싼다
        // 알약 칩을 쓰지 않는다 — 도면의 절차는 번호가 붙어 나열된다.
        // 지금 단계는 번호가 채워지고, 지난 단계는 취소선으로 지워진다
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            PairingStep.entries.forEach { step ->
                val isCurrent = step == current
                val isPast = step.ordinal < current.ordinal
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 지난 단계도 번호를 채운다 — 취소선을 그으면 "완료"가 아니라
                    // "무효"로 읽힌다. 도면의 취소선은 지워진 항목에 쓰는 기호다
                    CalloutNumber(number = step.ordinal + 1, highlighted = isCurrent || isPast)
                    Spacer(Modifier.width(Space.xs + 2.dp))
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) T.Ink else T.InkFaint,
                    )
                }
            }
        }
        if (current.hint.isNotBlank()) {
            Text(
                text = current.hint,
                style = MaterialTheme.typography.bodySmall,
                color = T.InkMuted,
                modifier = Modifier.padding(top = Space.sm),
            )
        }
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
            // 행동 지시문이라 InkFaint(대비 미달) 대신 InkMuted
            color = T.InkMuted,
            modifier = Modifier.padding(top = Space.xs, bottom = Space.md),
        )
        DraftField(
            value = address,
            onValueChange = { address = it.uppercase() },
            label = "BLE 주소 (예 AA:BB:CC:11:22:33)",
            singleLine = true,
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
            text = "주변 기기를 훑거나, 이미 폰에 페어링된 기기 목록을 봅니다.\n" +
                "테슬라로 보이는 것은 앞에 표시가 붙습니다.\n" +
                "페어링 목록은 차가 없어도 읽혀요.",
            style = MaterialTheme.typography.bodySmall,
            // 행동 지시문이라 InkFaint(대비 미달) 대신 InkMuted
            color = T.InkMuted,
            modifier = Modifier.padding(top = Space.xs),
        )
        // 실사용 함정: 페어링 목록의 테슬라 주소로 직접 연결을 시도하다 30초 타임아웃만 반복했다
        WarnNotice(
            label = "주의",
            body = "페어링 목록에 보이는 테슬라는 음악·통화용 주소예요.\n" +
                "키 연결용 BLE 주소가 아니라 직접 연결이 안 돼요.\n" +
                "기존 기기가 있으면 그 앱의 설정 → 차량 → \"BLE 주소\"를 그대로 입력하세요.",
            modifier = Modifier.padding(top = Space.sm),
        )

        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            // 좁은 화면에서 두 버튼이 카드 폭을 넘치지 않게 반씩 나눈다
            TButton(
                text = if (busy) "훑는 중…" else "주변 기기 확인",
                tone = ButtonTone.Secondary,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onScan,
            )
            TButton(
                text = "페어링된 기기",
                tone = ButtonTone.Secondary,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onLoadBonded,
            )
        }

        if (nearby != null) {
            Spacer(Modifier.height(Space.md))
            if (nearby.isEmpty()) {
                WarnNotice(body = "한 건도 잡히지 않았어요.\n스캔 자체가 막힌 상태예요.")
            } else {
                nearby.forEach { device ->
                    Row(modifier = Modifier.padding(top = Space.xs)) {
                        // 글리프(★)를 아이콘으로 쓰지 않는다 — 폰트에 따라 모양이 달라지고
                        // 별은 도면 기호가 아니다. 지목은 삼각 지시자로 한다
                        if (device.isTesla) {
                            androidx.compose.material3.Icon(
                                imageVector = com.wemade.teslamacro.ui.component.DraftMark.Pointer,
                                contentDescription = "테슬라 후보",
                                tint = T.Ink,
                                modifier = Modifier
                                    .size(12.dp)
                                    .padding(end = Space.xs),
                            )
                        }
                        Text(
                            text = "${device.name}  ·  ${device.rssi}dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (device.isTesla) T.Ink else T.InkMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 경고 안내 박스.
 * 밝은 앰버(T.Warn) 글자는 흰 카드 위에서 안 읽혀서,
 * 앰버 틴트 면 + 진한 본문(T.Ink)으로 바꿔 보여준다. 라벨만 WarnText.
 */
@Composable
private fun WarnNotice(body: String, modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .draftBlock(tone = T.Warn)
            .padding(Space.md),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = T.WarnText,
                modifier = Modifier.padding(bottom = Space.xs),
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = T.Ink,
        )
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

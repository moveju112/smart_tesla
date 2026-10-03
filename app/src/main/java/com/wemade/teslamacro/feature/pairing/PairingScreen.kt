package com.wemade.teslamacro.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
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
import com.wemade.teslable.TeslaBleSpec
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DiagLogPanel
import com.wemade.teslamacro.ui.component.draftBlock
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 차량 검색 후에도 카드키로 앱 키를 승인해야 등록이 완료된다. */
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
    onEditVin: () -> Unit = {},
) {
    val compact = LocalPane.current.isCompact

    // 넓으면 안내(왼쪽)와 입력(오른쪽)을 나란히 둔다. 한 기둥으로 세우면
    // 가로 태블릿에서 좌우가 통째로 비고, 지금 뭘 해야 하는지가 스크롤 아래로 밀린다
    TwoPaneOrColumn(
        compact = compact,
        modifier = modifier,
        guide = {
            BoardingNotice()
            if (state.step != PairingStep.EnterVin && state.step != PairingStep.Done) {
                Text("VIN 저장됨 · 차량 키 등록 전",
                    style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
            }
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
                // VIN 입력은 공용 필드에 맡기고 별도의 중첩 카드는 두지 않는다.
                DraftField(
                    value = state.vin,
                    onValueChange = onVinChange,
                    label = "차량 식별번호 (VIN)",
                    placeholder = "VIN 17자 입력",
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

            if (state.message != null) {
                Spacer(Modifier.height(Space.md))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isError) T.Danger else T.InkMuted,
                )
            }
            if (state.isError && state.step == PairingStep.FindVehicle) {
                Spacer(Modifier.height(Space.sm))
                TButton("VIN 수정", tone = ButtonTone.Ghost, fillWidth = false,
                    enabled = !state.isBusy, onClick = onEditVin)
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
                    onClick = {
                        when (state.step) {
                            PairingStep.EnterVin -> onFindVehicle()
                            PairingStep.FindVehicle -> onFindVehicle()
                            PairingStep.TapCard -> onRequestEnrollment()
                            PairingStep.Done -> onSkip()
                        }
                    },
                )
                if (state.step != PairingStep.Done) {
                    TButton(text = "나중에", tone = ButtonTone.Ghost, onClick = onSkip)
                }
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

/** 차량 화면과 카드키 준비를 먼저 안내한다. */
@Composable
private fun BoardingNotice() {
    Text("차량 등록", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
    Text("차량 가까이에서 진행하세요. 차량 화면과 카드키가 필요합니다.",
        style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
}

/** 감지된 별칭만 알 수 있으므로 VIN 입력은 계속 필요하다. */
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
            fillWidth = false,
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

/** 큰 글씨에서도 주요 동작과 나중에 버튼을 각각 온전한 너비로 제공한다. */
@Composable
private fun PrimaryActions(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.xs), content = content)
}

/** 현재 절차와 이어지는 안내를 텍스트로 제공한다. */
@Composable
private fun StepIndicator(current: PairingStep) {
    Text(current.title, style = MaterialTheme.typography.titleMedium, color = T.Ink)
    if (current.hint.isNotBlank()) {
        Text(current.hint, style = MaterialTheme.typography.bodyMedium,
            color = T.InkMuted, modifier = Modifier.padding(top = Space.xs))
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

    TCard {
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
    TCard {
        Text(
            text = "차가 안 보이나요",
            style = MaterialTheme.typography.titleSmall,
            color = T.Ink,
        )
        Text(
            text = "주변 기기를 확인하거나 이미 페어링된 기기 목록을 확인하세요.",
            style = MaterialTheme.typography.bodySmall,
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
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            TButton(
                text = if (busy) "훑는 중…" else "주변 기기 확인",
                tone = ButtonTone.Secondary,
                fillWidth = false,
                enabled = !busy,
                onClick = onScan,
            )
            TButton(
                text = "페어링된 기기",
                tone = ButtonTone.Ghost,
                fillWidth = false,
                enabled = !busy,
                onClick = onLoadBonded,
            )
        }

        if (nearby != null) {
            Spacer(Modifier.height(Space.md))
            if (nearby.isEmpty()) {
                WarnNotice(body = "한 건도 잡히지 않았어요.\n스캔 자체가 막힌 상태예요.")
            } else {
                nearby.forEach { device ->
                    Row(modifier = Modifier.padding(top = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        if (device.isTesla) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = "테슬라 후보",
                                tint = T.Electric, modifier = Modifier.size(Space.lg))
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

/** 경고는 공용 주의색 면과 명확한 안내문으로 표시한다. */
@Composable
private fun WarnNotice(body: String, modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .draftBlock(tone = T.Warn)
            .padding(Space.md),
    ) {
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null,
                    tint = T.WarnText, modifier = Modifier.size(Space.lg))
                Text(label, style = MaterialTheme.typography.titleSmall, color = T.WarnText)
            }
            Spacer(Modifier.height(Space.xs))
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

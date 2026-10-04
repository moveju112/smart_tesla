package com.wemade.teslamacro.feature.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Text
import androidx.compose.material3.Snackbar
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.wemade.teslamacro.ui.layout.LocalPane
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.nav.DestinationRequest
import com.wemade.teslamacro.ui.component.*
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 전송 화면은 서버 결과와 아직 보내지 않은 검색어를 따로 보관한다. */
data class DestinationUiState(
    val query: String = "", val minutes: Int = 10,
    val request: DestinationRequest? = null, val receiverName: String? = null,
    val pairingCode: String = "", val receiverCode: String? = null,
    val receiving: Boolean = false,
    val overlayAllowed: Boolean = false, val busy: Boolean = false,
    val notice: String? = null, val error: String? = null, val connectionError: String? = null,
    val receiveMessage: String = "탑승 대기",
    val connectionChecked: Boolean = false,
)

/** 조회가 끝난 정상 응답만 연결 준비 여부로 판단한다. 통신 오류를 미설정으로 취급하지 않는다. */
internal fun needsDestinationSetup(state: DestinationUiState): Boolean =
    state.connectionChecked && state.connectionError == null && state.receiverName == null &&
        !state.receiving

/** 실제 화면 수명에 맞춰 발신 결과 조회를 시작하고 멈춘다. */
@Composable
fun DestinationRoute(
    viewModel: DestinationViewModel,
    settingsOnly: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, _ ->
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.observe()
            else viewModel.stopObserving()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.observe()
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.stopObserving() }
    }
    var checkedSetup by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.connectionChecked, state.connectionError, settingsOnly) {
        if (!settingsOnly && state.connectionChecked && state.connectionError == null && !checkedSetup && onOpenSettings != null) {
            checkedSetup = true
            if (needsDestinationSetup(state)) onOpenSettings()
        }
    }
    DestinationScreen(state, onBack, viewModel::queryChanged,
        viewModel::minutesChanged, viewModel::send, viewModel::cancel, viewModel::receiveTest,
        viewModel::refresh, viewModel::pairingCodeChanged, viewModel::pair, viewModel::unlink,
        viewModel::createPairCode, viewModel::receivingChanged, viewModel::allowOverlay,
        settingsOnly = settingsOnly, onOpenSettings = onOpenSettings)
}

/** 출발 전 전송을 먼저 보여주고 드문 연결·수신 설정은 한 단계 안으로 둔다. */
@Composable
fun DestinationScreen(
    state: DestinationUiState, onBack: () -> Unit = {}, onQuery: (String) -> Unit = {},
    onMinutes: (Int) -> Unit = {}, onSend: (Boolean) -> Unit = {},
    onCancel: () -> Unit = {}, onReceiveTest: () -> Unit = {}, onRefresh: () -> Unit = {},
    onPairingCode: (String) -> Unit = {}, onPair: () -> Unit = {}, onUnlink: () -> Unit = {},
    onCreateCode: () -> Unit = {},
    onReceiving: (Boolean) -> Unit = {}, onOverlay: () -> Unit = {},
    initialSetup: Boolean = false,
    settingsOnly: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    var setup by rememberSaveable { mutableStateOf(initialSetup || settingsOnly) }
    var pairingExpanded by rememberSaveable { mutableStateOf(false) }
    var testExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !setup) { onBack() }
    val wide = !LocalPane.current.isCompact
    val canSend = !state.busy && DestinationPlace(state.query.trim()).valid() &&
        state.minutes in 1..120
    val content: @Composable () -> Unit = {
        Column(if (setup) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f, fill = !setup).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = if (wide) 640.dp else androidx.compose.ui.unit.Dp.Infinity)
                        .then(if (setup) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                        .verticalScroll(scrollState).then(if (setup) Modifier else Modifier.padding(Space.md)),
                    verticalArrangement = Arrangement.spacedBy(Space.sm + Space.xs),
                ) {
                    if (!setup) {
                        TButton("뒤로", tone = ButtonTone.Ghost, icon = Icons.Rounded.ArrowBack,
                            fillWidth = false, onClick = onBack)
                        HelpTitle("목적지 전송", description = "입력한 검색어를 받는 기기의 네이버지도에서 검색해요. 장소 선택과 길안내 시작은 받는 기기에서 해 주세요.",
                            style = MaterialTheme.typography.headlineSmall)
                    }
                    if (state.busy) Text("처리 중…", color = T.InkMuted)
                    if (setup) {
                        Column {
                            SettingRow("보낼 기기", state.receiverName ?: "미연결",
                                onClick = { pairingExpanded = true })
                            Hairline()
                            NumberSettingRow("전송 유효시간", state.minutes.toDouble(),
                                min = 1.0, max = 120.0, step = 1.0, unit = "분",
                                onChange = { onMinutes(it.toInt()) })
                            Hairline()
                            SettingToggleRow("이 기기 자동 수신", state.receiving, onReceiving,
                                description = "탑승이 확인되면 받은 목적지를 이 기기의 네이버지도에서 열어요.")
                            if (state.receiving) {
                                SettingActionRow("수신 상태", action = {
                                    Text(state.receiveMessage, style = MaterialTheme.typography.bodyMedium,
                                        color = T.InkMuted, textAlign = TextAlign.End)
                                })
                                if (!state.overlayAllowed) SettingActionRow("다른 앱 위에 표시", action = {
                                    TButton("허용", tone = ButtonTone.Secondary,
                                        fillWidth = false, small = true, onClick = onOverlay)
                                })
                            }
                            Hairline()
                            SettingActionRow("연결 코드", description = "보내는 기기에서 이 코드를 입력해 연결하세요. 생성한 코드는 10분 동안 사용할 수 있어요.", action = {
                                TButton(if (state.receiverCode == null) "생성" else "재생성",
                                    tone = ButtonTone.Secondary, fillWidth = false, small = true,
                                    enabled = !state.busy, onClick = onCreateCode)
                            })
                            state.receiverCode?.let { code ->
                                SettingActionRow("생성된 코드", action = {
                                    Text(code, style = MaterialTheme.typography.bodyMedium,
                                        color = T.Ink, textAlign = TextAlign.End)
                                })
                            }
                            Hairline()
                            SettingRow("수신 테스트", onClick = { testExpanded = true })
                        }
                    } else {
                        Column {
                            DestinationQueryRow(state, onQuery)
                            Hairline()
                            SettingRow("받는 기기", state.receiverName ?: if (state.connectionError != null) "확인 필요"
                                else if (!state.connectionChecked) "확인 중…" else "미연결",
                                onClick = { if (onOpenSettings != null) onOpenSettings() else setup = true })
                            Hairline()
                            SettingActionRow("전송", action = {
                                TButton("전송", icon = Icons.Rounded.Send, fillWidth = false, small = true,
                                    enabled = canSend && state.receiverName != null,
                                    onClick = { onSend(false) })
                            })
                            state.request?.let { request ->
                                Hairline()
                                SettingActionRow("보낸 검색어", action = {
                                    Text(request.destination.name, style = MaterialTheme.typography.bodyMedium,
                                        color = T.InkMuted, textAlign = TextAlign.End)
                                })
                                SettingActionRow("전송 상태", description = "네이버지도에 전달된 뒤 장소 선택과 길안내 시작은 받는 기기에서 해 주세요.", action = {
                                    Text((if (request.selfTest) "이 기기 테스트 · " else "") + destinationStatus(request.status),
                                        style = MaterialTheme.typography.bodyMedium, color = T.InkMuted,
                                        textAlign = TextAlign.End)
                                })
                                if (request.status == "pending") SettingActionRow("대기 중인 전송", action = {
                                    TButton("취소", tone = ButtonTone.Ghost, fillWidth = false, small = true,
                                        enabled = !state.busy, onClick = onCancel)
                                })
                            }
                        }
                    }
                }
            }
            if (!setup || (!pairingExpanded && !testExpanded)) DestinationFeedback(state, onRefresh)
        }
    }
    if (setup) PickerSheet("목적지 설정",
        onDismiss = { if (settingsOnly) onBack() else setup = false }, content = content)
    else content()
    if (setup && pairingExpanded) PickerSheet("보낼 기기", onDismiss = { pairingExpanded = false }) {
        DestinationPairingEditor(state, onPairingCode, onPair, onUnlink, onRefresh)
    }
    if (setup && testExpanded) PickerSheet("수신 테스트", onDismiss = { testExpanded = false }) {
        DestinationReceiveTestEditor(state, onQuery, onSend, onReceiveTest, onOverlay, onRefresh)
    }
}

/** 기기 연결은 편집 시트에서만 입력하고 연결 결과와 해제를 함께 표시한다. */
@Composable
internal fun DestinationPairingEditor(
    state: DestinationUiState,
    onPairingCode: (String) -> Unit = {}, onPair: () -> Unit = {},
    onUnlink: () -> Unit = {}, onRefresh: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        state.receiverName?.let { name ->
            SettingActionRow(name, action = {
                TButton("연결 해제", tone = ButtonTone.Danger, fillWidth = false,
                    small = true, enabled = !state.busy, onClick = onUnlink)
            })
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val connectButton: @Composable () -> Unit = {
                TButton("연결", fillWidth = false, small = true,
                    enabled = !state.busy && state.pairingCode.length == 10, onClick = onPair)
            }
            // 큰 글자로 입력 폭이 부족할 때만 연결 버튼을 다음 줄로 보낸다.
            if (maxWidth < 240.dp * LocalDensity.current.fontScale) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    DraftField(state.pairingCode, onPairingCode, label = "연결 코드", enabled = !state.busy)
                    connectButton()
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    DraftField(state.pairingCode, onPairingCode, label = "연결 코드",
                        enabled = !state.busy, modifier = Modifier.weight(1f))
                    connectButton()
                }
            }
        }
        if (state.busy) Text("처리 중…", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        DestinationFeedback(state, onRefresh)
    }
}

/** 수신 테스트 입력과 권한·결과는 전용 시트에 모아 일반 설정 행을 짧게 유지한다. */
@Composable
internal fun DestinationReceiveTestEditor(
    state: DestinationUiState,
    onQuery: (String) -> Unit = {}, onSend: (Boolean) -> Unit = {},
    onReceiveTest: () -> Unit = {}, onOverlay: () -> Unit = {}, onRefresh: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        DestinationQueryRow(state, onQuery)
        Hairline()
        SettingActionRow("테스트 전송", description = "입력한 검색어를 이 기기로 보내 수신을 확인해요.", action = {
            TButton("전송", tone = ButtonTone.Secondary, icon = Icons.Rounded.Send,
                fillWidth = false, small = true,
                enabled = !state.busy && DestinationPlace(state.query.trim()).valid() &&
                    state.minutes in 1..120, onClick = { onSend(true) })
        })
        SettingActionRow("네이버지도", description = "대기 중인 목적지를 이 기기의 네이버지도에서 열어요.", action = {
            TButton("열기", tone = ButtonTone.Secondary, icon = Icons.Rounded.Place,
                fillWidth = false, small = true, enabled = !state.busy && state.overlayAllowed,
                onClick = onReceiveTest)
        })
        if (!state.overlayAllowed) SettingActionRow("다른 앱 위에 표시", action = {
            TButton("허용", tone = ButtonTone.Secondary, fillWidth = false, small = true, onClick = onOverlay)
        })
        SettingActionRow("수신 상태", action = {
            Text(state.receiveMessage, style = MaterialTheme.typography.bodyMedium,
                color = T.InkMuted, textAlign = TextAlign.End)
        })
        if (state.busy) Text("처리 중…", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
        DestinationFeedback(state, onRefresh)
    }
}

/** 결과와 재확인을 현재 열린 화면에 표시해 편집 시트 뒤로 오류가 가려지지 않게 한다. */
@Composable
private fun DestinationFeedback(state: DestinationUiState, onRefresh: () -> Unit) {
    (state.error ?: state.connectionError ?: state.notice)?.let { message ->
        Snackbar(
            modifier = Modifier.padding(Space.md).semantics { liveRegion = LiveRegionMode.Polite },
            action = if (state.error != null || state.connectionError != null) {
                { androidx.compose.material3.TextButton(enabled = !state.busy, onClick = onRefresh,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = androidx.compose.material3.SnackbarDefaults.actionColor)) { Text("재확인") } }
            } else null,
        ) { Text(message) }
    }
}

/** 검색어는 현재 값만 표시하고 편집 시트의 적용 버튼으로만 원본을 바꾼다. */
@Composable
private fun DestinationQueryRow(state: DestinationUiState, onQuery: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingRow("검색어", state.query.ifBlank { "입력" }, onClick = { if (!state.busy) editing = true })
    if (editing) DestinationQueryEditorSheet(state.query, state.busy,
        onApply = { onQuery(it); editing = false }, onDismiss = { editing = false })
}

/** 닫기와 취소는 입력을 버리고 유효한 검색어만 적용한다. */
@Composable
internal fun DestinationQueryEditorSheet(
    query: String,
    busy: Boolean = false,
    onApply: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf(query) }
    PickerSheet("검색어", onDismiss = onDismiss, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TButton("취소", tone = ButtonTone.Ghost, fillWidth = false, onClick = onDismiss)
            TButton("적용", fillWidth = false, enabled = !busy && DestinationPlace(draft.trim()).valid(),
                onClick = { onApply(draft.trim()) })
        }
    }) {
        val invalid = draft.isNotEmpty() && !DestinationPlace(draft.trim()).valid()
        DraftField(draft, { draft = it }, label = "장소 또는 주소", enabled = !busy,
            isError = invalid, note = if (invalid) "줄바꿈 없이 1~120자로 입력해 주세요" else null)
    }
}

/** 지도 앱에 전달한 사실과 실제 길 안내 시작 여부를 구분한다. */
internal fun destinationStatus(status: String): String = when (status) {
    "pending" -> "수신 대기"
    "claimed" -> "인계됨 · 전달 결과 확인 필요"
    "delivered" -> "네이버지도로 전달됨"
    "failed" -> "전달 실패 · 목적지를 다시 보내 주세요"
    "cancelled" -> "전송 취소됨"
    "replaced" -> "새 목적지로 교체됨"
    "expired" -> "유효시간 만료"
    else -> "상태 확인 필요"
}

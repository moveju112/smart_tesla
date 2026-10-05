package com.wemade.teslamacro.feature.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
    val receiving: Boolean = false, val senderCount: Int = 0,
    val overlayAllowed: Boolean = false, val busy: Boolean = false,
    val notice: String? = null, val error: String? = null, val connectionError: String? = null,
    val receiveMessage: String = "탑승 대기",
    val connectionChecked: Boolean = false,
    val setupStarted: Boolean? = null,
    val sendCompleted: Boolean = false,
) {
    val connected: Boolean get() = receiverName != null || senderCount > 0
    val canConfigure: Boolean get() = !busy && (setupStarted == false || (connectionChecked && connectionError == null))
    val canSend: Boolean get() = canConfigure && receiverName != null
    val canReceive: Boolean get() = canConfigure && senderCount > 0
}

/** 처음 설정하는 기기는 조회 없이 진입하고, 확인된 미연결도 통신 오류로 가리지 않는다. */
internal fun needsDestinationSetup(state: DestinationUiState): Boolean =
    state.setupStarted == false || (state.connectionChecked && state.receiverName == null)

/** 실제 화면 수명에 맞춰 발신 결과 조회를 시작하고 멈춘다. */
@Composable
fun DestinationRoute(
    viewModel: DestinationViewModel,
    settingsOnly: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ObserveDestination(viewModel)
    var checkedSetup by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.connectionChecked, state.connectionError, settingsOnly) {
        if (!settingsOnly && state.connectionChecked && state.connectionError == null && !checkedSetup && onOpenSettings != null) {
            checkedSetup = true
            if (needsDestinationSetup(state)) onOpenSettings()
        }
    }
    DestinationScreen(state, onBack, viewModel::queryChanged,
        viewModel::minutesChanged, viewModel::send, viewModel::cancel,
        viewModel::refresh, viewModel::pairingCodeChanged, viewModel::pair, viewModel::unlink,
        viewModel::createPairCode, viewModel::receivingChanged, viewModel::allowOverlay,
        settingsOnly = settingsOnly, onOpenSettings = onOpenSettings)
}

/** 위젯 입력창도 기존 화면과 같은 수명 동안만 전송 결과를 조회한다. */
@Composable
internal fun ObserveDestination(viewModel: DestinationViewModel) {
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
}

/** 출발 전 전송을 먼저 보여주고 드문 연결·수신 설정은 한 단계 안으로 둔다. */
@Composable
fun DestinationScreen(
    state: DestinationUiState, onBack: () -> Unit = {}, onQuery: (String) -> Unit = {},
    onMinutes: (Int) -> Unit = {}, onSend: () -> Unit = {},
    onCancel: () -> Unit = {}, onRefresh: () -> Unit = {},
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
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = !setup) { onBack() }
    val wide = !LocalPane.current.isCompact
    val canSend = state.canSend && DestinationPlace(state.query.trim()).valid() &&
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
                            SettingActionRow("연결코드생성", description = "받는 기기에서 생성하고, 보내는 기기에 입력하세요. 코드는 10분 동안 유효해요.", action = {
                                TButton(if (state.receiverCode == null) "생성" else "재생성",
                                    tone = ButtonTone.Secondary, fillWidth = false, small = true,
                                    enabled = state.canConfigure && !state.connected, onClick = onCreateCode)
                            })
                            state.receiverCode?.takeIf { !state.connected }?.let { code ->
                                SelectionContainer {
                                    Text(code, style = MaterialTheme.typography.titleLarge, color = T.Ink,
                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(Space.sm))
                                }
                                TButton("코드 복사", tone = ButtonTone.Ghost, onClick = {
                                    clipboard.setText(AnnotatedString(code))
                                })
                            }
                            Hairline()
                            if (state.connected) {
                                SettingActionRow("연결된 기기", action = {
                                    Text(state.receiverName ?: "보내는 기기 ${state.senderCount}대",
                                        style = MaterialTheme.typography.bodyMedium, color = T.InkMuted,
                                        textAlign = TextAlign.End)
                                })
                                SettingActionRow("기기 연결", action = {
                                    TButton("연결 해제", tone = ButtonTone.Danger, fillWidth = false,
                                        small = true, enabled = state.canConfigure, onClick = onUnlink)
                                })
                            } else {
                                SettingRow("전송받을 기기", if (state.setupStarted == false || state.connectionChecked) "미연결" else "확인 중…",
                                    enabled = state.canConfigure, onClick = { pairingExpanded = true })
                            }
                            if (state.setupStarted == false) {
                                TButton("기존 연결 불러오기", tone = ButtonTone.Ghost,
                                    enabled = !state.busy, onClick = onRefresh)
                            }
                            Hairline()
                            NumberSettingRow("전송 유효시간", state.minutes.toDouble(),
                                min = 1.0, max = 120.0, step = 1.0, unit = "분",
                                enabled = state.canSend, onChange = { onMinutes(it.toInt()) })
                            Hairline()
                            SettingToggleRow("이 기기 자동 수신", state.receiving, onReceiving,
                                enabled = state.canReceive,
                                description = "보내는 기기와 연결하면 사용할 수 있어요. 탑승 중 받은 목적지를 네이버지도에서 열어요.")
                            if (state.senderCount > 0 && state.receiving) {
                                SettingActionRow("수신 상태", action = {
                                    Text(state.receiveMessage, style = MaterialTheme.typography.bodyMedium,
                                        color = T.InkMuted, textAlign = TextAlign.End)
                                })
                                if (!state.overlayAllowed) SettingActionRow("다른 앱 위에 표시", action = {
                                    TButton("허용", tone = ButtonTone.Secondary, enabled = state.canReceive,
                                        fillWidth = false, small = true, onClick = onOverlay)
                                })
                            }
                        }
                    } else {
                        Column {
                            DestinationQueryRow(state, onQuery)
                            Hairline()
                            SettingRow("전송받을 기기", state.receiverName ?: if (state.connectionError != null) "확인 필요"
                                else if (!state.connectionChecked) "확인 중…" else "미연결",
                                onClick = { if (onOpenSettings != null) onOpenSettings() else setup = true })
                            Hairline()
                            SettingActionRow("전송", action = {
                                TButton("전송", icon = Icons.Rounded.Send, fillWidth = false, small = true,
                                    enabled = canSend && state.receiverName != null,
                                    onClick = onSend)
                            })
                            if (state.request?.status == "pending") {
                                Hairline()
                                SettingActionRow("대기 중인 전송", action = {
                                    TButton("취소", tone = ButtonTone.Ghost, fillWidth = false, small = true,
                                        enabled = !state.busy, onClick = onCancel)
                                })
                            }
                        }
                    }
                }
            }
            if (!setup || !pairingExpanded) DestinationFeedback(state, onRefresh)
        }
    }
    if (setup) PickerSheet("목적지 설정",
        onDismiss = { if (settingsOnly) onBack() else setup = false }, content = content)
    else content()
    if (setup && pairingExpanded) PickerSheet("전송받을 기기", onDismiss = { pairingExpanded = false }) {
        DestinationPairingEditor(state, onPairingCode, onPair, onUnlink, onRefresh)
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
                    enabled = state.canConfigure && !state.connected && state.pairingCode.length == 10, onClick = onPair)
            }
            // 큰 글자로 입력 폭이 부족할 때만 연결 버튼을 다음 줄로 보낸다.
            if (maxWidth < 240.dp * LocalDensity.current.fontScale) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    DraftField(state.pairingCode, onPairingCode, label = "연결 코드", enabled = state.canConfigure && !state.connected)
                    connectButton()
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    DraftField(state.pairingCode, onPairingCode, label = "연결 코드",
                        enabled = state.canConfigure && !state.connected, modifier = Modifier.weight(1f))
                    connectButton()
                }
            }
        }
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

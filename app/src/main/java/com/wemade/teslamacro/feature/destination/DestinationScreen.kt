package com.wemade.teslamacro.feature.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.ui.unit.dp
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
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    if (!setup) {
                        TButton("뒤로", tone = ButtonTone.Ghost, icon = Icons.Rounded.ArrowBack,
                            fillWidth = false, onClick = onBack)
                        Text("목적지 전송", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
                    }
                    if (state.busy) Text("처리 중…", color = T.InkMuted)
                    if (setup) {
                        DestinationSection("전송 유효시간") {
                            NumberStepper(state.minutes.toDouble(), min = 1.0, max = 120.0, step = 1.0,
                                unit = "분", onChange = { onMinutes(it.toInt()) })
                        }
                        DestinationSection("보낼 기기") {
                            state.receiverName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = T.InkMuted) }
                            DraftField(state.pairingCode, onPairingCode, label = "받을 기기의 연결 코드", enabled = !state.busy)
                            TButton("연결", icon = Icons.Rounded.Link, fillWidth = false,
                                enabled = !state.busy && state.pairingCode.length == 10, onClick = onPair)
                        }
                        DestinationSection("이 기기에서 받기") {
                            DraftToggle(state.receiving, onReceiving, label = "탑승 시 자동 수신")
                            if (state.receiving) Text(state.receiveMessage, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                            if (!state.overlayAllowed) TButton("다른 앱 위에 표시", tone = ButtonTone.Secondary,
                                fillWidth = false, onClick = onOverlay)
                            TButton("연결 코드 만들기", tone = ButtonTone.Secondary, icon = Icons.Rounded.Link,
                                fillWidth = false, enabled = !state.busy, onClick = onCreateCode)
                            state.receiverCode?.let {
                                Text(it, style = MaterialTheme.typography.headlineSmall, color = T.Ink)
                                Text("10분 내 사용", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                            }
                        }
                        DestinationSection {
                            com.wemade.teslamacro.ui.component.DisclosureHeader(
                                "수신 테스트", testExpanded, { testExpanded = !testExpanded })
                            if (testExpanded) {
                                DraftField(state.query, onQuery, label = "검색어", enabled = !state.busy)
                                TButton("이 기기로 전송", tone = ButtonTone.Secondary, icon = Icons.Rounded.Send,
                                    fillWidth = false, enabled = canSend, onClick = { onSend(true) })
                                TButton("지도 열기", tone = ButtonTone.Secondary, icon = Icons.Rounded.Place,
                                    fillWidth = false, enabled = !state.busy && state.overlayAllowed, onClick = onReceiveTest)
                                Text(state.receiveMessage, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                            }
                        }
                    } else {
                        state.request?.let { request ->
                            DestinationSection("전송 상태") {
                                Text(request.destination.name, style = MaterialTheme.typography.bodyLarge, color = T.Ink)
                                Text((if (request.selfTest) "폰 1대 테스트 · " else "") + destinationStatus(request.status),
                                    style = MaterialTheme.typography.bodyMedium, color = T.InkMuted)
                                if (request.status == "pending") TButton("전송 취소", tone = ButtonTone.Ghost,
                                    fillWidth = false, enabled = !state.busy, onClick = onCancel)
                            }
                        }
                        DestinationSection {
                            DraftField(state.query, onQuery, label = "장소 또는 주소", enabled = !state.busy)
                            Text("태블릿 네이버지도에서 검색해요", style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                            TButton("전송", icon = Icons.Rounded.Send,
                                enabled = canSend && state.receiverName != null,
                                onClick = { onSend(false) })
                            Text(state.receiverName ?: if (state.connectionError != null) "연결 확인 필요"
                                else if (!state.connectionChecked) "연결 확인 중…" else "받는 기기 미연결",
                                style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                            TButton("설정", tone = ButtonTone.Ghost, icon = Icons.Rounded.Settings,
                                fillWidth = false, onClick = { if (onOpenSettings != null) onOpenSettings() else setup = true })
                        }
                    }
                }
            }
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
    }
    if (setup) PickerSheet("목적지 설정",
        onDismiss = { if (settingsOnly) onBack() else setup = false }, content = content)
    else content()
}

/** 검색·전송·수신을 카드 중첩 없이 제목과 간격으로 구분한다. */
@Composable
private fun DestinationSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink)
        content()
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

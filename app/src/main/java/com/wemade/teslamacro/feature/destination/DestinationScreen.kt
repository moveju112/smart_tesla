package com.wemade.teslamacro.feature.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.nav.DestinationRequest
import com.wemade.teslamacro.ui.component.*
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 전송 화면은 서버 결과와 사용자가 선택한 장소를 따로 보관한다. */
data class DestinationUiState(
    val query: String = "", val results: List<DestinationPlace> = emptyList(),
    val selected: DestinationPlace? = null, val minutes: String = "10",
    val request: DestinationRequest? = null, val receiverName: String? = null,
    val pairingCode: String = "", val receiverCode: String? = null,
    val mounted: Boolean = false, val receiving: Boolean = false,
    val overlayAllowed: Boolean = false, val busy: Boolean = false,
    val notice: String? = null, val error: String? = null, val connectionError: String? = null,
    val receiveMessage: String = "탑승하면 목적지를 확인해요",
)

/** 실제 화면 수명에 맞춰 발신 결과 조회를 시작하고 멈춘다. */
@Composable
fun DestinationRoute(viewModel: DestinationViewModel, onBack: () -> Unit) {
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
    DestinationScreen(state, onBack, viewModel::queryChanged, viewModel::search, viewModel::select,
        viewModel::minutesChanged, viewModel::send, viewModel::cancel, viewModel::receiveTest,
        viewModel::refresh, viewModel::pairingCodeChanged, viewModel::pair, viewModel::unlink,
        viewModel::createPairCode, viewModel::enableReceiver, viewModel::receivingChanged, viewModel::allowOverlay)
}

/** 출발 전 전송을 먼저 보여주고 드문 연결·수신 설정은 한 단계 안으로 둔다. */
@Composable
fun DestinationScreen(
    state: DestinationUiState, onBack: () -> Unit = {}, onQuery: (String) -> Unit = {},
    onSearch: () -> Unit = {}, onSelect: (DestinationPlace) -> Unit = {},
    onMinutes: (String) -> Unit = {}, onSend: (Boolean) -> Unit = {},
    onCancel: () -> Unit = {}, onReceiveTest: () -> Unit = {}, onRefresh: () -> Unit = {},
    onPairingCode: (String) -> Unit = {}, onPair: () -> Unit = {}, onUnlink: () -> Unit = {},
    onCreateCode: () -> Unit = {}, onEnableReceiver: () -> Unit = {},
    onReceiving: (Boolean) -> Unit = {}, onOverlay: () -> Unit = {},
    initialSetup: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
) {
    var setup by rememberSaveable { mutableStateOf(initialSetup) }
    BackHandler { if (setup) setup = false else onBack() }
    val wide = !LocalPane.current.isCompact
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = if (wide) 640.dp else androidx.compose.ui.unit.Dp.Infinity)
        .fillMaxSize().verticalScroll(scrollState).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        TButton("뒤로", tone = ButtonTone.Ghost, fillWidth = false, onClick = { if (setup) setup = false else onBack() })
        Text(if (setup) "기기 연결·수신" else "차로 보내기", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
        if (state.busy) Text("처리 중이에요…", color = T.InkMuted)
        if (setup) {
            DestinationCard {
                Text("보내는 폰에서", style = MaterialTheme.typography.titleMedium, color = T.Ink)
                Text(state.receiverName?.let { "연결된 기기 · $it" } ?: "받는 태블릿에 표시된 코드를 입력해 주세요", color = T.InkMuted)
                DraftField(state.pairingCode, onPairingCode, label = "기기 연결 코드", enabled = !state.busy)
                TButton("기기 연결", enabled = !state.busy && state.pairingCode.length == 10, onClick = onPair)
                if (state.receiverName != null) TButton("연결 해제", tone = ButtonTone.Ghost, enabled = !state.busy, onClick = onUnlink)
            }
            DestinationCard {
                Text("받는 태블릿에서", style = MaterialTheme.typography.titleMedium, color = T.Ink)
                Text("거치 모드에서 차량 탑승과 인터넷 연결을 확인한 뒤 네이버지도로 전달해요.", color = T.InkMuted)
                if (state.mounted) DraftToggle(state.receiving, onReceiving, label = "목적지 자동 받기")
                else TButton("거치 모드로 전환하고 받기", enabled = !state.busy, onClick = onEnableReceiver)
                Text(state.receiveMessage, color = T.InkMuted)
                if (!state.overlayAllowed) TButton("다른 앱 위에 표시 허용", tone = ButtonTone.Secondary, onClick = onOverlay)
                TButton("연결 코드 만들기", enabled = !state.busy, onClick = onCreateCode)
                state.receiverCode?.let { Text(it, style = MaterialTheme.typography.headlineSmall, color = T.Electric) }
                Text("코드는 10분 동안 유효해요. 차량 등록·서비스 실행과 네이버지도 설치가 필요해요.", color = T.InkMuted)
            }
        } else {
            state.request?.let { request ->
                DestinationCard {
                    Text(request.destination.name, style = MaterialTheme.typography.titleMedium, color = T.Ink)
                    Text((if (request.selfTest) "폰 1대 테스트 · " else "") + destinationStatus(request.status), color = T.InkMuted)
                    if (request.status == "pending") TButton("전송 취소", tone = ButtonTone.Ghost, enabled = !state.busy, onClick = onCancel)
                    TButton("상태 새로고침", tone = ButtonTone.Ghost, enabled = !state.busy, onClick = onRefresh)
                }
            }
            DestinationCard {
                DraftField(state.query, onQuery, label = "주소 또는 가게 이름", enabled = !state.busy,
                    note = "검색 결과가 없으면 주소로 검색해 주세요")
                TButton("목적지 찾기", tone = ButtonTone.Secondary, enabled = !state.busy && state.query.trim().length >= 2, onClick = onSearch)
                state.results.forEach { place ->
                    TButton("${place.name}\n${place.address}", tone = ButtonTone.Ghost, onClick = { onSelect(place) })
                }
                state.selected?.let { place ->
                    Text(place.name, style = MaterialTheme.typography.titleMedium, color = T.Ink)
                    Text(place.address, color = T.InkMuted)
                }
                DraftField(state.minutes, onMinutes, label = "보낸 뒤 유효시간", suffix = "분", enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), note = "1~120분 · 시간이 지나면 자동으로 열지 않아요")
                TButton("차로 보내기", enabled = !state.busy && state.selected != null && state.receiverName != null,
                    onClick = { onSend(false) })
                Text(state.receiverName?.let { "받는 기기 · $it" } ?: "처음에는 받는 기기를 연결해 주세요", color = T.InkMuted)
                TButton("기기 연결·수신 설정", tone = ButtonTone.Ghost, onClick = { setup = true })
            }
            DestinationCard {
                Text("임시 · 폰 1대 테스트", style = MaterialTheme.typography.titleMedium, color = T.Ink)
                Text("선택한 목적지를 서버에 보낸 뒤, 이 폰에서 직접 받아요. 차량 탑승 없이 테스트할 수 있어요.", color = T.InkMuted)
                TButton("① 이 폰으로 전송", tone = ButtonTone.Secondary, enabled = !state.busy && state.selected != null,
                    onClick = { onSend(true) })
                if (!state.overlayAllowed) TButton("다른 앱 위에 표시 허용", tone = ButtonTone.Ghost, onClick = onOverlay)
                TButton("② 수신해서 네이버지도 열기", tone = ButtonTone.Secondary,
                    enabled = !state.busy && state.overlayAllowed, onClick = onReceiveTest)
                Text(state.receiveMessage, color = T.InkMuted)
            }
        }
    }
    }
    (state.error ?: state.connectionError ?: state.notice)?.let { message ->
        Snackbar(modifier = Modifier.padding(Space.md).semantics { liveRegion = LiveRegionMode.Polite }) { Text(message) }
    }
    }
}

/** 기존 카드 안에 입력·버튼 사이의 최소 간격을 둔다. */
@Composable
private fun DestinationCard(content: @Composable ColumnScope.() -> Unit) {
    TCard { Column(verticalArrangement = Arrangement.spacedBy(Space.xs), content = content) }
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

package com.wemade.teslamacro.feature.destination

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.ui.ViewModelFactory
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme

/** 홈 화면 위에서 주소 입력과 전송만 끝내도록 메인 화면과 별도 창을 쓴다. */
class DestinationQuickSendActivity : ComponentActivity() {
    /** 초기화·테마·전송 상태는 앱의 기존 저장소와 ViewModel을 그대로 사용한다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val app = application as TeslaMacroApplication
        setContent {
            val ready by app.ready.collectAsState()
            val initializationError by app.initializationError.collectAsState()
            val settings by app.container.settingsStore.settings.collectAsState(initial = AppSettings())
            TeslaMacroTheme(mode = settings.themeMode) {
                Surface(shape = RoundedCornerShape(Radius.card), color = T.Carbon) {
                    if (ready) {
                        val model: DestinationViewModel = viewModel(factory = ViewModelFactory(app.container))
                        DestinationQuickSendRoute(model, onClose = ::finish)
                    } else {
                        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(initializationError ?: "목적지 전송 준비 중…", color = T.Ink)
                            if (initializationError != null) TButton("다시 시도", onClick = app::retryInitialization)
                            TButton("닫기", tone = ButtonTone.Ghost, onClick = ::finish)
                        }
                    }
                }
            }
        }
    }
}

/** 연결 설정이 필요할 때도 메인 화면을 거치지 않고 같은 창에서 해결한다. */
@Composable
private fun DestinationQuickSendRoute(model: DestinationViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsState()
    var setup by rememberSaveable { mutableStateOf(false) }
    if (setup) {
        DestinationRoute(model, settingsOnly = true, onBack = { setup = false })
    } else {
        ObserveDestination(model)
        DestinationQuickSendScreen(state, model::queryChanged, { model.send(false) }, { setup = true }, onClose)
    }
}

/** 입력칸에 바로 초점을 주며 실패 시 입력을 유지해 같은 창에서 다시 보낼 수 있게 한다. */
@Composable
internal fun DestinationQuickSendScreen(
    state: DestinationUiState,
    onQuery: (String) -> Unit,
    onSend: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text("목적지 전송", style = MaterialTheme.typography.titleLarge, color = T.Ink)
        DraftField(
            value = state.query, onValueChange = onQuery, label = "주소 또는 장소명",
            modifier = Modifier.focusRequester(focus), enabled = !state.busy,
            placeholder = "예: 서울시청",
        )
        Text(
            state.receiverName?.let { "$it · ${state.minutes}분 동안 전달 대기" }
                ?: if (state.connectionChecked) "받는 기기를 연결해 주세요" else "받는 기기 확인 중…",
            style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
        )
        TButton(
            if (state.busy) "전송 중…" else "전송",
            enabled = !state.busy && DestinationPlace(state.query.trim()).valid() && state.minutes in 1..120,
            onClick = { keyboard?.hide(); onSend() },
        )
        state.error?.let { Text(it, color = T.Danger, style = MaterialTheme.typography.bodyMedium) }
        state.notice?.let { Text(it, color = T.Ok, style = MaterialTheme.typography.bodyMedium) }
        state.connectionError?.let { Text(it, color = T.Danger, style = MaterialTheme.typography.bodySmall) }
        Text("받는 기기의 네이버지도에서 검색해요.", color = T.InkMuted, style = MaterialTheme.typography.bodySmall)
        TButton("기기 연결·설정", tone = ButtonTone.Ghost, enabled = !state.busy, onClick = onSettings)
        TButton("닫기", tone = ButtonTone.Ghost, onClick = onClose)
    }
}

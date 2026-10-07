package com.wemade.teslamacro.feature.destination

import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.nav.DestinationPlace
import com.wemade.teslamacro.data.settings.ThemeMode
import com.wemade.teslamacro.ui.ViewModelFactory
import com.wemade.teslamacro.ui.component.DraftField
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme

/** 키보드와 간격을 두고 남은 화면 가운데 반투명 입력 카드만 표시한다. */
class DestinationQuickSendActivity : ComponentActivity() {
    /** 제목·설정은 두지 않고 키보드 전송, 바깥 터치와 시스템 뒤로가기를 사용한다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setGravity(Gravity.CENTER)
        setFinishOnTouchOutside(true)
        val app = application as TeslaMacroApplication
        setContent {
            val ready by app.ready.collectAsState()
            val initializationError by app.initializationError.collectAsState()
            val settings by app.container.settingsStore.settings.collectAsState(initial = null)
            LaunchedEffect(initializationError) {
                initializationError?.let {
                    Toast.makeText(this@DestinationQuickSendActivity, it, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            TeslaMacroTheme(mode = settings?.themeMode ?: ThemeMode.AUTO) {
                if (ready && settings != null) {
                    val model: DestinationViewModel = viewModel(factory = ViewModelFactory(app.container))
                    DestinationQuickSendRoute(model, onClose = ::finish)
                }
            }
        }
    }
}

/** 전송 성공 때만 창을 닫고 실패는 짧게 알린 뒤 입력을 유지한다. */
@Composable
private fun DestinationQuickSendRoute(model: DestinationViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    if (needsDestinationSetup(state)) ObserveDestination(model)
    LaunchedEffect(state.sendCompleted) {
        if (state.sendCompleted) {
            Toast.makeText(context, "전송했어요", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
    if (!needsDestinationSetup(state)) {
        com.wemade.teslamacro.ui.component.ActionFeedback(state.error, onDismiss = model::clearFeedback, useSnackbar = true)
    }
    DestinationQuickSendContent(state, model::queryChanged, model::send) {
        DestinationScreen(state, onBack = onClose, onMinutes = model::minutesChanged,
            onRefresh = model::refresh, onPairingCode = model::pairingCodeChanged,
            onPair = model::pair, onUnlink = model::unlink, onCreateCode = model::createPairCode,
            onReceiving = model::receivingChanged, onOverlay = model::allowOverlay, settingsOnly = true,
            onDismissFeedback = model::clearFeedback)
    }
}

/** 설정 이력만 읽고 입력창을 열며 서버 연결 확인은 전송 요청 때 수행한다. */
@Composable
internal fun DestinationQuickSendContent(
    state: DestinationUiState, onQuery: (String) -> Unit, onSend: () -> Unit,
    setup: @Composable () -> Unit,
) {
    when {
        needsDestinationSetup(state) -> setup()
        state.setupStarted == null && !state.connectionChecked -> Unit
        else -> DestinationQuickSendScreen(state, onQuery, onSend)
    }
}

/** 버튼 대신 IME 전송 키를 사용하며 전송 중에도 초점을 유지해 입력줄이 튀지 않게 한다. */
@Composable
internal fun DestinationQuickSendScreen(
    state: DestinationUiState,
    onQuery: (String) -> Unit,
    onSend: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.busy) {
        if (!state.busy) { focus.requestFocus(); keyboard?.show() }
    }
    val clear = T.Carbon.copy(alpha = 0f)
    Surface(
        shape = RoundedCornerShape(Radius.hero), color = T.Carbon.copy(alpha = 0.88f),
        border = BorderStroke(Stroke.hair, T.Hairline.copy(alpha = 0.5f)),
    ) {
        DraftField(
            value = state.query, onValueChange = { if (!state.busy) onQuery(it) }, label = null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm)
                .focusRequester(focus).semantics { contentDescription = "주소 또는 장소명" },
            placeholder = "주소·장소 입력",
            shape = RoundedCornerShape(Radius.button),
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Normal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = T.Ink, unfocusedTextColor = T.Ink,
                focusedPlaceholderColor = T.InkMuted, unfocusedPlaceholderColor = T.InkMuted,
                focusedBorderColor = clear, unfocusedBorderColor = clear,
                focusedContainerColor = clear, unfocusedContainerColor = clear,
                cursorColor = T.Electric,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (!state.busy && state.setupStarted != null && DestinationPlace(state.query.trim()).valid() && state.minutes in 1..120) onSend()
            }),
        )
    }
}

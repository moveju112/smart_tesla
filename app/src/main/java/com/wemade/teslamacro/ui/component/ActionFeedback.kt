package com.wemade.teslamacro.ui.component

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.wemade.teslamacro.ui.theme.Space

/** 짧은 결과는 Toast, 자세한 결과·후속 동작은 화면 위 Snackbar로 표시해 본문 위치를 보존한다. */
@Composable
fun ActionFeedback(
    message: String?,
    onDismiss: () -> Unit = {},
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    useSnackbar: Boolean = actionLabel != null,
) {
    if (message == null) return
    val context = LocalContext.current
    val dismiss by rememberUpdatedState(onDismiss)
    val action by rememberUpdatedState(onAction)
    var handled by rememberSaveable(message) { mutableStateOf(false) }
    if (handled) return
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message, useSnackbar) {
        if (!useSnackbar) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            handled = true
            dismiss()
        } else {
            val result = host.showSnackbar(message, actionLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            handled = true
            dismiss()
            if (result == SnackbarResult.ActionPerformed) action()
        }
    }
    if (useSnackbar) {
        val density = LocalDensity.current
        val bottomInset = maxOf(WindowInsets.safeDrawing.getBottom(density), WindowInsets.ime.getBottom(density))
        val margin = with(density) { Space.md.roundToPx() }
        val position = remember(bottomInset, margin) {
            object : PopupPositionProvider {
                /** 시트·본문의 크기와 무관하게 키보드와 시스템 탐색 영역 위에 알림을 놓는다. */
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                    (windowSize.width - popupContentSize.width) / 2,
                    (windowSize.height - popupContentSize.height - bottomInset - margin).coerceAtLeast(0),
                )
            }
        }
        Popup(popupPositionProvider = position) {
            SnackbarHost(host, Modifier.fillMaxWidth().padding(horizontal = Space.md))
        }
    }
}

package com.wemade.teslamacro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.layout.Pane
import com.wemade.teslamacro.ui.nav.Destination
import com.wemade.teslamacro.ui.nav.NavBar
import com.wemade.teslamacro.ui.nav.NavRail
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TeslaMacroTheme

/**
 * 스크린샷용 앱 껍데기.
 *
 * MainActivity와 **같은 방식으로** 폭을 재서 Pane을 내려보낸다.
 * 여기서 값을 손으로 넣으면 실제 앱과 다른 그림이 나와 테스트가 거짓말을 한다.
 */
@Composable
fun AppFrame(selected: Destination, dark: Boolean = false, content: @Composable () -> Unit) {
    // 스냅샷은 시계에 흔들리면 안 된다 — 낮/밤을 자동 판정에 맡기지 않고 못 박는다
    TeslaMacroTheme(dark = dark) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(T.Void)) {
            val pane = Pane.of(maxWidth)
            // MainActivity는 **방향**으로 레일/탭을 가른다(`bottomNav = portrait`).
            // 여기서 폭으로 갈랐더니 세로 태블릿(600×960dp)이 스냅샷에서는 레일,
            // 실제 앱에서는 하단 탭으로 나와 컷이 거짓말을 했다
            val portrait = maxHeight > maxWidth
            CompositionLocalProvider(LocalPane provides pane) {
                if (portrait) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) { content() }
                        NavBar(current = selected, onSelect = {})
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavRail(current = selected, onSelect = {})
                        Box(modifier = Modifier.weight(1f)) { content() }
                    }
                }
            }
        }
    }
}

/** 내비게이션 없이 화면 전체를 차지하는 배치 (등록·스플래시) */
@Composable
fun FullScreenFrame(dark: Boolean = false, content: @Composable () -> Unit) {
    TeslaMacroTheme(dark = dark) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(T.Void)) {
            CompositionLocalProvider(LocalPane provides Pane.of(maxWidth)) {
                Box(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

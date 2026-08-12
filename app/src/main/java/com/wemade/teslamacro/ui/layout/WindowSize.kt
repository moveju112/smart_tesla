package com.wemade.teslamacro.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면 폭 등급.
 *
 * Material3 WindowSizeClass 라이브러리를 따로 붙이지 않고
 * 루트에서 실제 폭을 재서 나눈다 — 기준이 두 개뿐이라 의존성을 늘릴 이유가 없다.
 */
enum class Pane {
    /** 폰 세로. 한 번에 한 덩어리만 보여준다 */
    Compact,

    /** 폰 가로 · 작은 태블릿. 두 단까지 */
    Medium,

    /** 태블릿 가로. 세 단까지 */
    Expanded;

    val isCompact: Boolean get() = this == Compact
    val columns: Int
        get() = when (this) {
            Compact -> 1
            Medium -> 2
            Expanded -> 3
        }

    companion object {
        /** 600/900dp는 안드로이드 표준 분기점이다 */
        fun of(width: Dp): Pane = when {
            width < 600.dp -> Compact
            width < 900.dp -> Medium
            else -> Expanded
        }
    }
}

/** 루트에서 한 번 재서 아래로 내려보낸다. 화면마다 다시 재지 않는다 */
val LocalPane: ProvidableCompositionLocal<Pane> = compositionLocalOf { Pane.Expanded }

/** 화면에서 짧게 쓰기 위한 별칭 */
val pane: Pane
    @Composable get() = LocalPane.current

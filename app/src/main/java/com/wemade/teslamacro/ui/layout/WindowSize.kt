package com.wemade.teslamacro.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면 방향과 폭에 따른 본문 배치 등급.
 *
 * Material3 WindowSizeClass 라이브러리를 따로 붙이지 않고
 * 루트에서 실제 폭을 재서 나눈다 — 기준이 두 개뿐이라 의존성을 늘릴 이유가 없다.
 */
enum class Pane {
    /** 기기 크기에 관계없이 세로는 한 덩어리씩, 좁은 가로도 한 열로 보여준다 */
    Compact,

    /** 폰 가로 · 작은 태블릿 가로. 두 단까지 */
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
        /** 세로는 한 열로 고정하고 가로에서만 기존 600/900dp 분기점을 적용한다. */
        fun of(width: Dp, portrait: Boolean): Pane = when {
            portrait || width < 600.dp -> Compact
            width < 900.dp -> Medium
            else -> Expanded
        }
    }
}

/** 루트에서 한 번 재서 아래로 내려보낸다. 기본값은 Compact — 좁게 시작해야 넘침이 없다 */
val LocalPane: ProvidableCompositionLocal<Pane> = compositionLocalOf { Pane.Compact }

/** 화면에서 짧게 쓰기 위한 별칭 */
val pane: Pane
    @Composable get() = LocalPane.current

package com.wemade.teslamacro.ui.nav

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/**
 * 사용 빈도 순으로 나열한 화면 목록. 번호 대신 기능 이름으로 이동한다.
 */
enum class Destination(val route: String, val label: String) {
    // 이전 버전의 저장 상태와 화면 테스트가 읽을 수 있게 값은 남기되 실제 목차에서는 제외한다.
    Dashboard("dashboard", "제어"),
    Macros("macros", "매크로"),
    Features("features", "기능"),
    Settings("settings", "설정"),
    ;

    /** 실제 목차에 보이는 두 화면의 시트 번호. */
    val sheet: Int get() = visible.indexOf(this).takeIf { it >= 0 }?.plus(1) ?: 1

    companion object {
        /** 제어 화면을 뺀 실제 앱 목차. */
        val visible = listOf(Features, Settings)
    }
}

/** 루트가 안전 영역을 처리하므로 하단 탭은 콘텐츠 높이만 차지한다. */
@Composable
fun NavBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = T.Carbon,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Destination.visible.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        if (destination == Destination.Settings) DraftMark.Settings else DraftMark.Automation,
                        contentDescription = null,
                        modifier = Modifier.size(Space.lg),
                    )
                },
                label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = T.Electric,
                    selectedTextColor = T.Electric,
                    indicatorColor = T.ElectricFaint,
                    unselectedIconColor = T.InkMuted,
                    unselectedTextColor = T.InkMuted,
                ),
            )
        }
    }
}

/** 태블릿은 같은 두 목적지를 왼쪽 레일로 옮겨 본문 폭을 확보한다. */
@Composable
fun NavRail(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight().width(Space.xxl * 2 + Space.md),
        containerColor = T.Carbon,
        windowInsets = WindowInsets(0, 0, 0, 0),
        header = {
            Text(
                "Smart Tesla",
                style = MaterialTheme.typography.labelMedium,
                color = T.InkMuted,
                modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.md),
            )
        },
    ) {
        Destination.visible.forEach { destination ->
            NavigationRailItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        if (destination == Destination.Settings) DraftMark.Settings else DraftMark.Automation,
                        contentDescription = null,
                        modifier = Modifier.size(Space.lg),
                    )
                },
                label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = T.Electric,
                    selectedTextColor = T.Electric,
                    indicatorColor = T.ElectricFaint,
                    unselectedIconColor = T.InkMuted,
                    unselectedTextColor = T.InkMuted,
                ),
            )
            Spacer(Modifier.height(Space.sm))
        }
    }
}

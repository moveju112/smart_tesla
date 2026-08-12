package com.wemade.teslamacro.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 화면 목록. 탭 순서는 사용 빈도 순이다 */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "제어", Icons.Filled.AcUnit),
    Macros("macros", "매크로", Icons.Filled.Bolt),
    Settings("settings", "설정", Icons.Filled.Settings),
}

/**
 * 하단 탭 (폰 세로).
 * 좁은 화면에서 96dp 레일은 본문을 너무 먹는다. 엄지도 하단이 닿기 쉽다.
 */
@Composable
fun NavBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 상단에 얇은 경계선 한 겹 — 본문과 탭바를 분리
            .drawBehind {
                drawRect(T.Carbon)
                drawRect(T.Hairline, size = size.copy(height = 1f))
            }
            .padding(horizontal = Space.sm, vertical = Space.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.entries.forEach { destination ->
            NavItem(
                destination = destination,
                selected = destination == current,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(destination) },
            )
        }
    }
}

/** 탭 한 칸. 선택되면 알약형 강조 배경 + 액센트 아이콘 */
@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (selected) T.ElectricFaint else Color.Transparent)
                .padding(horizontal = Space.lg, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                tint = if (selected) T.ElectricBright else T.InkFaint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) T.Ink else T.InkFaint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 좌측 세로 내비게이션 (태블릿).
 * 가로가 넓어 하단 탭보다 레일이 낫다. 엄지 도달 범위도 화면 좌측이 유리하다.
 */
@Composable
fun NavRail(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(T.Carbon)
            .padding(vertical = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Destination.entries.forEach { destination ->
            val selected = destination == current
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.sm)
                    .background(
                        if (selected) T.ElectricFaint else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(Radius.card),
                    )
                    .clickable { onSelect(destination) }
                    .padding(vertical = Space.sm, horizontal = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.label,
                    tint = if (selected) T.Electric else T.InkFaint,
                    modifier = Modifier.size(24.dp),
                )
                Box(modifier = Modifier.padding(top = Space.xs)) {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) T.Ink else T.InkFaint,
                    )
                }
            }
        }
    }
}

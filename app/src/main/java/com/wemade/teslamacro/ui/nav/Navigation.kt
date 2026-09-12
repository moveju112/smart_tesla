package com.wemade.teslamacro.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.Radius

/**
 * 사용 빈도 순으로 나열한 화면 목록. 번호 대신 기능 이름으로 이동한다.
 */
enum class Destination(val route: String, val label: String) {
    // 이전 버전의 저장 상태와 화면 테스트가 읽을 수 있게 값은 남기되 실제 목차에서는 제외한다.
    Dashboard("dashboard", "제어"),
    Macros("macros", "매크로"),
    Settings("settings", "설정"),
    ;

    /** 실제 목차에 보이는 두 화면의 시트 번호. */
    val sheet: Int get() = visible.indexOf(this).takeIf { it >= 0 }?.plus(1) ?: 1

    companion object {
        /** 제어 화면을 뺀 실제 앱 목차. */
        val visible = listOf(Macros, Settings)
    }
}

/**
 * 휴대폰에서는 엄지가 닿는 하단에 두 화면의 이동 탭을 둔다.
 */
@Composable
fun NavBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 그리기 람다 안에서는 색 토큰을 못 읽는다 — 바깥에서 꺼내 둔다
    val barColor = T.Carbon
    val lineColor = T.Hairline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(barColor)
                // 본문과 탭을 가르는 괘선
                drawRect(lineColor, size = size.copy(height = 1f))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.visible.forEach { destination ->
            SheetTab(
                destination = destination,
                selected = destination == current,
                vertical = false,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(destination) },
            )
        }
    }
}

/**
 * 넓은 화면의 좌측 탐색 영역. 아이콘과 이름을 함께 표시한다.
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
            .width(112.dp)
            .background(T.Carbon)
            .padding(vertical = Space.md),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Smart Tesla",
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
            modifier = Modifier.padding(start = Space.md, bottom = Space.sm),
        )
        Box(
            Modifier
                .padding(horizontal = Space.md)
                .fillMaxWidth()
                .height(Stroke.thin)
                .background(T.Hairline)
        )
        Spacer(Modifier.height(Space.sm))
        Destination.visible.forEach { destination ->
            SheetTab(
                destination = destination,
                selected = destination == current,
                vertical = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelect(destination) },
            )
        }
    }
}

/**
 * 선택한 화면은 채운 배경과 강조색으로 표시한다.
 */
@Composable
private fun SheetTab(
    destination: Destination,
    selected: Boolean,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.sm, vertical = Space.xs)
            .clip(RoundedCornerShape(Radius.tile))
            .background(if (selected) T.ElectricFaint else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (destination == Destination.Settings) DraftMark.Settings else DraftMark.Automation,
                contentDescription = null,
                tint = if (selected) T.Electric else T.InkMuted,
                modifier = Modifier.size(Space.lg),
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) T.Electric else T.InkMuted,
            )
        }
    }
}

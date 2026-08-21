package com.wemade.teslamacro.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.CalloutNumberStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T

/**
 * 도면집의 시트 목록.
 *
 * 아이콘을 쓰지 않는다. 도면집은 아이콘이 아니라 **시트 번호**로 넘긴다 —
 * "SHEET 2 / 3"처럼. 번호가 있으면 몇 장 중 몇 번째인지도 함께 알 수 있어
 * 아이콘 세 개보다 정보가 많다.
 *
 * 순서는 사용 빈도 순이다.
 */
enum class Destination(val route: String, val label: String) {
    Dashboard("dashboard", "제어"),
    Macros("macros", "매크로"),
    Settings("settings", "설정"),
    ;

    /** 시트 번호. 1부터 센다 */
    val sheet: Int get() = ordinal + 1
}

/** 도면집의 총 장수. 시트 번호 옆에 "/ 3"으로 붙는다 */
private val SHEET_COUNT = Destination.entries.size

/**
 * 좁은 화면의 시트 탭 (하단 가로).
 *
 * 세로에선 레일이 본문 폭을 너무 먹는다. 시트가 3장뿐이라 하단이 낫고 엄지도 닿기 쉽다.
 * 지금 시트는 위쪽 굵은 선으로 표시한다 — 도면집에서 펼쳐진 장을 가리키는 방식이다.
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
        Destination.entries.forEachIndexed { index, destination ->
            if (index > 0) {
                // 시트 사이 괘선 — 표제란과 같은 방식으로 칸을 나눈다
                Box(
                    Modifier
                        .width(Stroke.thin)
                        .height(36.dp)
                        .background(T.Hairline)
                )
            }
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
 * 넓은 화면의 시트 목록 (좌측 세로).
 *
 * 도면집 표지의 시트 목차다. 번호와 이름이 한 줄로 눕고, 지금 펼친 장에만
 * 왼쪽에 굵은 선이 선다 — 색이 아니라 선 굵기로 표시하는 게 이 세계의 방식이다.
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
            text = "도면",
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
        Destination.entries.forEach { destination ->
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
 * 시트 한 장.
 *
 * @param vertical 세로 목차(레일)인지 가로 탭(하단)인지. 지금 시트를 가리키는 선의
 *   방향이 달라진다 — 목차는 왼쪽에, 탭은 위쪽에 선다
 */
@Composable
private fun SheetTab(
    destination: Destination,
    selected: Boolean,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val markColor = if (selected) T.Ink else Color.Transparent
    val markPx = Stroke.bold
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .drawBehind {
                if (markColor == Color.Transparent) return@drawBehind
                val thickness = markPx.toPx()
                if (vertical) {
                    drawRect(markColor, size = size.copy(width = thickness))
                } else {
                    drawRect(markColor, size = size.copy(height = thickness))
                }
            }
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (vertical) Arrangement.Start else Arrangement.Center,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "${destination.sheet} / $SHEET_COUNT",
                style = CalloutNumberStyle,
                color = if (selected) T.Ink else T.InkFaint,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) T.Ink else T.InkFaint,
            )
        }
    }
}

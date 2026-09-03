package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.CalloutNumberStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T

/**
 * 표제란(title block).
 *
 * 도면에서 "이게 무슨 도면이고 언제 것인가"는 큰 제목이 아니라 시트 하단의
 * 표제란에 작게 적힌다. 이 앱도 그렇게 한다 — 차 이름과 연결 상태가
 * 화면 상단을 먹지 않고 아래 한 줄에 눕는다.
 *
 * 칸 사이는 세로 괘선으로 나눈다. 여백으로 나누면 도면이 아니라 그냥 문단이 된다.
 */
@Composable
fun TitleBlock(
    fields: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    /**
     * 한 줄에 넣을 칸 수. null이면 폭에 맞춰 한 줄로 흘린다.
     *
     * 좁은 화면에서는 반드시 지정한다 — 흘려 보내면 줄이 어디서 접힐지 몰라
     * 새 줄이 괘선으로 시작하거나 앞 줄 끝에 괘선만 남는다.
     */
    perRow: Int? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rows = fields.chunked(perRow ?: fields.size)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(T.Carbon)
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        Spacer(Modifier.width(Space.md))
                        // 괘선 — 표제란의 칸 경계. 여백으로 나누면 도면이 아니라 그냥 문단이 된다
                        Box(
                            Modifier
                                .width(Stroke.thin)
                                .height(18.dp)
                                .background(T.Hairline)
                        )
                        Spacer(Modifier.width(Space.md))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = T.InkFaint,
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleSmall,
                        color = T.InkMuted,
                        maxLines = 1,
                    )
                }
                if (trailing != null && rowIndex == rows.lastIndex) {
                    Spacer(Modifier.width(Space.md))
                    trailing()
                }
            }
        }
    }
}

/**
 * 부품번호 — 도면과 값 표를 잇는 유일한 끈.
 *
 * 도면에서는 지시선 끝에 원을 그리고 그 안에 번호를 적는다.
 * 값 목록의 같은 번호를 보면 그 값이 차의 어디 것인지 곧바로 안다.
 *
 * @param highlighted 이 번호가 지금 봐야 할 것인지. 원이 채워진다
 */
@Composable
fun CalloutNumber(
    number: Int,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    accent: Color = T.Ink,
) {
    // 원 크기를 글자에서 뽑는다. 18dp로 고정했더니 시스템 글자 크기 1.3배에서
    // 두 자리 중 뒷자리가 잘려 "03"이 "0"으로 보였다 —
    // sp를 dp로 풀면 사용자 글자 크기 설정이 원 지름에도 그대로 반영된다
    val diameter = with(LocalDensity.current) { CalloutNumberStyle.fontSize.toDp() * 1.75f }
    Box(
        modifier = modifier
            .size(diameter)
            .background(if (highlighted) accent else Color.Transparent, CircleOutline)
            .border(Stroke.thin, accent, CircleOutline),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            style = CalloutNumberStyle,
            color = if (highlighted) T.Void else accent,
        )
    }
}

/** 원번호의 원. 도면 지시선의 관례라 여기만 실제로 둥글다 */
private val CircleOutline = androidx.compose.foundation.shape.CircleShape


/**
 * 표 머리글.
 *
 * 열 비율을 호출부와 나눠 갖는다 — 머리글과 본문이 다른 비율을 쓰면 표가 아니라
 * 두 개의 줄이 된다. 머리글 아래는 굵은 괘선으로 닫는다(도면 표의 관례).
 *
 * @param columns 열 이름과 weight 쌍. 본문 행이 쓰는 값과 같아야 한다
 */
@Composable
fun TableHeader(
    columns: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            columns.forEach { (name, weight) ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = T.InkFaint,
                    modifier = Modifier.weight(weight),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(Stroke.thin)
                .background(T.Ink)
        )
    }
}

/**
 * 켜짐/꺼짐 표시 — 도면의 상태 기호.
 *
 * Material 스위치를 쓰지 않는다. 알약형 트랙과 미끄러지는 손잡이는 이 세계의 문법이
 * 아니고, 무엇보다 **켜짐인지 꺼짐인지 색으로만 갈린다** — 도면은 채움으로 갈린다.
 * 채운 사각 = 켜짐, 빈 사각 = 꺼짐. 옆에 상태어를 함께 적어 색맹이어도 읽힌다.
 */
@Composable
fun DraftToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(if (checked) T.Ink else Color.Transparent)
                .border(Stroke.thin, if (checked) T.Ink else T.InkFaint),
        )
        if (label != null) {
            Spacer(Modifier.width(Space.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (checked) T.Ink else T.InkFaint,
                maxLines = 1,
            )
        }
    }
}

/**
 * 구획 — 괘선 하나로 나뉜 글 묶음.
 *
 * 채운 면으로 구획하지 않는다. 채움이 쌓이면 판이 카드 목록이 되고,
 * 이 세계의 약속은 "층은 괘선으로만 생긴다"였다.
 *
 * @param tone 괘선 색. 주의 안내는 경보 잉크로, 평상 안내는 잉크로 긋는다
 */
@Composable
fun Modifier.draftBlock(tone: Color = T.Ink): Modifier {
    val height = Stroke.bold
    return this.drawBehind { drawRect(tone, size = size.copy(height = height.toPx())) }
        .padding(top = Space.sm)
}

/**
 * 기입란 — 사람이 값을 적어 넣는 칸.
 *
 * Material의 `OutlinedTextField`를 쓰지 않는다. 네 면을 감싼 둥근 테두리에
 * 라벨이 홈을 파고 떠 있는 그 모양은 안드로이드 기본 부품의 서명 그 자체라,
 * 다른 걸 다 바꿔도 저 칸 하나가 "머티리얼 앱"이라고 말한다.
 *
 * 도면의 기입란은 **라벨이 위에 적히고 값은 밑줄 위에 앉는다.** 서식이 그렇게 생겼다.
 * 초점이 오면 밑줄이 굵어진다 — 색을 쓰지 않고 선 굵기로 말하는 방식이다.
 */
@Composable
fun DraftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** 단위. 밑줄 오른쪽 끝에 붙는다 — 도면이 치수에 단위를 적는 자리다 */
    suffix: String? = null,
    /** 주기(註記). 밑줄 아래 작게 붙는다 */
    note: String? = null,
    /** 빈 기입란이 공백처럼 보이지 않도록 값이 들어갈 자리를 직접 알려준다 */
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val rule = if (focused) Stroke.bold else Stroke.thin
    val ruleColor = when {
        isError -> T.Danger
        !enabled -> T.Hairline
        focused -> T.Ink
        else -> T.InkFaint
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) T.Danger else T.InkFaint,
        )
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) T.Ink else T.InkFaint,
                ),
                cursorBrush = SolidColor(T.Ink),
                interactionSource = remember { MutableInteractionSource() }
                    .also { source ->
                        val isFocused by source.collectIsFocusedAsState()
                        focused = isFocused
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.sm),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = T.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp),
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = T.InkFaint,
                    modifier = Modifier.padding(start = Space.sm, bottom = Space.sm),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(rule)
                .background(ruleColor)
        )
        if (note != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

/**
 * 도면 기호.
 *
 * Material 아이콘을 쓰지 않는다 — 둥근 모서리와 채워진 면이 도면 문법과 어긋난다.
 * 여기 있는 것들은 전부 직선과 직각으로만 그린 1.5dp 단일 굵기 기호다.
 *
 * 제도에서 쓰는 기호를 그대로 가져왔다: 취소는 대각 두 선, 추가는 십자,
 * 삭제는 사선 하나, 방향은 삼각 화살촉.
 */
object DraftMark {

    val Close: ImageVector = mark {
        moveTo(5f, 5f); lineTo(19f, 19f)
        moveTo(19f, 5f); lineTo(5f, 19f)
    }

    val Add: ImageVector = mark {
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(4f, 12f); lineTo(20f, 12f)
    }

    val Minus: ImageVector = mark {
        moveTo(4f, 12f); lineTo(20f, 12f)
    }

    /**
     * 삭제 = 도면의 취소 표시. 항목을 지우지 않고 상자에 사선을 그어 무효로 만든다.
     * 사선만 그으면 그냥 빗금으로 보이고, 상자가 있어야 "이 항목이 취소됨"으로 읽힌다.
     */
    val Strike: ImageVector = mark {
        moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 19f); lineTo(5f, 19f); lineTo(5f, 5f)
        moveTo(5f, 19f); lineTo(19f, 5f)
    }

    /** 지시선 화살촉 — 위 */
    val ArrowUp: ImageVector = mark {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
    }

    val ArrowDown: ImageVector = mark {
        moveTo(12f, 19f); lineTo(12f, 5f)
        moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f)
    }

    val ArrowRight: ImageVector = mark {
        moveTo(6f, 12f); lineTo(18f, 12f)
        moveTo(12f, 6f); lineTo(18f, 12f); lineTo(12f, 18f)
    }

    /** 펼침 — 아래를 향한 화살촉만. 선은 없다 */
    val Expand: ImageVector = mark {
        moveTo(6f, 10f); lineTo(12f, 16f); lineTo(18f, 10f)
    }

    /** 실행 = 도면의 방향 표시. 채운 삼각이 아니라 윤곽 삼각이다 */
    val Run: ImageVector = mark {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); lineTo(8f, 5f)
    }

    /**
     * 지목 — 목록에서 "이것"을 가리키는 표시.
     * ★ 글리프를 쓰면 폰트에 따라 모양이 달라지고, 별은 도면 기호가 아니다.
     * 도면은 지목할 때 삼각 지시자를 쓴다.
     */
    val Pointer: ImageVector = mark {
        moveTo(6f, 6f); lineTo(18f, 12f); lineTo(6f, 18f); lineTo(6f, 6f)
    }

    val More: ImageVector = mark {
        moveTo(5f, 12f); lineTo(7f, 12f)
        moveTo(11f, 12f); lineTo(13f, 12f)
        moveTo(17f, 12f); lineTo(19f, 12f)
    }
}

/**
 * 도면 기호 하나를 만든다.
 *
 * 전부 같은 규칙을 강제한다 — 24dp 격자, 1.5dp 단일 굵기, 사각 끝단, 채움 없음.
 * 굵기를 인자로 열지 않은 건 일부러다. 기호가 굵기별로 갈리면 도면이 아니게 된다.
 */
private fun mark(pathBlock: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathBuilder().apply(pathBlock).nodes,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Square,
        )
    }.build()

/**
 * 해칭 — 지금 작동 중인 부위를 채우는 사선 무늬.
 *
 * 색만 바꾸면 흘깃 볼 때 안 걸린다. 도면은 색이 아니라 무늬로 재질과 상태를 말한다.
 * 앱에서 유일하게 움직이는 것이기도 하다 — 사선이 천천히 흐른다.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.hatch(
    rect: androidx.compose.ui.geometry.Rect,
    color: Color,
    spacingPx: Float,
    strokePx: Float,
    /** 0f~1f. 무늬가 흐르는 위상 */
    phase: Float = 0f,
) {
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        val offset = phase * spacingPx
        var x = rect.left - rect.height - spacingPx + offset
        while (x < rect.right + rect.height) {
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, rect.bottom),
                end = androidx.compose.ui.geometry.Offset(x + rect.height, rect.top),
                strokeWidth = strokePx,
                cap = StrokeCap.Square,
            )
            x += spacingPx
        }
    }
}

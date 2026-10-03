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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AirlineSeatReclineNormal
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.CalloutNumberStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T

/** 차량명과 연결 상태를 간결한 보조 정보로 표시한다. */
@Composable
fun TitleBlock(
    fields: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    /** 좁은 화면에서 한 줄에 배치할 항목 수. */
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
                        // 읽기 순서를 유지하면서 항목 사이만 구분한다.
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

/** 값 표와 차량 위치를 연결하는 번호. 강조 여부는 면과 글자색으로 함께 표시한다. */
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

/** 강조 번호의 원형 배경. */
private val CircleOutline = androidx.compose.foundation.shape.CircleShape


/** 본문과 동일한 열 비율을 사용하는 표 머리글. */
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

/** 손잡이 위치와 상태어로 켜짐 여부를 함께 전달한다. */
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
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange)
            .padding(end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = T.Slate,
                uncheckedThumbColor = T.InkMuted,
                uncheckedBorderColor = T.Hairline,
            ),
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

/** 보조 콘텐츠를 상단 경계로 구분한다. */
@Composable
fun Modifier.draftBlock(tone: Color = T.Ink): Modifier {
    val height = Stroke.bold
    return this.drawBehind { drawRect(tone, size = size.copy(height = height.toPx())) }
        .padding(top = Space.sm)
}

/** 라벨과 입력 면을 분리해 휴대폰에서도 입력 위치를 쉽게 찾는다. */
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
    /** 단위는 입력값 오른쪽에 표시한다. */
    suffix: String? = null,
    /** 입력칸 아래에 표시하는 보조 설명. */
    note: String? = null,
    /** 빈 기입란이 공백처럼 보이지 않도록 값이 들어갈 자리를 직접 알려준다 */
    placeholder: String? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val rule = if (focused) Stroke.bold else Stroke.thin
    val ruleColor = when {
        isError -> T.Danger
        !enabled -> T.Hairline
        focused -> T.Electric
        else -> T.Hairline
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) T.Danger else T.InkFaint,
        )
        Spacer(Modifier.height(Space.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(T.Slate, RoundedCornerShape(Radius.button))
                .border(rule, ruleColor, RoundedCornerShape(Radius.button))
                .padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) T.Ink else T.InkFaint,
                ),
                cursorBrush = SolidColor(T.Electric),
                interactionSource = interactionSource,
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
                    .heightIn(min = Space.xxl)
                    // 라벨은 입력칸 밖에 있으므로 빈 필드에서도 TalkBack이 입력 목적과 오류를 읽어야 한다.
                    .semantics {
                        contentDescription = label
                        if (isError) error("$label 입력을 확인해 주세요")
                    },
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = T.InkFaint,
                    modifier = Modifier.padding(start = Space.sm),
                )
            }
        }
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

/** 앱 전체에서 사용하는 Material Rounded 동작 아이콘. 기존 이름을 유지한다. */
object DraftMark {
    val Close: ImageVector = Icons.Rounded.Close
    val Add: ImageVector = Icons.Rounded.Add
    val Minus: ImageVector = Icons.Rounded.Remove
    val Strike: ImageVector = Icons.Rounded.Delete
    val Edit: ImageVector = Icons.Rounded.Edit
    val Automation: ImageVector = Icons.Rounded.Bolt
    val Settings: ImageVector = Icons.Rounded.Settings
    val ArrowUp: ImageVector = Icons.Rounded.ArrowUpward
    val ArrowDown: ImageVector = Icons.Rounded.ArrowDownward
    val ArrowLeft: ImageVector = Icons.Rounded.ArrowBack
    val Folder: ImageVector = Icons.Rounded.Folder
    val ArrowRight: ImageVector = Icons.Rounded.ArrowForward
    val Expand: ImageVector = Icons.Rounded.ExpandMore
    val Run: ImageVector = Icons.Rounded.PlayArrow
    val Pointer: ImageVector = Icons.Rounded.DirectionsCar
    val More: ImageVector = Icons.Rounded.MoreHoriz

    val Seat: ImageVector = Icons.Rounded.AirlineSeatReclineNormal
    val Climate: ImageVector = Icons.Rounded.Thermostat
    val Lock: ImageVector = Icons.Rounded.Lock
    val Charge: ImageVector = Icons.Rounded.EvStation
    val Search: ImageVector = Icons.Rounded.Search
    val ChevronRight: ImageVector = Icons.Rounded.ChevronRight
    val Calendar: ImageVector = Icons.Rounded.CalendarToday
    val Location: ImageVector = Icons.Rounded.LocationOn
    val Check: ImageVector = Icons.Rounded.Check
}

/** 차량 상태 그림에서 냉방·난방 구역을 선 패턴으로 구별한다. */
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

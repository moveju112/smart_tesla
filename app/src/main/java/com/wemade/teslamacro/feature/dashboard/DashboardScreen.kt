/*
 * THESIS: 차의 상태를 목록이 아니라 차의 모양으로 말한다. 부위 하나가 곧 값이고 곧 조작이다.
 *   카드를 격자에 늘어놓는 대시보드 배치와, 큰 숫자 하나에 라벨을 붙인 히어로 타일을 거부한다.
 * OWN-WORLD: 제도지(#F2F0E9)에 단색 잉크(#1A1A17). 밤엔 같은 도면의 청사진 네거티브(#101619).
 *   모서리 0dp, 선 굵기 0.5·1·2dp 3계층, 유채색은 도면 정정 2색(적 #C8321E · 청 #1F5C8C)뿐.
 *   카드가 없다. 판은 종이와 같은 색이고 층은 괘선으로만 생긴다.
 * STORY: 흘깃 봐서 "색이 없다 = 괜찮다"를 알고, 이상한 부위에 든 잉크를 보고 어디인지 안다.
 *   그 부위를 누르면 옆에 상세도가 펼쳐지고, 조작하고 닫으면 도면으로 돌아온다.
 * FIRST VIEWPORT: 좌측 68%가 작도 영역 — Model Y 평면 선도가 실물 비례 2.47:1로 앉고,
 *   상단 3개·하단 2개 지시선이 부위에서 라벨로 뻗는다. 우측 32%는 치수 기입란이고
 *   실내 온도가 화면 최대 글자로 앉는다. 하단은 표제란 한 줄. 주 조작은 부위 탭이다.
 * FORM: 정비 매뉴얼 분해도. 자체 후보 목록 6번째(굴림 배정). 시드 키 80e949b2.
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
 */
package com.wemade.teslamacro.feature.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.model.Door
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import com.wemade.teslamacro.domain.model.TirePosition
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.CalloutNumber
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.Hairline
import com.wemade.teslamacro.ui.component.IndeterminateBar
import com.wemade.teslamacro.ui.component.InlineBanner
import com.wemade.teslamacro.ui.component.LevelSelector
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TitleBlock
import com.wemade.teslamacro.ui.component.ToggleRow
import com.wemade.teslamacro.ui.layout.LocalPane
import com.wemade.teslamacro.ui.layout.Pane
import com.wemade.teslamacro.ui.theme.ColorRole
import com.wemade.teslamacro.ui.theme.HeroValueStyle
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Stroke
import com.wemade.teslamacro.ui.theme.T
import com.wemade.teslamacro.ui.theme.TileValueStyle

/** 목표 도달로 볼 여유 폭(℃). 차 온도계가 0.1씩 흔들려 딱 맞을 때만 도달로 보면 색이 깜빡인다 */
private const val REACHED_MARGIN_C = 1.0

/** 좌우로 나눌 때 작도 영역이 먹는 비율. 나머지가 치수 기입란이다 */
private const val PLAN_WEIGHT = 0.68f

/**
 * 위아래로 쌓을 때 작도 영역이 먹는 비율.
 *
 * 세로에서는 차가 전폭으로 앉아 높이를 폭의 1/2.47만 쓴다 — 좌우 배치와 같은 0.68을 주면
 * 선도 위아래로 200dp가 그냥 빈다. 남는 높이는 기입란에 주는 게 낫다:
 * 이 기기는 **주로 세로로 쓰이고**, 그러면 기입 치수가 화면의 주인공이다.
 */
private const val PLAN_WEIGHT_STACKED = 0.46f

/**
 * 제어 화면 — 시트 1. 계기판이 아니라 **도면**이다.
 *
 * 이 화면은 대부분의 시간 동안 아무도 안 만진다. 매크로가 알아서 다 하기 때문이다.
 * 그래서 목표는 "빨리 누르기"가 아니라 **"안 만지고 알아채기"** 다.
 *
 * 도면을 고른 이유가 그것이다:
 * - **정상이면 전체가 단색 윤곽선이다.** 색이 하나 뜨면 그게 곧 소식이다 —
 *   "정상은 조용해야 한다"가 절제가 아니라 세계의 구조 자체가 된다
 * - **위치가 라벨이다.** "문 열림"을 읽고 어느 문인지 다시 생각하는 단계가 없다
 * - **조작은 옆에 상세도로 펼쳐진다.** 시트가 화면을 덮지 않아 도면이 계속 보인다
 * - 스크롤이 없다. 차에 고정된 화면에서 스크롤은 못 본 정보를 만든다
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
    onRetryConnect: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit = { _, _, _ -> },
    onStealthCharging: (Boolean) -> Unit = {},
) {
    val compact = LocalPane.current.isCompact
    var selected by remember { mutableStateOf<CarPart?>(null) }

    // 상세도가 열려 있으면 Back이 그걸 닫는다. 없으면 서명 상호작용 한가운데서 앱이 꺼진다
    BackHandler(enabled = selected != null) { selected = null }

    Column(modifier = modifier.fillMaxSize()) {
        // 명령이 오가는 동안 맨 위에 얇은 선이 흐른다. 누른 게 먹었는지 즉시 안다
        IndeterminateBar(active = state.isBusy)
        InlineBanner(message = state.errorMessage, onDismiss = onDismissError)

        // 폭에 따라 작도 영역과 기입란을 **좌우로 나눌지 위아래로 쌓을지** 가른다.
        //
        // 실기기를 세로로 돌리면 600dp가 되는데, 그때 좌우로 나누면 작도 영역이 408dp가 되어
        // 차가 손톱만큼 작아지고 남은 높이가 통째로 빈다. 지시선은 화면 절반을 가로질러
        // 무엇을 가리키는지 알 수 없게 된다. 좁으면 눕히는 게 아니라 **쌓아야** 한다.
        val stacked = LocalPane.current == Pane.Medium
        val detail: @Composable () -> Unit = {
            if (selected == null) {
                DimensionPanel(state, compact = compact, stacked = stacked) { selected = it }
            } else {
                DetailView(
                    part = selected!!,
                    state = state,
                    onClose = { selected = null },
                    onCommand = onCommand,
                    onSeatClimate = onSeatClimate,
                    onStealthCharging = onStealthCharging,
                )
            }
        }

        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                DrawingArea(
                    state = state,
                    selected = selected,
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxWidth().weight(PLAN_WEIGHT_STACKED),
                )
                // 괘선 — 작도 영역과 기입란의 경계
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Stroke.thin)
                        .background(T.Hairline)
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f - PLAN_WEIGHT_STACKED)) { detail() }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 가장 좁은 화면에서는 선도를 접고 값만 남긴다. 둘을 함께 줄이면 둘 다 못 읽는다
                if (!compact) {
                    DrawingArea(
                        state = state,
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.weight(PLAN_WEIGHT).fillMaxHeight(),
                    )
                    Box(
                        Modifier
                            .width(Stroke.thin)
                            .fillMaxHeight()
                            .background(T.Hairline)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(if (compact) 1f else 1f - PLAN_WEIGHT)
                        .fillMaxHeight()
                ) { detail() }
            }
        }

        // 표제란 — 차 이름과 연결 상태는 도면 하단에 적힌다. 상단을 먹지 않는다
        Hairline()
        TitleBlock(
            // 좁으면 두 칸씩 접는다. 흘려 보내면 괘선이 줄 경계에 걸린다
            perRow = if (compact) 2 else null,
            fields = buildList {
                add("차량" to state.vehicleName)
                add("갱신" to state.lastUpdatedLabel)
                add("자동화" to state.automationLabel)
                add("연결" to state.connectionLabel)
            },
            trailing = {
                if (state.link is LinkState.Failed) {
                    TButton(
                        text = "다시 연결",
                        tone = ButtonTone.Secondary,
                        fillWidth = false,
                        small = true,
                        onClick = onRetryConnect,
                    )
                }
            },
        )
    }
}

/**
 * 작도 영역 — 선도와 지시선, 그리고 지시선 끝의 라벨.
 *
 * 라벨을 weight로 균등 분할한 칸에 놓는다. 그러면 라벨의 실제 폭을 모르고도
 * 각 칸의 중심 x를 계산할 수 있어 지시선이 정확히 그 칸으로 뻗는다 —
 * 측정 후 배치를 하려면 두 번 그려야 하고, 그러면 스냅샷이 흔들린다.
 */
@Composable
private fun DrawingArea(
    state: DashboardUiState,
    selected: CarPart?,
    onSelect: (CarPart) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 라벨은 앵커가 있는 쪽 줄에 놓는다. 줄을 고정하면 문이 아래쪽에서 열렸을 때
    // 위쪽 라벨에서 지시선이 도면을 통째로 가로질러 X자를 만든다
    val all = topCallouts(state) + bottomCallouts(state)
    val top = all.filter { it.anchor.second < 0.5f }
    val bottom = all.filter { it.anchor.second >= 0.5f }
    val density = LocalDensity.current
    val hairPx = with(density) { Stroke.hair.toPx() }
    val thinPx = with(density) { Stroke.thin.toPx() }
    val boldPx = with(density) { Stroke.bold.toPx() }
    // 지시선 색은 Canvas 안에서 못 읽는다(@Composable 게터) — 미리 풀어 둔다
    val leaderAlert = T.Danger
    val leaderPlain = T.Hairline

    BoxWithConstraints(modifier = modifier.padding(Space.md)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val plan = carPlanBounds(widthPx, heightPx)
        val rowHeight = calloutRowHeight()
        val rowHeightPx = with(density) { rowHeight.toPx() }

        CarPlan(
            tones = state.planTones(),
            modifier = Modifier.fillMaxSize(),
            strokeHairPx = hairPx,
            strokeThinPx = thinPx,
            strokeBoldPx = boldPx,
            onPartTap = onSelect,
        )

        // 지시선 — 라벨 칸의 중심에서 부위의 앵커까지 한 줄로 뻗는다
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            top.forEachIndexed { index, callout ->
                drawLeader(
                    from = androidx.compose.ui.geometry.Offset(
                        columnCenterX(index, top.size, size.width),
                        rowHeightPx,
                    ),
                    to = carAnchorOffset(callout.anchor, size.width, size.height),
                    color = if (callout.alert) leaderAlert else leaderPlain,
                    widthPx = hairPx,
                    dotPx = hairPx * 3,
                )
            }
            bottom.forEachIndexed { index, callout ->
                drawLeader(
                    from = androidx.compose.ui.geometry.Offset(
                        columnCenterX(index, bottom.size, size.width),
                        size.height - rowHeightPx,
                    ),
                    to = carAnchorOffset(callout.anchor, size.width, size.height),
                    color = if (callout.alert) leaderAlert else leaderPlain,
                    widthPx = hairPx,
                    dotPx = hairPx * 3,
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            CalloutRow(top, rowHeight, selected, onSelect)
            Spacer(Modifier.weight(1f))
            CalloutRow(bottom, rowHeight, selected, onSelect)
        }
    }
}

/**
 * 라벨 한 줄의 높이. 지시선 시작점을 계산할 때도 같은 값을 쓴다.
 *
 * 고정 46dp로 두면 시스템 글자 크기 1.3배에서 한글 받침이 잘린다 —
 * 줄 안에 라벨(11sp)과 값(20sp)이 함께 들어가야 하니 글자 배율만큼 같이 커져야 한다.
 */
@Composable
private fun calloutRowHeight(): androidx.compose.ui.unit.Dp =
    48.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

/** i번째 칸의 중심 x. 균등 분할이라 폭을 몰라도 나온다 */
private fun columnCenterX(index: Int, count: Int, width: Float): Float =
    width * (index * 2 + 1) / (count * 2f)

/**
 * 지시선 하나.
 *
 * 도면의 지시선은 화살표가 아니라 **점**으로 끝난다 — 면을 가리킬 때의 관례다.
 * 꺾이지 않은 직선 하나로 그어야 어느 부위를 가리키는지 헷갈리지 않는다.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLeader(
    from: androidx.compose.ui.geometry.Offset,
    to: androidx.compose.ui.geometry.Offset,
    color: Color,
    widthPx: Float,
    dotPx: Float,
) {
    drawLine(color, from, to, strokeWidth = widthPx, cap = StrokeCap.Square)
    drawCircle(color, dotPx, to)
}

/** 라벨 줄. 칸마다 weight 1f — 지시선 계산과 같은 균등 분할이다 */
@Composable
private fun CalloutRow(
    callouts: List<Callout>,
    rowHeight: androidx.compose.ui.unit.Dp,
    selected: CarPart?,
    onSelect: (CarPart) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        callouts.forEach { callout ->
            CalloutLabel(
                callout = callout,
                active = selected == callout.part,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(callout.part) },
            )
        }
    }
}

/**
 * 지시선 끝의 라벨 한 칸.
 *
 * 원번호 · 부품명 · 값 순으로 한 줄이다. 값은 고정폭이라 자릿수가 바뀌어도 안 흔들린다.
 * 이상한 값은 적색 면에 얹는다 — 이 글씨는 작아서 색만 바꾸면 곁눈에 안 걸린다.
 */
@Composable
private fun CalloutLabel(
    callout: Callout,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
        ) {
            CalloutNumber(
                number = callout.number,
                highlighted = active || callout.alert,
                accent = if (callout.alert) T.Danger else T.Ink,
            )
            Text(
                text = callout.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (callout.alert) T.Danger else T.InkFaint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        if (callout.alert) {
            Text(
                text = callout.value,
                style = TileValueStyle,
                color = T.OnDanger,
                maxLines = 1,
                modifier = Modifier
                    .background(T.Danger)
                    .padding(horizontal = Space.xs + 2.dp),
            )
        } else {
            Text(
                text = callout.value,
                style = TileValueStyle,
                color = callout.accent,
                maxLines = 1,
            )
        }
    }
}

/**
 * 치수 기입란 — 도면 우측.
 *
 * 도면에서 제일 큰 글자는 제목이 아니라 **기입된 치수**다. 실내 온도가 그 치수다.
 * 목표와 공조 상태는 그 아래 주기(註記)로 작게 붙는다.
 */
@Composable
private fun DimensionPanel(
    state: DashboardUiState,
    compact: Boolean,
    /** 도면 아래에 띠로 눕는가. 세로 화면에서 그렇다 */
    stacked: Boolean = false,
    onSelect: (CarPart) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .clickable { onSelect(CarPart.CABIN) }
            .padding(Space.lg),
        // 좁으면 위에서 시작하고, 넓으면 아래(표제란 쪽)로 붙인다.
        // 가운데 정렬이면 값이 아직 없을 때 화면에서 가장 큰 빈 공간이 우상단에 생긴다
        verticalArrangement = Arrangement.Top,
    ) {
        // 기입란 머리 — 도면번호·축척·시트. 표제란에 밀어 넣었더니 폰에서 두 줄로 접혔고,
        // 이 칸의 위쪽이 통째로 비어 화면에서 가장 큰 공백이 되어 있었다.
        // 도면의 이 정보는 원래 작도 영역 옆에 적힌다
        // 세로에서는 도면 식별을 표제란이 이미 지고 있고, 띠 높이가 짧아 넣을 자리가 없다
        if (!compact && !stacked) {
            SheetStamp(
                fields = listOf(
                    "도번" to "ST-01",
                    "축척" to "NTS",
                    "개정" to "REV ${com.wemade.teslamacro.BuildConfig.VERSION_NAME}",
                ),
            )
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalloutNumber(number = 1)
            Spacer(Modifier.width(Space.sm))
            Text(
                text = "실내",
                style = MaterialTheme.typography.labelSmall,
                color = T.InkFaint,
            )
        }
        Spacer(Modifier.height(Space.xs))
        // 값과 단위를 갈라 쓴다 — 도면의 치수 기입 방식이고, 단위가 잘려 사라지는 것도 막는다.
        // 아직 못 읽었으면 큰 대시를 띄우지 않는다 — 96sp 대시 두 개는 굵은 막대로 보여
        // 무슨 값인지도 모르는데 화면에서 가장 큰 것이 되어 버린다
        if (state.hasReading) {
            // 칸 폭을 알아야 크기를 뽑을 수 있다. 상한을 찍는 대신 재서 맞춘다
            BoxWithConstraints {
                val fontSize = inscribedSize(state.insideTemp, maxWidth)
                Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.insideTemp,
                    style = HeroValueStyle.copy(fontSize = fontSize),
                    color = state.insideColor(),
                    maxLines = 1,
                    // 계산이 어긋나도 값이 단위를 칸 밖으로 밀지 못하게 폭을 나눈다.
                    // fill=false라 필요한 만큼만 쓰고, 남으면 단위가 그 자리를 갖는다
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    text = "°C",
                    style = MaterialTheme.typography.headlineMedium,
                    color = T.InkMuted,
                    modifier = Modifier.padding(bottom = Space.md),
                )
                }
            }
        } else {
            Text(
                text = "치수 미기입",
                style = MaterialTheme.typography.titleMedium,
                color = T.InkFaint,
                modifier = Modifier.padding(vertical = Space.md),
            )
        }
        Spacer(Modifier.height(Space.sm))
        // 치수 보조선 — 기입값 아래를 받치는 선. 값이 판에 얹혀 있음을 보인다
        Box(
            Modifier
                .fillMaxWidth()
                .height(Stroke.thin)
                .background(T.Ink)
        )
        Spacer(Modifier.height(Space.md))

        // 치수표 — 도면은 주요 치수를 기입하고 나머지를 표로 세운다.
        // 여기가 비어 있으면 도면이 미완성으로 보인다
        DimensionRow("외기", "${state.outsideTemp} °C", number = 2)
        DimensionRow("목표", if (state.hasClimateReading) "${state.targetTemp} °C" else "--")
        DimensionRow(
            label = "공조",
            value = when {
                !state.hasClimateReading -> "확인 중"
                state.isClimateOn -> "켜짐"
                else -> "꺼짐"
            },
        )
        // 공기압은 빠졌을 때만 적는다. 정상 타이어는 도면에서 파선으로 조용히 있고,
        // 여기 "정상"이라고 써 두면 상시 켜진 화면에서 읽히지 않는 배경이 된다
        state.tireWarning?.let {
            DimensionRow(label = "공기압", value = it, tone = T.Danger)
        }
        // 차량 업데이트도 사람이 결정할 일이 남았을 때만 (예약·다운로드·설치 가능)
        state.vehicleSoftware?.let {
            DimensionRow(label = "차량 SW", value = it)
        }
        // 달리는 중에만. 세워둔 차에 "0 km/h"를 띄우면 읽히지 않는 배경이 된다
        state.speedKph?.let {
            DimensionRow(label = "속도", value = "$it km/h")
        }
        // 다가오는 단속·보호구역. 오버레이는 다른 앱 위에만 뜨니 여기에도 같은 말을 적는다.
        // 적색은 이 한 줄에만 든다 — 속도까지 같이 물들이면 기입란에 붉은 줄이 둘이 되고,
        // 공기압 경보까지 겹치면 "지금 봐야 할 것"이 셋이 되어 아무것도 안 보인다
        if (state.safetyLabel != null && state.safetyValue != null) {
            DimensionRow(
                label = state.safetyLabel,
                value = state.safetyValue,
                tone = if (state.safetyAlarming) T.Danger else null,
            )
        }
        // 주차가 얼마나 됐고 그동안 얼마나 빠졌는지. 방전이 이 앱의 가장 큰 걱정이다
        state.parkSummary?.let {
            DimensionRow(label = "주차", value = it)
        }

        // 부품표. 좁은 화면에서는 선도가 없으니 이것이 유일한 값 목록이고,
        // 세로에서는 선도 아래 자리가 남아 함께 싣는다 — 도면은 그림과 부품표를
        // 같은 시트에 싣는 것이 정상이고, 값이 두 번 읽히는 게 흘깃 보기에 유리하다
        if (compact || stacked) {
            Spacer(Modifier.height(Space.md))
            Hairline()
            val parts = topCallouts(state) + bottomCallouts(state)
            // 세로는 폭이 넉넉하니 두 열로 접는다 — 한 열로 세우면 다섯 줄이
            // 기입란을 넘겨 06·07이 잘렸다. 제어 화면은 스크롤 없이 한 화면이 전제다
            val perRow = if (stacked) 2 else 1
            parts.chunked(perRow).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { index, callout ->
                        if (index > 0) {
                            // 열 사이 괘선 — 없으면 왼쪽 칸의 값과 오른쪽 칸의 라벨이 붙어
                            // 어디까지가 한 항목인지 읽히지 않는다
                            Box(
                                Modifier
                                    .width(Stroke.thin)
                                    .height(24.dp)
                                    .align(Alignment.CenterVertically)
                                    .background(T.Hairline)
                            )
                            Spacer(Modifier.width(Space.md))
                        }
                        CompactRow(
                            callout = callout,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 마지막 줄이 한 칸이면 남은 칸을 비워 열을 맞춘다
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 기입란 머리의 도면 식별 — 도번 · 축척 · 개정.
 *
 * 도면 옆에 이게 없으면 "무슨 도면의 몇 번째 개정인지"를 아무도 모른다.
 * 값이 도착하기 전 이 칸이 비어 있던 것도 이걸 놓았기 때문이다.
 */
@Composable
private fun SheetStamp(fields: List<Pair<String, String>>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(Stroke.thin).background(T.Hairline))
        Spacer(Modifier.height(Space.sm))
        fields.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = T.InkFaint,
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = T.InkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 치수표 한 행 — 이름 왼쪽, 값 오른쪽, 사이는 점선.
 *
 * 점선으로 이어야 눈이 이름에서 값으로 건너간다. 여백만 두면 두 열이 따로 읽힌다.
 */
@Composable
private fun DimensionRow(
    label: String,
    value: String,
    number: Int? = null,
    /** 기본은 무채색. 적색은 지금 봐야 할 값에만 든다 */
    tone: Color? = null,
) {
    val dotColor = T.Hairline
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            CalloutNumber(number = number)
            Spacer(Modifier.width(Space.sm))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.sm)
                .height(Stroke.hair)
                .drawBehind {
                    drawLine(
                        color = dotColor,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        cap = StrokeCap.Square,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(2.dp.toPx(), 3.dp.toPx())
                        ),
                    )
                }
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = tone ?: T.InkMuted,
            maxLines = 1,
        )
    }
}

/** 좁은 화면의 값 한 줄. 도면의 부품 명세표 한 행이다 */
@Composable
private fun CompactRow(
    callout: Callout,
    onSelect: (CarPart) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onSelect(callout.part) }
            .padding(end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalloutNumber(
            number = callout.number,
            highlighted = callout.alert,
            accent = if (callout.alert) T.Danger else T.Ink,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = callout.label,
            style = MaterialTheme.typography.labelSmall,
            color = T.InkFaint,
            modifier = Modifier.weight(1f),
        )
        if (callout.alert) {
            // 색만 바꾸면 곁눈에 안 걸린다 — 면으로 깔아야 안 읽고도 보인다
            Text(
                text = callout.value,
                style = MaterialTheme.typography.headlineMedium,
                color = T.OnDanger,
                maxLines = 1,
                modifier = Modifier
                    .background(T.Danger)
                    .padding(horizontal = Space.xs + 2.dp),
            )
        } else {
            Text(
                text = callout.value,
                style = MaterialTheme.typography.headlineMedium,
                color = callout.accent,
                maxLines = 1,
            )
        }
    }
}

/** 기입 치수 크기 — 폭이 좁으면 줄인다. 넘치면 읽히지도 않는다 */
/** 값과 단위 사이 여백. 단위 자체의 폭은 추정하지 않고 잰다 */
private val UNIT_GAP = 8.dp

/** 기입 치수에 붙는 단위 */
private const val UNIT_LABEL = "°C" 

/**
 * 잰 폭이 칸을 넘으면 넘친 비율만큼 크기를 줄인다.
 *
 * Compose 없이 계산되는 순수 함수로 떼어 뒀다 — 이 식이 "배율을 올리면 커진다"를
 * 지키는지는 스크린샷으로 못 잡는다(값 아래 치수선이 칸 전폭을 채워서
 * 오른쪽 끝을 재면 항상 같은 값이 나온다). 단위 테스트가 직접 봐야 한다.
 *
 * @param measuredPx 기본 크기로 이 글자를 실제로 재 본 폭. 글자 배율이 이미 반영돼 있다
 * @param roomPx 값이 쓸 수 있는 폭
 */
internal fun fitInscribedSp(baseSp: Float, measuredPx: Float, roomPx: Float): Float =
    if (measuredPx <= roomPx || measuredPx <= 0f) baseSp
    else baseSp * (roomPx / measuredPx)

/**
 * 기입 치수 크기 — **실제로 재서** 칸에 맞춘다.
 *
 * 두 번 틀렸다. 처음엔 배율 상한을 1.3으로 박아 뒀는데 그건 찍은 숫자였고,
 * 네 글자가 1.3배에서 칸을 18dp 넘겼다. 다음엔 고정폭 advance를 0.6em으로 가정해
 * 계산했는데 이 기기의 실제 고정폭은 그보다 훨씬 좁아, 넘치지는 않지만
 * 배율을 올릴 때 값이 오히려 **작아졌다** — 접근성 의도가 거꾸로 된 것이다.
 *
 * 그래서 가정을 다 버리고 `TextMeasurer`로 이 글자를 이 서체로 실제로 재 본다.
 * 재 본 폭이 칸을 넘으면 넘친 비율만큼 줄인다 — 결과는 **칸이 허락하는 최대 크기**다.
 * 글자 수가 늘면(-10.5) 알아서 작아지고, 칸이 넓어지면 알아서 커진다.
 * 배율을 올려도 작아지지 않는다는 것이 이 식의 핵심이다.
 */
@Composable
private fun inscribedSize(
    text: String,
    available: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.TextUnit {
    val base = when (LocalPane.current) {
        Pane.Compact -> 56.sp
        Pane.Medium -> 68.sp
        Pane.Expanded -> 84.sp
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val unitStyle = MaterialTheme.typography.headlineMedium
    // 단위 폭도 추정하지 않고 잰다. 고정 dp로 예약했더니 글자 배율을 곱해 줘야 했고,
    // 그 곱이 값의 성장분을 그대로 먹어 배율을 올릴 때 값이 오히려 줄었다(56px → 51px)
    val unitPx = measurer.measure(text = UNIT_LABEL, style = unitStyle).size.width.toFloat()
    val roomPx = with(density) {
        (available.toPx() - unitPx - UNIT_GAP.toPx()).coerceAtLeast(24.dp.toPx())
    }
    // 측정기는 LocalDensity를 통해 재므로 글자 배율이 **이미 반영돼 있다.**
    // 여기에 배율을 또 곱했더니 같은 것을 두 번 보정해 값이 작아졌다
    val measuredPx = measurer.measure(
        text = text,
        style = HeroValueStyle.copy(fontSize = base),
    ).size.width.toFloat()
    return fitInscribedSp(base.value, measuredPx, roomPx).sp
}

/**
 * 상세도 — 부위를 눌렀을 때 기입란 자리에 펼쳐진다.
 *
 * 도면에서 한 부품을 자세히 보려면 옆에 상세도를 따로 그린다. 시트로 화면을 덮으면
 * 도면이 안 보이고, 그러면 "어디를 조작하는 중인지"가 사라진다.
 */
@Composable
private fun DetailView(
    part: CarPart,
    state: DashboardUiState,
    onClose: () -> Unit,
    onCommand: (VehicleCommand) -> Unit,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit,
    onStealthCharging: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(T.Carbon)
            .verticalScroll(rememberScrollState())
            .padding(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalloutNumber(number = part.calloutNumber, highlighted = true)
            Spacer(Modifier.width(Space.sm))
            Text(
                text = part.title,
                style = MaterialTheme.typography.titleMedium,
                color = T.Ink,
                modifier = Modifier.weight(1f),
            )
            // 닫기 — 도면의 취소 기호. 대각 두 선뿐이다
            androidx.compose.material3.Icon(
                imageVector = DraftMark.Close,
                contentDescription = "상세도 닫기",
                tint = T.InkMuted,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .width(48.dp)
                    .clickable(onClick = onClose)
                    .padding(Space.sm + Space.xs),
            )
        }
        Spacer(Modifier.height(Space.sm))
        Hairline()
        Spacer(Modifier.height(Space.md))

        when (part) {
            CarPart.CABIN -> ClimateDetail(state, onCommand)
            CarPart.BODY -> LockDetail(onCommand, onClose)
            CarPart.PACK -> ChargeDetail(state, onCommand, onStealthCharging)
            CarPart.FRUNK, CarPart.TRUNK -> OpeningsDetail(state, onCommand)
            CarPart.SEAT_LEFT -> SeatDetail(state, SeatPosition.FRONT_LEFT, onSeatClimate)
            CarPart.SEAT_RIGHT -> SeatDetail(state, SeatPosition.FRONT_RIGHT, onSeatClimate)
        }
    }
}

@Composable
private fun ClimateDetail(state: DashboardUiState, onCommand: (VehicleCommand) -> Unit) {
    FieldLabel("목표 온도")
    NumberStepper(
        value = state.targetTempValue ?: 22.0,
        min = 15.0,
        max = 28.0,
        step = 0.5,
        unit = "℃",
        onChange = { onCommand(VehicleCommand.SetTemperature(it)) },
    )
    Spacer(Modifier.height(Space.lg))
    if (state.isClimateOn) {
        TButton("공조 끄기", tone = ButtonTone.Secondary) { onCommand(VehicleCommand.ClimateOff) }
    } else {
        TButton("공조 켜기") { onCommand(VehicleCommand.ClimateOn) }
    }
}

@Composable
private fun LockDetail(onCommand: (VehicleCommand) -> Unit, onClose: () -> Unit) {
    // 잠금 해제는 실수로 눌리면 안 되는 동작이라 부위 탭으로 바로 걸지 않았다
    TButton("잠그기") {
        onCommand(VehicleCommand.Lock)
        onClose()
    }
    Spacer(Modifier.height(Space.sm))
    TButton("잠금 해제", tone = ButtonTone.Secondary) {
        onCommand(VehicleCommand.Unlock)
        onClose()
    }
}

@Composable
private fun ChargeDetail(
    state: DashboardUiState,
    onCommand: (VehicleCommand) -> Unit,
    onStealthCharging: (Boolean) -> Unit,
) {
    FieldLabel("충전 한도")
    NumberStepper(
        value = (state.chargeLimitPercent ?: 80).toDouble(),
        min = 50.0,
        max = 100.0,
        step = 5.0,
        unit = "%",
        onChange = { onCommand(VehicleCommand.SetChargeLimit(it.toInt())) },
    )
    Spacer(Modifier.height(Space.md))
    FieldLabel("충전 전류")
    NumberStepper(
        value = (state.chargingAmps ?: 32).toDouble(),
        min = 5.0,
        max = 48.0,
        step = 1.0,
        unit = "A",
        onChange = { onCommand(VehicleCommand.SetChargingAmps(it.toInt())) },
    )
    Spacer(Modifier.height(Space.md))
    Hairline()
    Spacer(Modifier.height(Space.md))
    ToggleRow(
        title = "스텔스 충전",
        subtitle = "전류를 조금씩 흔들어 눈에 덜 띄게 해요",
        checked = state.stealthCharging,
        onCheckedChange = onStealthCharging,
    )
}

@Composable
private fun OpeningsDetail(state: DashboardUiState, onCommand: (VehicleCommand) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OpeningButton("창문 환기", state.isReady) { onCommand(VehicleCommand.VentWindows) }
        OpeningButton("창문 닫기", state.isReady) { onCommand(VehicleCommand.CloseWindows) }
    }
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OpeningButton("트렁크 열기", state.isReady) { onCommand(VehicleCommand.OpenTrunk) }
        // 열기만 있고 닫기가 없었다. 프렁크는 전동이 아니라 손으로 닫아야 해서 없다
        OpeningButton("트렁크 닫기", state.isReady) { onCommand(VehicleCommand.CloseTrunk) }
    }
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OpeningButton("프렁크 열기", state.isReady) { onCommand(VehicleCommand.OpenFrunk) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SeatDetail(
    state: DashboardUiState,
    seat: SeatPosition,
    onSeatClimate: (SeatPosition, SeatMode, Level) -> Unit,
) {
    val climate = state.seatClimate[seat] ?: SeatClimate()
    var mode by remember(seat) { mutableStateOf(climate.mode) }

    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        SeatMode.entries.forEach { candidate ->
            TButton(
                text = candidate.label,
                tone = if (candidate == mode) ButtonTone.Primary else ButtonTone.Secondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    mode = candidate
                    // 이미 켜져 있으면 모드 전환을 그 자리에서 보낸다.
                    // 안 보내면 통풍→열선을 눌러도 아무 일이 없어 고장으로 보인다
                    if (climate.level != Level.OFF) {
                        onSeatClimate(seat, candidate, climate.level)
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(Space.md))
    LevelSelector(
        label = "단계",
        selected = climate.level,
        accent = if (mode == SeatMode.COOL) T.Cool else T.Heat,
        onSelect = { onSeatClimate(seat, mode, it) },
        enabled = state.isReady,
    )
}

/** 상세도 안의 항목 이름. 도면의 필드 라벨이다 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = T.InkFaint,
    )
    Spacer(Modifier.height(Space.sm))
}

@Composable
private fun RowScope.OpeningButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    TButton(
        text = text,
        tone = ButtonTone.Secondary,
        modifier = Modifier.weight(1f),
        enabled = enabled,
        onClick = onClick,
    )
}

// ---- 지시선에 매달 것들 ----

/** 지시선 하나가 가리키는 것. 번호·이름·값·앵커를 한 묶음으로 든다 */
private data class Callout(
    val number: Int,
    val label: String,
    val value: String,
    val part: CarPart,
    val anchor: Pair<Float, Float>,
    val accent: Color,
    val alert: Boolean = false,
)

/**
 * 화면 위쪽 지시선 — 동승석 · 잠금 · 문·적재함.
 *
 * 평면도는 위에서 내려다본 그림이라 **차량 우측이 화면 위**다. 그래서 동승석이 여기다.
 * 번호는 01 실내 · 02 외기(치수표)를 잇는 연속 번호다 — 도면의 번호에 구멍이 있으면 도면이 아니다.
 */
@Composable
private fun topCallouts(state: DashboardUiState): List<Callout> {
    val right = state.seatClimate[SeatPosition.FRONT_RIGHT] ?: SeatClimate()
    val openings = state.openings
    return listOf(
        Callout(
            number = 3,
            label = "동승석",
            value = if (right.level != Level.OFF) {
                "${right.mode.label} ${right.level.label}"
            } else "끔",
            part = CarPart.SEAT_RIGHT,
            anchor = CarAnchors.seatRight,
            accent = seatAccent(right),
        ),
        Callout(
            number = 4,
            label = "잠금",
            value = when {
                !state.hasBodyReading -> "--"
                state.isLocked -> "잠김"
                else -> "열림"
            },
            part = CarPart.BODY,
            anchor = CarAnchors.lock,
            accent = T.Ink,
            // 문이 열려 있으면 잠금 해제는 당연한 결과라 새 소식이 아니다 — 경보는 한 곳만
            alert = state.hasBodyReading && !state.isLocked && openings.isEmpty(),
        ),
        Callout(
            number = 5,
            label = "문 · 적재함",
            value = when {
                !state.hasBodyReading -> "--"
                openings.isEmpty() -> "모두 닫힘"
                else -> openings.joinToString(" · ") { it.label }
            },
            // 열린 것이 있으면 그것을 가리킨다. 고정 앵커면 문이 열렸는데 트렁크를 가리킨다
            part = openings.firstOrNull()?.part ?: CarPart.TRUNK,
            anchor = openings.firstOrNull()?.anchor ?: CarAnchors.openings,
            accent = T.Ink,
            alert = openings.isNotEmpty(),
        ),
    )
}

/** 화면 아래쪽 지시선 — 운전석(차량 좌측) · 배터리 */
@Composable
private fun bottomCallouts(state: DashboardUiState): List<Callout> {
    val left = state.seatClimate[SeatPosition.FRONT_LEFT] ?: SeatClimate()
    return listOf(
        Callout(
            number = 6,
            label = "운전석",
            value = if (left.level != Level.OFF) "${left.mode.label} ${left.level.label}" else "끔",
            part = CarPart.SEAT_LEFT,
            anchor = CarAnchors.seatLeft,
            accent = seatAccent(left),
        ),
        Callout(
            number = 7,
            label = if (state.rangeKm != null) "배터리 · 주행" else "배터리",
            value = if (state.rangeKm != null) {
                "${state.batteryLabel} ${state.rangeKm}km"
            } else state.batteryLabel,
            part = CarPart.PACK,
            anchor = CarAnchors.pack,
            accent = T.Ink,
        ),
    )
}

/**
 * 열린 것 하나가 도면의 어느 부위인가.
 *
 * 문자열을 뒤지지 않고 enum으로 잇는다. 라벨을 "운전석 도어"에서 뭐로 바꾸든
 * 지시선이 계속 맞는 곳을 가리킨다.
 */
private val Door.part: CarPart
    get() = when (this) {
        Door.TRUNK -> CarPart.TRUNK
        Door.FRUNK -> CarPart.FRUNK
        else -> CarPart.BODY
    }

/** 도면의 어느 짝인가. 트렁크·프렁크는 문이 아니라 적재함이라 없다 */
private val Door.carDoor: CarDoor?
    get() = when (this) {
        Door.DRIVER_FRONT -> CarDoor.DriverFront
        Door.DRIVER_REAR -> CarDoor.DriverRear
        Door.PASSENGER_FRONT -> CarDoor.PassengerFront
        Door.PASSENGER_REAR -> CarDoor.PassengerRear
        else -> null
    }

private val Door.anchor: Pair<Float, Float>
    get() = carDoor?.anchor ?: when (this) {
        Door.FRUNK -> 0.10f to 0.50f
        else -> CarAnchors.openings
    }

/** 시트가 실제로 돌고 있을 때만 색을 준다 */
@Composable
private fun seatAccent(climate: SeatClimate): Color = when {
    climate.level == Level.OFF -> T.Ink
    climate.mode == SeatMode.COOL -> T.Cool
    else -> T.Heat
}

/** 상세도 제목과 번호 — 부위 자신이 안다 */
private val CarPart.title: String
    get() = when (this) {
        CarPart.CABIN -> "공조"
        CarPart.BODY -> "잠금"
        CarPart.PACK -> "충전"
        CarPart.FRUNK -> "문 · 적재함"
        CarPart.TRUNK -> "문 · 적재함"
        CarPart.SEAT_LEFT -> "운전석"
        CarPart.SEAT_RIGHT -> "동승석"
    }

private val CarPart.calloutNumber: Int
    get() = when (this) {
        CarPart.CABIN -> 1
        CarPart.SEAT_RIGHT -> 3
        CarPart.BODY -> 4
        CarPart.FRUNK, CarPart.TRUNK -> 5
        CarPart.SEAT_LEFT -> 6
        CarPart.PACK -> 7
    }

/**
 * 선도가 쓸 부위별 상태.
 *
 * 화면 상태를 그리기 상태로 옮기는 유일한 곳이다 — 여기 없으면 선도가 색을 스스로 정하게 되고,
 * 그러면 "정상은 무채색" 규칙이 여러 곳에 흩어진다.
 */
@Composable
private fun DashboardUiState.planTones(): CarPlanTones {
    val left = seatClimate[SeatPosition.FRONT_LEFT] ?: SeatClimate()
    val right = seatClimate[SeatPosition.FRONT_RIGHT] ?: SeatClimate()
    val states = buildMap {
        // 캐빈 — 목표에 아직 못 갔을 때만 색이 든다. 도달해 유지만 하면 무채색으로 돌아온다
        val inside = insideTemp.toDoubleOrNull()
        val target = targetTempValue
        val gap = if (inside != null && target != null) inside - target else 0.0
        if (isClimateOn && hasClimateReading) {
            when {
                gap > REACHED_MARGIN_C -> put(CarPart.CABIN, PartState.Cooling)
                gap < -REACHED_MARGIN_C -> put(CarPart.CABIN, PartState.Heating)
                else -> Unit
            }
        }
        if (left.level != Level.OFF) {
            put(
                CarPart.SEAT_LEFT,
                if (left.mode == SeatMode.COOL) PartState.Cooling else PartState.Heating,
            )
        }
        if (right.level != Level.OFF) {
            put(
                CarPart.SEAT_RIGHT,
                if (right.mode == SeatMode.COOL) PartState.Cooling else PartState.Heating,
            )
        }
        // 열린 것은 그 부위를 채운다. enum으로 곧바로 잇는다
        if (openings.contains(Door.TRUNK)) put(CarPart.TRUNK, PartState.Alert)
        if (openings.contains(Door.FRUNK)) put(CarPart.FRUNK, PartState.Alert)
        // 잠금 해제로 차체 전체를 적색으로 칠하지 않는다.
        // 차 옆에 서 있으면 늘 참인 평상 상태인데 화면에서 가장 큰 잉크가 되어,
        // 정작 봐야 할 경보(열린 문·명령 실패)를 이길 수 없게 만들었다.
        // 잠금은 04 배지 하나로 말한다 — 유채색은 "지금 봐야 할 것" 하나에만 쓴다
        if (isCharging == true) put(CarPart.PACK, PartState.Heating)
    }
    return CarPlanTones(
        states = states,
        openDoors = openings.mapNotNull { it.carDoor }.toSet(),
        lowTires = lowTires,
        ink = T.Ink,
        inkMuted = T.InkMuted,
        inkFaint = T.InkFaint,
        cool = T.Cool,
        heat = T.Heat,
        alert = T.Danger,
        paper = T.Void,
    )
}

/** 기입 치수의 색. 목표에 못 갔을 때만 색이 든다 */
@Composable
private fun DashboardUiState.insideColor(): Color {
    // 봐야 할 경보가 이미 있으면 기입값은 잉크로 물러선다 —
    // 유채색이 두 군데서 동시에 뜨면 어느 쪽을 봐야 하는지가 사라진다
    val unlocked = hasBodyReading && !isLocked
    if (openings.isNotEmpty() || errorMessage != null || unlocked) return T.Ink
    if (!isClimateOn || !hasClimateReading) return T.Ink
    val inside = insideTemp.toDoubleOrNull() ?: return T.Ink
    val target = targetTempValue ?: return T.Ink
    val gap = inside - target
    return when {
        gap > REACHED_MARGIN_C -> T.Cool
        gap < -REACHED_MARGIN_C -> T.Heat
        else -> T.Ink
    }
}

/** 화면이 그리는 데 필요한 값만 담는다. 도메인 모델을 그대로 노출하지 않는다 */
data class DashboardUiState(
    val link: LinkState,
    val vehicleName: String,
    val insideTemp: String,
    val outsideTemp: String,
    val targetTemp: String,
    val targetTempValue: Double?,
    val isClimateOn: Boolean,
    val isLocked: Boolean,
    /** 좌석별 통풍/열선 설정 (클라 저장값). 화면은 이걸 기준으로 그린다 */
    val seatClimate: Map<SeatPosition, SeatClimate> = emptyMap(),
    val isSimulated: Boolean,
    /** 상태를 한 번이라도 읽었는지. false면 값 자리에 --를 보여준다 */
    val hasReading: Boolean,
    /** VCSEC(잠금·탑승)를 실제로 읽었는가 — 전역 hasReading은 다른 카테고리 성공으로도 참이 된다 */
    val hasBodyReading: Boolean = false,
    /** CLIMATE를 실제로 읽었는가 — 읽기 전 "공조 꺼짐" 단정을 막는다 */
    val hasClimateReading: Boolean = false,
    val pendingCommand: VehicleCommand?,
    val errorMessage: String?,
    /** 마지막으로 차량 상태를 읽은 뒤 흐른 초. 못 읽었으면 null */
    val secondsSinceReading: Long? = null,
    val batteryPercent: Int? = null,
    /** 충전 상태. 못 읽었으면 null — 타일이 "확인 중"으로 보인다 */
    val isCharging: Boolean? = null,
    val chargeLimitPercent: Int? = null,
    val chargingAmps: Int? = null,
    val stealthCharging: Boolean = false,
    /** 공기압이 기준 아래인 바퀴. 있으면 도면의 그 자리가 적색이 된다 */
    val lowTires: Set<TirePosition> = emptySet(),
    /** 낮은 바퀴가 있을 때만 채운다 — 정상이면 화면에 한 글자도 안 늘린다 */
    val tireWarning: String? = null,
    /** 차량 소프트웨어 상태. 설치 예약·다운로드 중일 때만 채운다 */
    val vehicleSoftware: String? = null,
    /** 주차 경과와 그동안의 배터리 소모. 타고 있으면 null */
    val parkSummary: String? = null,
    /** 달리는 중일 때만 채운다. 차가 보고한 속도라 GPS HUD보다 갱신이 느리다 */
    val speedKph: Int? = null,
    /** 다가오는 단속·보호구역의 종류(라벨 자리). 안내할 게 없으면 null */
    val safetyLabel: String? = null,
    /** 그 제한속도와 남은 거리(값 자리) */
    val safetyValue: String? = null,
    /** 그 경보가 지금 지켜야 할 제한속도를 넘긴 상태인가 — 넘겼을 때만 적색 */
    val safetyAlarming: Boolean = false,
    val automationEnabled: Boolean = true,
    val runningMacroCount: Int = 0,
    /** 주행 가능 거리(km). 배터리 %만으론 실감이 안 나 함께 보여준다 */
    val rangeKm: Int? = null,
    /**
     * 지금 열려 있는 문·트렁크.
     *
     * 라벨 문자열이 아니라 [Door] 자체를 든다 — 선도가 "어느 짝인지"를 알아야
     * 그 문만 벌어지게 그릴 수 있고, 라벨 글자가 바뀌어도 조용히 깨지지 않는다.
     */
    val openings: List<Door> = emptyList(),
) {
    val isReady: Boolean get() = link is LinkState.Ready

    val lastUpdatedLabel: String
        get() = when (val seconds = secondsSinceReading) {
            null -> "아직 없음"
            in 0..4 -> "방금"
            in 5..59 -> "${seconds}초 전"
            else -> "${seconds / 60}분 전"
        }

    val batteryLabel: String get() = batteryPercent?.let { "$it%" } ?: "--"

    val automationLabel: String
        get() = when {
            !automationEnabled -> "꺼짐"
            runningMacroCount > 0 -> "실행 중 ${runningMacroCount}개"
            else -> "감시 중"
        }

    /** 연결 중이거나 명령이 오가는 중 */
    val isBusy: Boolean
        get() = pendingCommand != null ||
            link is LinkState.Scanning ||
            link is LinkState.Connecting

    /** 지금 차량으로 전송 중인 시트 명령의 좌석. 그 좌석 타일에만 표시를 단다 */
    val pendingSeat: SeatPosition?
        get() = when (val command = pendingCommand) {
            is VehicleCommand.SetSeatCooler -> command.seat
            is VehicleCommand.SetSeatHeater -> command.seat
            else -> null
        }

    val connectionLabel: String
        get() = when (link) {
            is LinkState.Idle -> "대기"
            is LinkState.Scanning -> "검색 중"
            is LinkState.Connecting -> "연결 중"
            is LinkState.Ready -> if (isSimulated) "시뮬레이터" else "연결됨"
            is LinkState.Failed -> "연결 실패"
        }

    val connectionRole: ColorRole
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) ColorRole.Warn else ColorRole.Ok
            is LinkState.Failed -> ColorRole.Danger
            else -> ColorRole.InkMuted
        }

    /** 배지 글자색 — 밝은 상태색(Warn/Ok)은 옅은 틴트 위에서 안 읽혀 진한 색으로 분리한다 */
    val connectionTextRole: ColorRole
        get() = when (link) {
            is LinkState.Ready -> if (isSimulated) ColorRole.WarnText else ColorRole.OkText
            is LinkState.Failed -> ColorRole.Danger
            else -> ColorRole.InkMuted
        }

    /** 27℃를 넘으면 더운 상태로 본다. 매크로 발동 임계값과 같은 기준 */
    val insideTempRole: ColorRole
        get() = insideTemp.toDoubleOrNull()?.let {
            when {
                it >= 27.0 -> ColorRole.Heat
                it <= 5.0 -> ColorRole.Cool
                else -> ColorRole.Ink
            }
        } ?: ColorRole.Ink
}

package com.wemade.teslamacro.feature.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import com.wemade.teslamacro.ui.component.hatch
import com.wemade.teslamacro.ui.component.reducedMotion
import com.wemade.teslamacro.ui.theme.Motion

/**
 * 도면이 가리킬 수 있는 차의 부위.
 *
 * 값 목록의 한 줄이 아니라 **차의 어디**가 조작 단위다.
 * 문이 열렸으면 그 문에 잉크가 들고, 그 문을 누르면 그 문의 조작이 열린다.
 */
enum class CarPart {
    /** 캐빈 — 공조 */
    CABIN,

    /** 프렁크 */
    FRUNK,

    /** 트렁크 · 리프트게이트 */
    TRUNK,
    SEAT_LEFT,
    SEAT_RIGHT,

    /** 차체 전체 — 잠금 */
    BODY,

    /** 배터리 팩 — 충전 */
    PACK,
}

/**
 * 부위 하나가 지금 어떤 상태인가.
 *
 * 도면은 색으로 말하기 전에 **무늬**로 말한다. 그래서 상태가 색 이름이 아니라
 * 그리기 방식(윤곽만 / 해칭 / 채움)으로 갈린다.
 */
enum class PartState {
    /** 정상 — 윤곽선만. 화면 대부분이 이 상태다 */
    Plain,

    /** 냉각 작동 중 — 청색 해칭이 흐른다 */
    Cooling,

    /** 가열 작동 중 — 적색 해칭이 흐른다 */
    Heating,

    /** 지금 사람이 봐야 할 것 — 적색으로 채운다 */
    Alert,
}

/** 부위별 상태와 색. 그리기에 필요한 것만 담는다 */
data class CarPlanTones(
    val states: Map<CarPart, PartState> = emptyMap(),
    /** 지금 열려 있는 문. 그 짝만 벌어져 그려진다 */
    val openDoors: Set<CarDoor> = emptySet(),
    /**
     * 공기압이 기준 아래인 바퀴. 그 자리만 적색 실선이 된다 —
     * 정상인 바퀴는 파선 그대로라 이상한 짝이 눈에 먼저 든다
     */
    val lowTires: Set<com.wemade.teslamacro.domain.model.TirePosition> = emptySet(),
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val cool: Color,
    val heat: Color,
    val alert: Color,
    /** 해칭 사이로 보이는 종이색. 채운 면 위 글자색으로도 쓴다 */
    val paper: Color,
)

/**
 * Model Y 평면 선도.
 *
 * 이 앱에서 가장 중요한 판단: **차의 상태를 목록이 아니라 차의 모양으로 말한다.**
 * "문 열림"이라는 글자를 읽고 어느 문인지 다시 생각하는 단계를 없앤다 —
 * 열린 문 자리에 잉크가 들면 읽기 전에 안다.
 *
 * 정상이면 전체가 단색 윤곽선이다. 유채색은 작동 중이거나 봐야 할 부위에만 든다.
 *
 * @param onPartTap 부위를 눌렀을 때. 시트가 화면을 덮는 대신 그 부위의 조작이 열린다
 */
@Composable
fun CarPlan(
    tones: CarPlanTones,
    modifier: Modifier = Modifier,
    strokeThinPx: Float,
    strokeBoldPx: Float,
    strokeHairPx: Float,
    onPartTap: (CarPart) -> Unit = {},
) {
    // 앱에서 유일하게 움직이는 것 — 작동 중인 부위의 해칭이 천천히 흐른다.
    // 공조가 실제로 도는 동안만 돌고, 정상이면 화면은 완전히 정지한다
    // 시스템 "애니메이션 제거"를 존중한다. 상시 켜진 화면에서 이걸 무시하면
    // 그 설정을 켠 사람에게 영원히 도는 무늬를 보여주게 된다
    val flowing = !reducedMotion() &&
        tones.states.values.any { it == PartState.Cooling || it == PartState.Heating }
    val phase by rememberInfiniteTransition(label = "hatch").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(Motion.breathe(2600), RepeatMode.Restart),
        label = "hatchPhase",
    )

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val plan = planRect(size.width.toFloat(), size.height.toFloat())
                hitPart(offset, plan)?.let(onPartTap)
            }
        }
    ) {
        val plan = planRect(size.width, size.height)
        drawCarPlan(
            plan = plan,
            tones = tones,
            phase = if (flowing) phase else 0f,
            hairPx = strokeHairPx,
            thinPx = strokeThinPx,
            boldPx = strokeBoldPx,
        )
    }
}

// ---- 기하 ----
//
// 차를 0~1 정규 좌표로 정의한다. x는 노즈(0)에서 테일(1), y는 좌측(0)에서 우측(1).
// Model Y 실제 비례 4,751 × 1,921mm ≈ 2.47:1 을 그대로 쓴다 —
// 도면이 실물 비례를 안 지키면 그건 도면이 아니라 삽화다.
private const val CAR_ASPECT = 2.47f

/** 지시선이 뻗어 나갈 여백. 차 폭의 몇 배를 위아래로 비워 둘지 */
private const val MARGIN_RATIO = 0.16f

/** 그리기 영역 안에서 차가 실제로 앉는 사각형 */
private fun planRect(width: Float, height: Float): Rect {
    val usableHeight = height * (1f - MARGIN_RATIO * 2)
    // 폭에 맞추면 넘칠 수 있고, 높이에 맞추면 좁을 수 있다 — 둘 중 작은 쪽에 맞춘다
    val byWidth = Size(width, width / CAR_ASPECT)
    val size = if (byWidth.height <= usableHeight) byWidth
    else Size(usableHeight * CAR_ASPECT, usableHeight)
    val left = (width - size.width) / 2f
    val top = (height - size.height) / 2f
    return Rect(left, top, left + size.width, top + size.height)
}

/** 정규 좌표를 화면 좌표로 */
private fun Rect.at(nx: Float, ny: Float) = Offset(left + width * nx, top + height * ny)

/** 정규 사각형을 화면 사각형으로 */
private fun Rect.box(x0: Float, y0: Float, x1: Float, y1: Float) =
    Rect(at(x0, y0), at(x1, y1))

/**
 * 부위별 정규 영역.
 *
 * 그리기와 탭 판정이 같은 값을 쓴다 — 따로 두면 보이는 곳과 눌리는 곳이 어긋난다.
 */
private object Parts {
    /** 프렁크 — 실제로 작다. 크게 그리면 보닛 전체로 오인된다 */
    val frunk = floatArrayOf(0.045f, 0.36f, 0.145f, 0.64f)
    val cabin = floatArrayOf(0.325f, 0.05f, 0.745f, 0.95f)
    val trunk = floatArrayOf(0.825f, 0.20f, 0.965f, 0.80f)
    // 평면도는 위에서 내려다본 그림이다. 노즈가 화면 왼쪽이면 **차량 좌측(운전석)은 화면 아래**다.
    // 처음엔 운전석을 화면 위에 뒀는데, 차를 아는 사람은 3초 안에 알아챈다
    val seatLeft = floatArrayOf(0.375f, 0.58f, 0.475f, 0.85f)
    val seatRight = floatArrayOf(0.375f, 0.15f, 0.475f, 0.42f)
    val rearBench = floatArrayOf(0.585f, 0.16f, 0.655f, 0.84f)
    /** 배터리 팩 — 축 사이 바닥. 숨은 선(파선)이라 프렁크·트렁크와 겹치지 않게 끊는다 */
    val pack = floatArrayOf(0.215f, 0.055f, 0.805f, 0.945f)
}

/**
 * 바퀴 — 이 그림을 "차"로 만드는 결정적 단서.
 *
 * 바퀴가 없으면 평면 선도는 알약으로 읽힌다. 실제 축 위치(휠베이스 2,890/4,751 = 0.61)를
 * 지켜서 앞축 0.20, 뒷축 0.81에 둔다. 타이어는 차체 밖으로 살짝 나온다.
 */
private object Wheels {
    private const val HALF_LEN = 0.055f

    /**
     * 타이어는 차체 **안쪽**에 있다.
     *
     * Model Y 윤거 1,635mm는 전폭 1,921mm보다 좁다 — 처음엔 밖으로 빼서 그렸는데
     * 그러면 오픈휠 경주차가 된다. 실제 타이어 외측은 차체선에 거의 붙고
     * 내측은 반폭의 0.72 지점이다. (1,635±255)/2 를 960mm로 나눈 값이다.
     */
    private const val OUTER = 0.008f
    private const val INNER = 0.141f
    val front = 0.205f
    val rear = 0.805f

    /** 화면 위쪽(차량 우측) 타이어 */
    fun top(axleX: Float) = floatArrayOf(axleX - HALF_LEN, OUTER, axleX + HALF_LEN, INNER)

    /** 화면 아래쪽(차량 좌측) 타이어 */
    fun bottom(axleX: Float) =
        floatArrayOf(axleX - HALF_LEN, 1f - INNER, axleX + HALF_LEN, 1f - OUTER)
}

/**
 * 문 네 짝.
 *
 * 평면 도면에서 열린 문은 **경첩을 축으로 밖으로 벌어진 판**으로 그린다.
 * 이 앱의 약속("문이 열렸으면 그 문에 잉크가 든다")이 실제로 지켜지는 곳이다 —
 * "운전석 도어"라는 글자를 읽지 않아도 벌어진 문이 어느 짝인지 보인다.
 *
 * @param hingeX 경첩의 정규 x. 문은 여기서 테일 쪽으로 뻗는다
 * @param length 문 길이(정규 x)
 * @param rightSide 우측(동승석) 문인지. 벌어지는 방향이 뒤집힌다
 */
enum class CarDoor(val hingeX: Float, val length: Float, val screenBottom: Boolean) {
    // 운전석(차량 좌측)은 화면 아래다 — 위에서 내려다본 그림이라 좌우가 뒤집힌다
    DriverFront(0.345f, 0.155f, true),
    DriverRear(0.50f, 0.155f, true),
    PassengerFront(0.345f, 0.155f, false),
    PassengerRear(0.50f, 0.155f, false),
    ;

    /** 지시선이 붙는 점 — 열려 있으면 벌어진 판 중간, 닫혀 있으면 경첩 옆 */
    val anchor: Pair<Float, Float>
        get() = (hingeX + length * 0.5f) to (if (screenBottom) 0.995f else 0.005f)
}

/** 벌어지는 각도. 45도면 도면에서 "확실히 열림"으로 읽힌다 */
private const val DOOR_OPEN_DEGREES = 45.0

/** 지시선이 붙는 점. 화면 쪽에서 라벨을 놓을 때도 이 값을 쓴다 */
object CarAnchors {
    /** 운전석(차량 좌측) — 화면 아래쪽 여백으로 뻗는다 */
    val seatLeft = 0.40f to 0.85f
    /** 동승석(차량 우측) — 화면 위쪽 */
    val seatRight = 0.38f to 0.15f
    /** 잠금 — B필러 도어 핸들. 상단 가운데 칸에서 거의 수직으로 내려온다 */
    val lock = 0.50f to 0.03f
    /** 문·적재함 — 리프트게이트 */
    val openings = 0.915f to 0.16f
    /** 배터리 팩 — 아래쪽. 동승석 지시선과 교차하지 않게 오른쪽에 둔다 */
    val pack = 0.66f to 0.945f
    /** 캐빈 중앙 — 실내 온도를 여기 직접 기입한다 */
    val cabin = 0.50f to 0.50f
}

/** 정규 x를 화면 x로. 화면 쪽이 라벨을 놓을 때 쓴다 */
fun carAnchorOffset(
    anchor: Pair<Float, Float>,
    width: Float,
    height: Float,
): Offset {
    val plan = planRect(width, height)
    return plan.at(anchor.first, anchor.second)
}

/** 차가 앉은 사각형. 화면 쪽이 라벨 여백을 계산할 때 쓴다 */
fun carPlanBounds(width: Float, height: Float): Rect = planRect(width, height)

/**
 * 어느 부위를 눌렀는가.
 *
 * 작은 부위(시트)를 먼저 보고 큰 부위(차체)를 마지막에 본다 —
 * 순서를 바꾸면 시트를 눌러도 차체가 잡힌다.
 */
private fun hitPart(point: Offset, plan: Rect): CarPart? {
    fun inside(n: FloatArray) = plan.box(n[0], n[1], n[2], n[3]).contains(point)
    return when {
        inside(Parts.seatLeft) -> CarPart.SEAT_LEFT
        inside(Parts.seatRight) -> CarPart.SEAT_RIGHT
        inside(Parts.frunk) -> CarPart.FRUNK
        inside(Parts.trunk) -> CarPart.TRUNK
        inside(Parts.cabin) -> CarPart.CABIN
        plan.contains(point) -> CarPart.BODY
        else -> null
    }
}

// ---- 그리기 ----

private fun DrawScope.drawCarPlan(
    plan: Rect,
    tones: CarPlanTones,
    phase: Float,
    hairPx: Float,
    thinPx: Float,
    boldPx: Float,
) {
    val square = Stroke(width = thinPx, cap = StrokeCap.Square)
    val outline = Stroke(width = boldPx, cap = StrokeCap.Square)

    fun stateOf(part: CarPart) = tones.states[part] ?: PartState.Plain

    // 1. 배터리 팩 — 바닥에 깔린 것이라 숨은 선(파선)으로 먼저 그린다
    drawRect(
        color = tones.inkFaint,
        topLeft = plan.box(Parts.pack[0], Parts.pack[1], Parts.pack[2], Parts.pack[3]).topLeft,
        size = plan.box(Parts.pack[0], Parts.pack[1], Parts.pack[2], Parts.pack[3]).size,
        style = Stroke(
            width = hairPx,
            cap = StrokeCap.Square,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(hairPx * 14, hairPx * 7)
            ),
        ),
    )

    // 2. 차체 윤곽 — 도면에서 가장 굵은 선
    val body = bodyPath(plan)
    val bodyState = stateOf(CarPart.BODY)
    if (bodyState == PartState.Alert) {
        // 잠금이 풀렸다 — 차체 전체가 적색 윤곽이 된다. 부위 하나가 아니라 차 전체의 상태다
        drawPath(body, tones.alert, style = Stroke(width = boldPx * 1.5f, cap = StrokeCap.Square))
    } else {
        drawPath(body, tones.ink, style = outline)
    }

    // 2-1. 바퀴 4개 — 없으면 이 그림이 알약으로 읽힌다.
    // 펜더에 덮여 있으니 숨은 선(파선)으로 그리고, 차체선에는 휠하우스 개구부를
    // 짧은 직각 표시로 낸다 — 도면이 덮인 부품을 다루는 방식이다
    val hidden = Stroke(
        width = hairPx,
        cap = StrokeCap.Square,
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
            floatArrayOf(hairPx * 4, hairPx * 4)
        ),
    )
    val solidTire = Stroke(width = boldPx, cap = StrokeCap.Square)
    listOf(Wheels.front to true, Wheels.rear to false).forEach { (axle, isFront) ->
        // y가 작은 쪽이 좌측이다 (0=좌, 1=우)
        listOf(Wheels.top(axle) to true, Wheels.bottom(axle) to false).forEach { (n, isLeft) ->
            val tire = plan.box(n[0], n[1], n[2], n[3])
            val position = tirePositionOf(isFront, isLeft)
            // 공기압이 빠진 바퀴는 덮인 부품이 아니라 "지금 봐야 할 것"이다.
            // 파선을 굵은 실선 적색으로 바꿔 그 자리만 튀게 한다
            if (position in tones.lowTires) {
                drawRect(tones.alert, tire.topLeft, tire.size, style = solidTire)
            } else {
                drawRect(tones.inkMuted, tire.topLeft, tire.size, style = hidden)
            }
        }
        // 휠하우스 개구부 — 차체선 위 두 점에 짧은 턱
        listOf(0.005f, 0.995f).forEach { sideY ->
            val inward = if (sideY < 0.5f) 1f else -1f
            listOf(axle - 0.055f, axle + 0.055f).forEach { x ->
                drawLine(
                    color = tones.ink,
                    start = plan.at(x, sideY),
                    end = plan.at(x, sideY + 0.055f * inward),
                    strokeWidth = thinPx,
                    cap = StrokeCap.Square,
                )
            }
        }
    }

    // 2-2. 사이드미러 — 앞이 어디인지 한눈에 갈라 준다.
    // 노즈 곡률만으로는 앞뒤 구분이 너무 약했다
    // 바퀴보다 얕게, 얇게 그린다. 같은 크기로 두면 바퀴 여섯 개로 읽힌다
    listOf(
        floatArrayOf(0.305f, -0.028f, 0.35f, 0.004f),
        floatArrayOf(0.305f, 0.996f, 0.35f, 1.028f),
    ).forEach { n ->
        val mirror = plan.box(n[0], n[1], n[2], n[3])
        drawRect(tones.ink, mirror.topLeft, mirror.size)
    }

    // 3. 캐빈 — 윈드실드와 리어글래스로 구획한다
    drawPath(glassPath(plan, front = true), tones.ink, style = square)
    drawPath(glassPath(plan, front = false), tones.ink, style = square)

    // 캐빈 공조 상태 — 해칭이 흐른다
    val cabinRect = plan.box(Parts.cabin[0], Parts.cabin[1], Parts.cabin[2], Parts.cabin[3])
    when (stateOf(CarPart.CABIN)) {
        PartState.Cooling -> hatch(cabinRect, tones.cool, hairPx * 16, hairPx, phase)
        PartState.Heating -> hatch(cabinRect, tones.heat, hairPx * 16, hairPx, phase)
        else -> Unit
    }

    // 4. 도어 분할선 — A·B·C 필러에서 차체 안쪽으로 짧게 긋는다
    listOf(0.345f, 0.50f, 0.745f).forEach { x ->
        drawLine(
            color = tones.inkMuted,
            start = plan.at(x, 0.005f),
            end = plan.at(x, 0.17f),
            strokeWidth = thinPx,
            cap = StrokeCap.Square,
        )
        drawLine(
            color = tones.inkMuted,
            start = plan.at(x, 0.83f),
            end = plan.at(x, 0.995f),
            strokeWidth = thinPx,
            cap = StrokeCap.Square,
        )
    }

    // 4-1. 열린 문 — 경첩을 축으로 밖으로 벌어진다. 이 화면에서 가장 중요한 표시다
    tones.openDoors.forEach { door -> drawOpenDoor(plan, door, tones.alert, boldPx) }

    // 5. 프렁크 · 트렁크 — 여닫는 것들
    drawPart(plan, Parts.frunk, stateOf(CarPart.FRUNK), tones, square, hairPx)
    drawPart(plan, Parts.trunk, stateOf(CarPart.TRUNK), tones, square, hairPx)

    // 6. 좌석 — 앞 두 개는 조작 대상, 뒷벤치는 참조만
    drawSeat(plan, Parts.seatLeft, stateOf(CarPart.SEAT_LEFT), tones, square, hairPx, thinPx)
    drawSeat(plan, Parts.seatRight, stateOf(CarPart.SEAT_RIGHT), tones, square, hairPx, thinPx)
    val bench = plan.box(
        Parts.rearBench[0], Parts.rearBench[1], Parts.rearBench[2], Parts.rearBench[3]
    )
    drawRect(
        color = tones.inkFaint,
        topLeft = bench.topLeft,
        size = bench.size,
        style = Stroke(width = hairPx, cap = StrokeCap.Square),
    )
    drawLine(
        color = tones.inkFaint,
        start = Offset(bench.right + hairPx, bench.top),
        end = Offset(bench.right + hairPx, bench.bottom),
        strokeWidth = thinPx,
        cap = StrokeCap.Square,
    )

    // 7. 충전 포트 — 차량 좌측 후방이므로 화면에선 아래쪽 후방이다
    val port = plan.at(0.90f, 0.945f)
    val portRadius = plan.height * 0.04f
    if (stateOf(CarPart.PACK) == PartState.Heating || stateOf(CarPart.PACK) == PartState.Cooling) {
        drawCircle(tones.ink, portRadius, port)
    } else {
        drawCircle(tones.ink, portRadius, port, style = Stroke(width = hairPx, cap = StrokeCap.Square))
    }
}

/**
 * 열린 문 한 짝.
 *
 * 경첩에서 뻗은 판 하나와, 판 끝의 짧은 직각 표시(문 끝단)로 그린다.
 * 원래 닫혀 있던 자리는 파선으로 남겨 "여기서 열렸다"를 보인다 —
 * 도면이 움직인 부품을 표시하는 방식이다.
 */
private fun DrawScope.drawOpenDoor(
    plan: Rect,
    door: CarDoor,
    color: Color,
    boldPx: Float,
) {
    val sideY = if (door.screenBottom) 0.995f else 0.005f
    val hinge = plan.at(door.hingeX, sideY)
    val lengthPx = door.length * plan.width
    val radians = Math.toRadians(DOOR_OPEN_DEGREES)
    // 화면 아래쪽 문은 아래로(+y), 위쪽 문은 위로(-y) 벌어진다
    val outward = if (door.screenBottom) 1f else -1f
    val tip = Offset(
        hinge.x + (lengthPx * Math.cos(radians)).toFloat(),
        hinge.y + (lengthPx * Math.sin(radians)).toFloat() * outward,
    )

    // 닫힌 자리 — 파선으로 남긴다
    drawLine(
        color = color,
        start = hinge,
        end = plan.at(door.hingeX + door.length, sideY),
        strokeWidth = boldPx * 0.5f,
        cap = StrokeCap.Square,
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
            floatArrayOf(boldPx * 2, boldPx * 2)
        ),
    )
    // 벌어진 판
    drawLine(color, hinge, tip, strokeWidth = boldPx * 1.5f, cap = StrokeCap.Square)
    // 판 끝단 — 문 두께. 이게 없으면 그냥 그은 선으로 보인다
    val tickPx = plan.height * 0.06f
    drawLine(
        color = color,
        start = tip,
        end = Offset(
            tip.x - (tickPx * Math.sin(radians)).toFloat(),
            tip.y - (tickPx * Math.cos(radians)).toFloat() * outward,
        ),
        strokeWidth = boldPx,
        cap = StrokeCap.Square,
    )
}

/** 부위 하나. 정상이면 윤곽선, 작동 중이면 해칭, 열려 있으면 채운다 */
private fun DrawScope.drawPart(
    plan: Rect,
    n: FloatArray,
    state: PartState,
    tones: CarPlanTones,
    square: Stroke,
    hairPx: Float,
) {
    val rect = plan.box(n[0], n[1], n[2], n[3])
    when (state) {
        PartState.Alert -> {
            // 열린 문·트렁크는 채운다. 색만 바꾸면 흘깃 볼 때 안 걸린다
            drawRect(tones.alert, rect.topLeft, rect.size)
        }
        PartState.Cooling -> {
            drawRect(tones.ink, rect.topLeft, rect.size, style = square)
            hatch(rect, tones.cool, hairPx * 12, hairPx, 0f)
        }
        PartState.Heating -> {
            drawRect(tones.ink, rect.topLeft, rect.size, style = square)
            hatch(rect, tones.heat, hairPx * 12, hairPx, 0f)
        }
        PartState.Plain -> drawRect(tones.ink, rect.topLeft, rect.size, style = square)
    }
}

/**
 * 좌석 하나.
 *
 * 사각형 하나로 두면 프렁크와 구별이 안 된다. 등받이 선을 하나 그어
 * "앉는 것"임을 표시한다 — 도면이 부품을 구별하는 방식이다.
 */
private fun DrawScope.drawSeat(
    plan: Rect,
    n: FloatArray,
    state: PartState,
    tones: CarPlanTones,
    square: Stroke,
    hairPx: Float,
    thinPx: Float,
) {
    drawPart(plan, n, state, tones, square, hairPx)
    // 등받이 — 좌석 뒤쪽(테일 쪽) 짧은 변에 붙은 굵은 선.
    // 사각형을 가로지르는 선으로 그리면 상자 두 개로 보인다
    val rect = plan.box(n[0], n[1], n[2], n[3])
    val backX = rect.right + thinPx
    drawLine(
        color = if (state == PartState.Alert) tones.alert else tones.ink,
        start = Offset(backX, rect.top),
        end = Offset(backX, rect.bottom),
        strokeWidth = thinPx * 3f,
        cap = StrokeCap.Square,
    )
}

/**
 * 차체 윤곽.
 *
 * 노즈가 왼쪽이다. 가로 화면에서 세로로 세우면 폭이 너무 좁아져
 * 캐빈 안에 아무것도 기입할 수 없다.
 */
/** 축·좌우를 타이어 자리로 옮긴다 */
private fun tirePositionOf(
    isFront: Boolean,
    isLeft: Boolean,
): com.wemade.teslamacro.domain.model.TirePosition = when {
    isFront && isLeft -> com.wemade.teslamacro.domain.model.TirePosition.FRONT_LEFT
    isFront -> com.wemade.teslamacro.domain.model.TirePosition.FRONT_RIGHT
    isLeft -> com.wemade.teslamacro.domain.model.TirePosition.REAR_LEFT
    else -> com.wemade.teslamacro.domain.model.TirePosition.REAR_RIGHT
}

private fun bodyPath(plan: Rect): Path = Path().apply {
    // 앞을 실제로 좁힌다. 앞뒤 곡률 차이만으론 실기기에서 그냥 둥근 사각형으로 읽혔다 —
    // 노즈 앞면이 최대폭의 56%까지 좁아져야 "앞"으로 보인다.
    // 뒤 코너는 반경을 작게 남겨 각을 살린다(Model Y의 리프트게이트가 실제로 그렇다)
    moveTo(plan.at(0.165f, 0.018f))
    lineTo(plan.at(0.955f, 0.005f))
    cubicTo(plan, 0.982f, 0.005f, 0.995f, 0.028f, 0.995f, 0.07f)
    lineTo(plan.at(0.995f, 0.93f))
    cubicTo(plan, 0.995f, 0.972f, 0.982f, 0.995f, 0.955f, 0.995f)
    lineTo(plan.at(0.165f, 0.982f))
    cubicTo(plan, 0.072f, 0.966f, 0.010f, 0.88f, 0.004f, 0.72f)
    lineTo(plan.at(0.004f, 0.28f))
    cubicTo(plan, 0.010f, 0.12f, 0.072f, 0.034f, 0.165f, 0.018f)
    close()
}

/**
 * 유리 — 윈드실드와 리어글래스.
 *
 * 직선으로 그으면 캐빈이 상자로 보인다. 실제로 유리는 휘어 있고,
 * 그 곡선 하나가 이 그림을 "차"로 만든다.
 */
private fun glassPath(plan: Rect, front: Boolean): Path = Path().apply {
    if (front) {
        moveTo(plan.at(0.345f, 0.02f))
        quadTo(plan, 0.235f, 0.50f, 0.345f, 0.98f)
    } else {
        moveTo(plan.at(0.745f, 0.02f))
        quadTo(plan, 0.815f, 0.50f, 0.745f, 0.98f)
    }
}

private fun Path.moveTo(offset: Offset) = moveTo(offset.x, offset.y)
private fun Path.lineTo(offset: Offset) = lineTo(offset.x, offset.y)

private fun Path.cubicTo(
    plan: Rect,
    x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
) {
    val a = plan.at(x1, y1)
    val b = plan.at(x2, y2)
    val c = plan.at(x3, y3)
    cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
}

private fun Path.quadTo(plan: Rect, x1: Float, y1: Float, x2: Float, y2: Float) {
    val a = plan.at(x1, y1)
    val b = plan.at(x2, y2)
    quadraticBezierTo(a.x, a.y, b.x, b.y)
}

/** dp를 px로 미리 풀어서 넘기기 위한 도우미. Canvas 안에서는 Density를 못 잡는다 */
fun Density.px(dp: androidx.compose.ui.unit.Dp): Float = with(this) { dp.toPx() }

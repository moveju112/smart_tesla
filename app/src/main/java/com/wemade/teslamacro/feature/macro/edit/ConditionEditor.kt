package com.wemade.teslamacro.feature.macro.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wemade.teslamacro.data.location.TabletLocation
import com.wemade.teslamacro.data.nav.NaverNavigator
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.GeoPoint
import com.wemade.teslamacro.service.MacroService
import kotlinx.coroutines.launch
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.macro.describe
import com.wemade.teslamacro.domain.macro.formatDuration
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.SignalKind
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.ChipRow
import com.wemade.teslamacro.ui.component.NumberStepper
import com.wemade.teslamacro.ui.component.rememberOnResume
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

private val DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")

/** 위치 권한 거부 안내 — 복귀 시 자동으로 지우기 위해 상수로 비교한다 */
private const val PERMISSION_MISSING = "위치 권한이 없어 저장할 수 없어요"

/** 트리거 카드 — "언제" */
@Composable
fun TriggerCard(
    trigger: Trigger,
    onChange: (Trigger) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TCard(modifier = modifier) {
        CardHeader(describe(trigger), onRemove)
        Spacer(Modifier.height(Space.md))

        when (trigger) {
            is Trigger.SignalBecomes -> {
                ChipRow(
                    options = listOf(true, false),
                    selected = trigger.to,
                    label = { if (it) "발생할 때" else "해제될 때" },
                    onSelect = { onChange(trigger.copy(to = it)) },
                )
            }

            is Trigger.Every -> ChipRow(
                options = listOf(15, 30, 60, 120, 360),
                selected = trigger.everyMinutes,
                label = { formatDuration(it * 60) },
                onSelect = { onChange(trigger.copy(everyMinutes = it)) },
            )

            is Trigger.AtTime -> {
                TimeAndDayEditor(
                    minutesOfDay = trigger.minutesOfDay,
                    days = trigger.days,
                    onMinutesChange = { onChange(trigger.copy(minutesOfDay = it)) },
                    onDaysChange = { onChange(trigger.copy(days = it)) },
                )
            }

            is Trigger.Manual -> Text(
                text = "자동으로 발동하지 않아요. 음성으로 매크로 이름을 부르거나 " +
                    "목록에서 \"지금 실행\"을 눌러 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )

            is Trigger.Always -> Text(
                text = "다음 페이지의 조건이 충족되는 \"순간\"마다 실행해요. " +
                    "조건을 하나 이상 추가해 주세요 (예: 실내 온도 26~28℃ 사이).",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
    }
}

/** 조건 카드 — "~라면" */
@Composable
fun ConditionCard(
    condition: Condition,
    onChange: (Condition) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TCard(modifier = modifier) {
        CardHeader(describe(condition), onRemove)
        Spacer(Modifier.height(Space.md))

        when (condition) {
            is Condition.InRange -> NumericEditor(condition, onChange)

            is Condition.SignalIs -> ChipRow(
                options = listOf(true, false),
                selected = condition.value,
                label = { if (it) "인 상태" else "아닌 상태" },
                onSelect = { onChange(condition.copy(value = it)) },
            )

            is Condition.TimeWindow -> Column {
                Text("시작", style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
                HourMinuteStepper(condition.fromMinutes) {
                    onChange(condition.copy(fromMinutes = it))
                }
                Spacer(Modifier.height(Space.sm))
                Text("종료", style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
                HourMinuteStepper(condition.toMinutes) { onChange(condition.copy(toMinutes = it)) }
            }

            is Condition.OnDays -> DayToggles(condition.days) { onChange(condition.copy(days = it)) }

            is Condition.NearLocation -> NearLocationEditor(condition, onChange)
        }
    }
}

/**
 * "출발지 근처" 조건 편집.
 *
 * 위치를 찍는 방법 2가지 — 그 자리에서 현재 GPS 저장, 또는 주소 입력.
 * 위치 권한이 없으면 먼저 요청하고, 승인되는 즉시 이어서 읽는다.
 */
@Composable
private fun NearLocationEditor(
    condition: Condition.NearLocation,
    onChange: (Condition) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf("") }

    // 설정 앱에서 권한을 허용하고 돌아오면 "권한 없음" 문구가 바로 사라지게 복귀마다 다시 읽는다
    val hasLocationPermission = rememberOnResume { TabletLocation(context).hasPermission() }
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && status == PERMISSION_MISSING) status = null
    }

    // 저장된 좌표를 주소로 되돌려 보여준다 — 어디가 찍혔는지 눈으로 확인시킨다
    var savedAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(condition.latitude, condition.longitude) {
        val lat = condition.latitude
        val lng = condition.longitude
        savedAddress = if (lat != null && lng != null) {
            NaverNavigator(context).addressOf(GeoPoint(lat, lng))
        } else null
    }

    // 1. 현재 위치 읽어서 조건에 저장
    val capture: () -> Unit = {
        status = "위치 확인 중… (최대 8초)"
        scope.launch {
            val point = TabletLocation(context).read()
            if (point == null) {
                status = "위치를 읽지 못했어요. 하늘이 보이는 곳에서 다시 시도해 주세요"
            } else {
                status = null
                onChange(condition.copy(latitude = point.latitude, longitude = point.longitude))
            }
        }
    }
    // 2. 권한 승인 직후 감시 서비스를 다시 승격시킨다 — 백그라운드 위치 읽기가 그때부터 열린다.
    //    FINE만 요청하면 "대략적인 위치"를 고른 사용자가 영영 거부로 나온다 — 둘 다 요청한다
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            MacroService.start(context)
            capture()
        } else {
            status = PERMISSION_MISSING
        }
    }

    Column {
        Text(
            text = when {
                condition.latitude == null ->
                    "위치를 지정해 주세요 — 그 자리에서 저장하거나 주소로 찍을 수 있어요"
                savedAddress != null ->
                    "저장 위치: $savedAddress"
                else ->
                    "저장 위치: 좌표 %.5f, %.5f".format(condition.latitude, condition.longitude)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (condition.latitude != null) T.InkMuted else T.Warn,
        )
        if (condition.latitude != null) {
            Text(
                text = "이 근처에서 발동했을 때만 실행해요",
                style = MaterialTheme.typography.bodySmall,
                color = T.InkFaint,
            )
        }
        status?.let {
            Spacer(Modifier.height(Space.xs))
            Text(it, style = MaterialTheme.typography.bodySmall, color = T.Warn)
        }
        Spacer(Modifier.height(Space.md))
        TButton(
            text = if (condition.latitude != null) "현재 위치로 다시 저장" else "현재 위치를 출발지로 저장",
            tone = ButtonTone.Secondary,
        ) {
            if (hasLocationPermission || TabletLocation(context).hasPermission()) capture()
            else permission.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
        Spacer(Modifier.height(Space.md))
        // 현장에 안 가도 되는 두 번째 방법 — 주소를 좌표로 바꿔 저장한다
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("또는 주소로 지정 (예: 성남시 분당구 판교역로 152)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.sm))
        TButton("주소 위치로 저장", ButtonTone.Secondary, enabled = address.isNotBlank()) {
            status = "주소 확인 중…"
            scope.launch {
                val point = NaverNavigator(context).geocodePoint(address)
                if (point == null) {
                    status = "주소를 좌표로 못 바꿨어요. 도로명 주소로 다시 시도해 주세요"
                } else {
                    status = null
                    onChange(condition.copy(latitude = point.latitude, longitude = point.longitude))
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        Text("허용 반경", style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
        Spacer(Modifier.height(Space.sm))
        // 지하주차장 GPS 오차를 감안해 기본을 400m로 넉넉히 잡았다
        ChipRow(
            options = listOf(100, 400, 1000, 3000),
            selected = condition.radiusMeters,
            label = { if (it >= 1000) "${it / 1000}km" else "${it}m" },
            onSelect = { onChange(condition.copy(radiusMeters = it)) },
        )
    }
}

@Composable
private fun CardHeader(title: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
            modifier = Modifier.weight(1f),
        )
        TButton("삭제", ButtonTone.Ghost, fillWidth = false, onClick = onRemove)
    }
}

private enum class Comparison(val label: String) { GTE("이상"), LTE("이하"), BETWEEN("사이") }

@Composable
private fun NumericEditor(condition: Condition.InRange, onChange: (Condition) -> Unit) {
    val comparison = when {
        condition.gte != null && condition.lte != null -> Comparison.BETWEEN
        condition.gte != null -> Comparison.GTE
        else -> Comparison.LTE
    }
    val value = condition.gte ?: condition.lte ?: 0.0
    val range = numericRange(condition.signal)
    val step = when (condition.signal) {
        Signal.BATTERY_LEVEL, Signal.RIDE_MINUTES -> 5.0
        else -> 0.5
    }

    Column {
        ChipRow(
            options = Comparison.entries,
            selected = comparison,
            label = { it.label },
            onSelect = { onChange(rebuild(condition.signal, it, value)) },
        )
        Spacer(Modifier.height(Space.md))
        if (comparison == Comparison.BETWEEN) {
            // "26~28도 사이" 같은 구간 조건. 단계별 통풍 조절의 재료다
            Text("부터", style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
            NumberStepper(
                value = condition.gte ?: range.first,
                min = range.first,
                max = range.second,
                step = step,
                unit = condition.signal.unit.orEmpty(),
                onChange = { onChange(condition.copy(gte = it)) },
            )
            Spacer(Modifier.height(Space.sm))
            Text("까지", style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
            NumberStepper(
                value = condition.lte ?: range.second,
                min = range.first,
                max = range.second,
                step = step,
                unit = condition.signal.unit.orEmpty(),
                onChange = { onChange(condition.copy(lte = it)) },
            )
        } else {
            NumberStepper(
                value = value,
                min = range.first,
                max = range.second,
                step = step,
                unit = condition.signal.unit.orEmpty(),
                onChange = { onChange(rebuild(condition.signal, comparison, it)) },
            )
        }
    }
}

private fun rebuild(signal: Signal, comparison: Comparison, value: Double) =
    when (comparison) {
        Comparison.GTE -> Condition.InRange(signal, gte = value)
        Comparison.LTE -> Condition.InRange(signal, lte = value)
        // "사이"로 바꾸는 순간의 기본 구간: 현재 값 ±1
        Comparison.BETWEEN -> Condition.InRange(signal, gte = value - 1, lte = value + 1)
    }

@Composable
private fun TimeAndDayEditor(
    minutesOfDay: Int,
    days: Set<Int>,
    onMinutesChange: (Int) -> Unit,
    onDaysChange: (Set<Int>) -> Unit,
) {
    Column {
        HourMinuteStepper(minutesOfDay, onMinutesChange)
        Spacer(Modifier.height(Space.md))
        Text("요일", style = MaterialTheme.typography.bodySmall, color = T.InkFaint)
        Spacer(Modifier.height(Space.sm))
        DayToggles(days, onDaysChange)
    }
}

@Composable
private fun HourMinuteStepper(minutesOfDay: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        NumberStepper(
            value = (minutesOfDay / 60).toDouble(),
            min = 0.0, max = 23.0, step = 1.0, unit = "시",
            onChange = { onChange(it.toInt() * 60 + minutesOfDay % 60) },
        )
        NumberStepper(
            value = (minutesOfDay % 60).toDouble(),
            min = 0.0, max = 55.0, step = 5.0, unit = "분",
            onChange = { onChange((minutesOfDay / 60) * 60 + it.toInt()) },
        )
    }
}

/** 비어 있으면 "매일"이라는 뜻이라 전부 켜진 것처럼 보여준다 */
@Composable
private fun DayToggles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        DAY_LABELS.forEachIndexed { index, label ->
            val day = index + 1
            val selected = days.isEmpty() || day in days
            TButton(
                text = label,
                tone = if (selected) ButtonTone.Primary else ButtonTone.Secondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    val explicit = days.ifEmpty { (1..7).toSet() }
                    val updated = if (day in explicit) explicit - day else explicit + day
                    onChange(if (updated.size == 7) emptySet() else updated)
                },
            )
        }
    }
}

/** 신호별로 현실적인 조절 범위를 준다. 배터리를 -40까지 내릴 이유가 없다 */
private fun numericRange(signal: Signal): Pair<Double, Double> = when (signal) {
    Signal.BATTERY_LEVEL -> 0.0 to 100.0
    Signal.RIDE_MINUTES -> 0.0 to 300.0
    else -> -20.0 to 60.0
}

/** 신호를 고르면 종류에 맞는 기본 조건을 만든다 */
fun defaultConditionFor(signal: Signal): Condition = when (signal.kind) {
    SignalKind.NUMBER -> Condition.InRange(signal, gte = defaultThreshold(signal))
    SignalKind.BOOLEAN -> Condition.SignalIs(signal, value = true)
}

private fun defaultThreshold(signal: Signal): Double = when (signal) {
    Signal.INSIDE_TEMP -> 27.0     // 통풍 자동화의 기본 임계값
    Signal.OUTSIDE_TEMP -> 30.0
    Signal.BATTERY_LEVEL -> 20.0
    Signal.RIDE_MINUTES -> 30.0    // "오래 탔으면 애프터블로우"의 기본선
    else -> 0.0
}

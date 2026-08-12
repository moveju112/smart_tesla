package com.wemade.teslamacro.domain.voice

import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition

/** 음성 한 마디를 해석한 결과 */
sealed interface VoiceIntent {
    data class RunCommand(val command: VehicleCommand, val spoken: String) : VoiceIntent
    data class RunMacro(val rule: MacroRule, val spoken: String) : VoiceIntent

    /** "온도 낮춰줘" — 현재 값을 알아야 완성되는 의도. 실행 측이 현재 값에 delta를 더한다 */
    data class AdjustTemp(val deltaC: Double, val spoken: String) : VoiceIntent

    /** "통풍 높여줘" — 좌석 단계를 한 칸 조절 */
    data class AdjustSeat(
        val mode: SeatMode,
        val delta: Int,
        val spoken: String,
        val seat: SeatPosition = SeatPosition.FRONT_LEFT,
    ) : VoiceIntent

    /** "배터리 몇 프로야" — 차량 상태를 물어보는 말. 실행 측이 최신 값으로 답한다 */
    data class Ask(val topic: QueryTopic, val spoken: String) : VoiceIntent

    data class NotUnderstood(val spoken: String) : VoiceIntent
}

/** 음성으로 물어볼 수 있는 상태 항목 */
enum class QueryTopic { BATTERY, TEMPERATURE, LOCK }

/**
 * 한국어 음성을 차량 명령으로 바꾼다.
 *
 * 자연어 처리를 하지 않는다. **정해진 낱말 조합만** 본다 —
 * 차 안에서 오인식으로 트렁크가 열리는 것보다 "못 알아들었다"가 낫다.
 *
 * 인식기가 여러 후보를 주므로 [parseCandidates]로 전부 시도하고 첫 성공을 쓴다.
 */
class VoiceCommandParser(private val macros: () -> List<MacroRule> = { emptyList() }) {

    /** 인식 후보들을 순서대로 시도한다. 앞쪽 후보가 신뢰도가 높다 */
    fun parseCandidates(candidates: List<String>, requireWake: Boolean = false): VoiceIntent {
        candidates.forEach { candidate ->
            val intent = parse(candidate, requireWake)
            if (intent !is VoiceIntent.NotUnderstood) return intent
        }
        return VoiceIntent.NotUnderstood(candidates.firstOrNull().orEmpty())
    }

    /**
     * @param requireWake 호출어("테슬라")가 있어야만 명령으로 친다.
     *   상시 대기에서는 반드시 켠다 — 잡담 중에 트렁크가 열리면 안 된다.
     */
    fun parse(spoken: String, requireWake: Boolean = false): VoiceIntent {
        var text = normalize(spoken)

        if (requireWake) {
            val wake = WAKE_WORDS.firstOrNull { text.contains(it) }
                ?: return VoiceIntent.NotUnderstood(spoken)
            // 호출어는 걷어낸다. 남겨두면 매크로 이름 대조에 끼어든다
            text = text.replace(wake, "")
        }

        if (text.isBlank()) return VoiceIntent.NotUnderstood(spoken)

        // 1. 매크로 이름이 통째로 불렸으면 그게 최우선이다 (사용자가 직접 지은 이름).
        //    - 꺼둔 매크로도 부르면 돈다: 스위치는 "자동 발동"만 끈다 — "지금 실행" 버튼과 같은 결
        //    - normalize 결과가 비는 이름(이모지·기호만)은 모든 말에 걸리므로 제외
        //    - 여러 개 걸리면 긴 이름 우선 — "쿨링"과 "쿨링 복사본"이 있으면 부른 쪽이 이긴다
        //    조건(conditions)은 검사하지 않는다: 이름을 직접 불렀다는 건 "지금 실행" 의지다
        macros()
            .filter { normalize(it.name).isNotEmpty() }
            .filter { text.contains(normalize(it.name)) }
            .maxByOrNull { normalize(it.name).length }
            ?.let { return VoiceIntent.RunMacro(it, spoken) }

        // 2. "23도로 해줘" — 숫자 온도 지정 (정밀 인식만 숫자를 만든다).
        // normalize는 소수점을 지우므로 ("21.5도"→"215도") 원문에서 뽑는다
        absoluteTemp(spoken)?.let { return it }

        // 3. "낮춰/높여" 같은 상대 조절 — 고정 문구보다 먼저 봐야 "온도"가 켜기로 새지 않는다
        relativeIntent(text, spoken)?.let { return it }

        // 4. "배터리 몇 프로야" 같은 질문
        queryIntent(text, spoken)?.let { return it }

        // 5. 고정 문구 표
        RULES.firstOrNull { rule -> rule.matches(text) }
            ?.let { return VoiceIntent.RunCommand(it.command(text), spoken) }

        return VoiceIntent.NotUnderstood(spoken)
    }

    /**
     * 상태 질문. "몇/얼마/알려/어때" 같은 묻는 말이 있어야 질문으로 친다 —
     * 없으면 "충전구 열어" 같은 명령을 질문으로 오인한다.
     */
    private fun queryIntent(text: String, spoken: String): VoiceIntent? {
        val asking = listOf("몇", "얼마", "알려", "어때").any { text.contains(it) }
        return when {
            // "잠겼어?"는 물음말 없이도 형태가 질문이다 ("잠가"와 겹치지 않는다)
            text.contains("잠겼") || text.contains("잠겨") ->
                VoiceIntent.Ask(QueryTopic.LOCK, spoken)
            !asking -> null
            text.contains("배터리") || text.contains("충전") ->
                VoiceIntent.Ask(QueryTopic.BATTERY, spoken)
            text.contains("온도") || text.contains("더워") || text.contains("추워") ->
                VoiceIntent.Ask(QueryTopic.TEMPERATURE, spoken)
            else -> null
        }
    }

    /** "23도", "21.5도로 맞춰줘" — 차량이 받는 범위(15~28℃) 안일 때만 명령이 된다 */
    private fun absoluteTemp(spoken: String): VoiceIntent? {
        val degrees = Regex("(\\d{1,2}(?:\\.5)?)도").find(spoken)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (degrees !in 15.0..28.0) return null
        return VoiceIntent.RunCommand(VehicleCommand.SetTemperature(degrees), spoken)
    }

    /** "통풍 낮춰줘", "에어컨 온도 높여줘" — 방향과 대상만 정하고 값 계산은 실행 측에 넘긴다 */
    private fun relativeIntent(text: String, spoken: String): VoiceIntent? {
        val lower = LOWER_WORDS.any { text.contains(it) }
        val raise = RAISE_WORDS.any { text.contains(it) }
        if (lower == raise) return null   // 방향이 없거나 둘 다면 판단하지 않는다

        val delta = if (raise) 1 else -1
        return when {
            text.contains("통풍") ->
                VoiceIntent.AdjustSeat(SeatMode.COOL, delta, spoken, spokenSeat(text))
            HEAT_WORDS.any { text.contains(it) } ->
                VoiceIntent.AdjustSeat(SeatMode.HEAT, delta, spoken, spokenSeat(text))
            listOf("온도", "에어컨", "공조", "히터", "난방", "냉방").any { text.contains(it) } ->
                VoiceIntent.AdjustTemp(delta.toDouble(), spoken)
            else -> null
        }
    }


    /** 호출어가 들어 있는가. 상시 대기에서 "나한테 한 말인지" 가르는 기준이다 */
    fun hasWakeWord(spoken: String): Boolean =
        normalize(spoken).let { text -> WAKE_WORDS.any { text.contains(it) } }

    /** 공백·조사·문장부호를 걷어내 비교하기 쉽게 만든다 */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^가-힣a-z0-9]"), "")

    /**
     * 낱말 규칙 하나.
     * @param any 이 중 하나라도 있어야 한다 (대상)
     * @param verbs 이 중 하나라도 있어야 한다 (동작). 비면 대상만으로 성립
     * @param not 이게 있으면 성립하지 않는다 (반대 동작과 구분)
     */
    private class Phrase(
        val any: List<String>,
        val verbs: List<String> = emptyList(),
        val not: List<String> = emptyList(),
        val command: (String) -> VehicleCommand,
    ) {
        fun matches(text: String): Boolean {
            if (any.none { text.contains(it) }) return false
            if (not.any { text.contains(it) }) return false
            return verbs.isEmpty() || verbs.any { text.contains(it) }
        }
    }

    companion object {
        /** 상시 대기에서 이 말이 앞에 붙어야 명령으로 친다 */
        val WAKE_WORDS = listOf("테슬라", "테슬러", "tesla")

        /**
         * 인식기에 넘길 낱말 목록.
         * 이 낱말만 듣게 하면 잡담을 명령으로 잘못 알아들을 일이 줄어든다.
         */
        val VOCABULARY = listOf(
            "테슬라",
            "보닛", "본네트", "프렁크", "트렁크", "앞", "뒤",
            "문", "잠금", "잠가", "잠금해제", "언락",
            "창문", "환기",
            "통풍", "열선", "시트",
            "에어컨", "히터", "공조", "냉방", "난방",
            "충전구", "충전", "포트",
            "라이트", "비상등", "경적",
            "깨워",
            "열어", "열어줘", "닫아", "닫아줘", "켜", "켜줘", "꺼", "꺼줘", "틀어", "실행",
            // 파서 동사 목록(OPEN/CLOSE/ON/OFF)에는 있는데 여기 빠지면
            // 상시 대기 인식기가 그 낱말을 아예 못 적는다 — 두 목록을 같이 관리한다
            "오픈", "클로즈", "오프", "정지", "중지",
            // 한 글자 낱말(일·이·삼·온)은 넣지 않는다 — 잡음을 빨아들여 전체 인식을 망친다.
            // 단계 지정은 상시 대기에서는 "세게/약하게/최대"로만 받는다 (실차 사고 사례)
            "세게", "약하게", "최대",
        )

        // 열기 계열 동사. "열어", "열어줘", "오픈"
        private val OPEN = listOf("열", "오픈", "open")
        private val CLOSE = listOf("닫", "클로즈", "close")
        // "온"은 넣지 않는다 — "온도"에 들어 있어 "온도 낮춰줘"가 켜기로 오작동했다 (실차 사고)
        private val ON = listOf("켜", "틀어", "실행")
        private val OFF = listOf("꺼", "끄", "오프", "정지", "중지")

        // 상대 조절 방향. 인식기가 "높여"를 "높아"로 적는 일이 있어 변형까지 받는다 (실차 로그)
        private val LOWER_WORDS = listOf("낮춰", "내려", "줄여", "낮게", "약하게해", "시원하게")
        private val RAISE_WORDS = listOf("높여", "높아", "높게", "올려", "키워", "세게해", "따뜻하게")

        // 열선을 부르는 말들. "엉따/엉뜨"가 표준어보다 자주 쓰인다
        private val HEAT_WORDS = listOf("열선", "엉따", "엉뜨", "시트열")

        /** 어느 좌석을 말했나. 지정이 없으면 운전석이다 */
        private fun spokenSeat(text: String): SeatPosition =
            if (listOf("동승석", "조수석", "옆자리").any { text.contains(it) }) SeatPosition.FRONT_RIGHT
            else SeatPosition.FRONT_LEFT

        private val RULES = listOf(
            // ---- 보닛 / 트렁크 ----
            // 보닛은 앞, 트렁크는 뒤. 둘 다 "트렁크"로 불릴 수 있어 앞/프렁크/보닛을 먼저 본다.
            // 소형 인식 모델이 "보닛/트렁크"를 자주 못 적는다(실차 로그) — "앞/뒤 + 열어"도 받는다
            Phrase(
                any = listOf("보닛", "본네트", "본넷", "프렁크", "앞트렁크", "프론트트렁크", "앞"),
                verbs = OPEN,
            ) { VehicleCommand.OpenFrunk },

            Phrase(
                any = listOf("트렁크", "뒷트렁크", "리어트렁크", "뒤"),
                verbs = OPEN,
                not = listOf("보닛", "프렁크", "앞트렁크", "앞"),
            ) { VehicleCommand.OpenTrunk },

            // 닫기는 트렁크만 — 프렁크는 전동이 아니다. 창문 닫기와 헷갈리지 않게 창문을 배제한다
            Phrase(
                any = listOf("트렁크", "뒷트렁크", "리어트렁크", "뒤"),
                verbs = CLOSE,
                not = listOf("보닛", "프렁크", "앞트렁크", "앞", "창문", "윈도우"),
            ) { VehicleCommand.CloseTrunk },

            // ---- 잠금 ----
            Phrase(any = listOf("잠금해제", "언락", "열어줘문", "문열"), verbs = emptyList()) {
                VehicleCommand.Unlock
            },
            Phrase(any = listOf("잠가", "잠궈", "잠금", "락"), not = listOf("해제", "언락")) {
                VehicleCommand.Lock
            },

            // ---- 창문 ----
            Phrase(any = listOf("창문", "윈도우"), verbs = CLOSE) { VehicleCommand.CloseWindows },
            // "통풍 1단계"가 "환기"로 잘못 전사되면 창문이 열리는 사고가 있었다.
            // 시트 낱말이 함께 들리면 창문이 아니라 시트 명령이다
            Phrase(
                any = listOf("창문", "윈도우", "환기"),
                verbs = OPEN + listOf("환기"),
                not = listOf("통풍", "열선", "시트"),
            ) {
                VehicleCommand.VentWindows
            },

            // ---- 통풍 / 열선 ---- ("동승석 통풍 켜줘"처럼 좌석 지정 가능, 기본 운전석)
            Phrase(any = listOf("통풍"), verbs = OFF) { text ->
                VehicleCommand.SetSeatCooler(spokenSeat(text), Level.OFF)
            },
            Phrase(any = listOf("통풍"), verbs = ON + listOf("세게", "약하게", "단계", "단")) { text ->
                VehicleCommand.SetSeatCooler(spokenSeat(text), spokenLevel(text))
            },
            Phrase(any = HEAT_WORDS, verbs = OFF) { text ->
                VehicleCommand.SetSeatHeater(spokenSeat(text), Level.OFF)
            },
            Phrase(any = HEAT_WORDS, verbs = ON + listOf("세게", "약하게", "단계", "단")) { text ->
                VehicleCommand.SetSeatHeater(spokenSeat(text), spokenLevel(text))
            },

            // ---- 공조 ----
            Phrase(any = listOf("에어컨", "공조", "히터", "냉방", "난방"), verbs = OFF) {
                VehicleCommand.ClimateOff
            },
            Phrase(any = listOf("에어컨", "공조", "히터", "냉방", "난방"), verbs = ON) {
                VehicleCommand.ClimateOn
            },

            // ---- 기타 ----
            Phrase(any = listOf("충전구", "충전포트"), verbs = OPEN) {
                VehicleCommand.SetChargePort(open = true)
            },
            Phrase(any = listOf("충전구", "충전포트"), verbs = CLOSE) {
                VehicleCommand.SetChargePort(open = false)
            },
            Phrase(any = listOf("라이트", "비상등", "불빛"), verbs = ON + OPEN) {
                VehicleCommand.FlashLights
            },
            Phrase(any = listOf("경적", "클락션", "빵빵")) { VehicleCommand.Honk },
            Phrase(any = listOf("차량깨", "깨워", "웨이크")) { VehicleCommand.Wake },
        )

        /** "1단계/세게" 같은 말에서 단계를 뽑는다. 숫자 단계가 형용사보다 우선이다 */
        private fun spokenLevel(text: String): Level = when {
            text.contains("3단") || text.contains("삼단") -> Level.HIGH
            text.contains("2단") || text.contains("이단") -> Level.MEDIUM
            text.contains("1단") || text.contains("일단") -> Level.LOW
            text.contains("세게") || text.contains("최대") || text.contains("강") -> Level.HIGH
            text.contains("약하게") || text.contains("약") -> Level.LOW
            else -> Level.MEDIUM
        }
    }
}

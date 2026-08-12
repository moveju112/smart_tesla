package com.wemade.teslamacro.domain.voice

import com.wemade.teslamacro.data.macro.MacroPresets
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음성 판정 검증.
 *
 * 차 안에서 오인식으로 트렁크가 열리면 위험하다.
 * "안 열려야 하는 경우"를 여는 경우만큼 촘촘히 본다.
 */
class VoiceCommandParserTest {

    private val parser = VoiceCommandParser { MacroPresets.defaults() }

    private fun command(spoken: String): VehicleCommand? =
        (parser.parse(spoken) as? VoiceIntent.RunCommand)?.command

    // ---- 보닛 / 트렁크 ----

    @Test
    fun `보닛을 여러 표현으로 연다`() {
        listOf("보닛 열어", "보닛 열어줘", "본네트 열어", "프렁크 열어줘", "앞 트렁크 열어")
            .forEach { assertEquals(it, VehicleCommand.OpenFrunk, command(it)) }
    }

    @Test
    fun `앞 뒤 만으로도 보닛과 트렁크를 구분해 연다`() {
        // 소형 인식 모델이 "보닛/트렁크"를 못 적는 실차 로그 대응
        assertEquals(VehicleCommand.OpenFrunk, command("앞 열어"))
        assertEquals(VehicleCommand.OpenTrunk, command("뒤 열어줘"))
        assertEquals(VehicleCommand.OpenTrunk, command("트렁크 열어"))
    }

    @Test
    fun `통풍 단계 지정이 창문 환기로 새지 않는다`() {
        // 실차 사고: "통풍 1단계 켜줘"가 창문 환기로 오작동했다
        assertEquals(
            VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.LOW),
            command("통풍 1단계 켜줘"),
        )
        // "환기"로 잘못 전사돼도 통풍 낱말이 있으면 시트 명령으로 판정한다
        assertEquals(
            VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.MEDIUM),
            command("통풍 환기 켜"),
        )
        assertEquals(
            VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.MEDIUM),
            command("열선 2단계"),
        )
        assertEquals(
            VehicleCommand.SetSeatCooler(SeatPosition.FRONT_LEFT, Level.HIGH),
            command("통풍 3단계로 켜줘"),
        )
    }

    @Test
    fun `낮춰 높여 같은 상대 조절을 의도로 해석한다`() {
        // 실차 사고: "에어컨 온도 낮춰줘"의 "온"이 켜기 동사로 오인돼 공조가 켜졌다
        assertEquals(
            VoiceIntent.AdjustTemp(-1.0, "에어컨 온도 낮춰줘"),
            parser.parse("에어컨 온도 낮춰줘"),
        )
        assertEquals(
            VoiceIntent.AdjustTemp(1.0, "온도 높여 줘"),
            parser.parse("온도 높여 줘"),
        )
        assertEquals(
            VoiceIntent.AdjustSeat(com.wemade.teslamacro.domain.model.SeatMode.COOL, -1, "통풍 낮춰줘"),
            parser.parse("통풍 낮춰줘"),
        )
        // "온도"만으로는 켜기가 아니다
        assertEquals(null, command("온도"))
    }

    @Test
    fun `실차 로그의 정밀 인식 문장들이 전부 해석된다`() {
        // 2026-08-10 실차 로그에서 그대로 가져온 후보들 — 하나라도 깨지면 회귀다
        val tempDown = parser.parseCandidates(listOf("에어컨 온도 낮춰 줘", "에어컨 온도 맞춰 줘"))
        assertTrue(tempDown is VoiceIntent.AdjustTemp && tempDown.deltaC < 0)

        val tempUp = parser.parseCandidates(listOf("온도 높여 줘", "온도 높아 줘"))
        assertTrue(tempUp is VoiceIntent.AdjustTemp && tempUp.deltaC > 0)

        val ventDown = parser.parseCandidates(listOf("통풍 낮춰 줘", "통풍 낮춰줘"))
        assertTrue(ventDown is VoiceIntent.AdjustSeat && ventDown.delta < 0)
    }

    @Test
    fun `동승석과 엉따 같은 구어도 알아듣는다`() {
        assertEquals(
            VehicleCommand.SetSeatCooler(SeatPosition.FRONT_RIGHT, Level.MEDIUM),
            command("동승석 통풍 켜줘"),
        )
        assertEquals(
            VehicleCommand.SetSeatHeater(SeatPosition.FRONT_LEFT, Level.MEDIUM),
            command("엉따 켜줘"),
        )
    }

    @Test
    fun `숫자 온도 지정을 알아듣고 범위 밖은 거른다`() {
        assertEquals(VehicleCommand.SetTemperature(23.0), command("23도로 해줘"))
        assertEquals(VehicleCommand.SetTemperature(21.5), command("21.5도"))
        // 차량 범위(15~28) 밖은 명령이 아니다
        assertEquals(null, command("50도로 해줘"))
    }

    @Test
    fun `상태 질문을 알아듣고 명령과 헷갈리지 않는다`() {
        assertEquals(
            VoiceIntent.Ask(QueryTopic.BATTERY, "배터리 몇 프로야"),
            parser.parse("배터리 몇 프로야"),
        )
        assertEquals(
            VoiceIntent.Ask(QueryTopic.TEMPERATURE, "실내 온도 몇 도야"),
            parser.parse("실내 온도 몇 도야"),
        )
        assertEquals(
            VoiceIntent.Ask(QueryTopic.LOCK, "차 잠겨 있어"),
            parser.parse("차 잠겨 있어"),
        )
        // 물음말이 없으면 명령이다 — "충전구 열어"가 질문으로 새면 안 된다
        assertEquals(VehicleCommand.SetChargePort(open = true), command("충전구 열어"))
        assertEquals(VehicleCommand.Lock, command("잠가"))
    }

    @Test
    fun `트렁크 닫기를 알아듣고 창문 닫기와 구분한다`() {
        assertEquals(VehicleCommand.CloseTrunk, command("트렁크 닫아"))
        assertEquals(VehicleCommand.CloseTrunk, command("뒤 닫아줘"))
        assertEquals(VehicleCommand.CloseWindows, command("창문 닫아"))
    }

    @Test
    fun `오픈 같은 외래어 동사도 알아듣고 낱말 목록에도 있다`() {
        assertEquals(VehicleCommand.OpenFrunk, command("보닛 오픈해"))
        assertEquals(VehicleCommand.OpenTrunk, command("트렁크 오픈"))
        // 파서만 알고 인식기 낱말 목록에 빠지면 상시 대기에서 못 듣는다 — 동기화를 못박는다
        assertTrue(VoiceCommandParser.VOCABULARY.contains("오픈"))
        assertTrue(VoiceCommandParser.VOCABULARY.contains("오프"))
    }

    @Test
    fun `트렁크를 연다`() {
        listOf("트렁크 열어", "트렁크 열어줘", "뒷 트렁크 오픈")
            .forEach { assertEquals(it, VehicleCommand.OpenTrunk, command(it)) }
    }

    @Test
    fun `앞 트렁크와 뒤 트렁크를 혼동하지 않는다`() {
        // "앞 트렁크"에 '트렁크'가 들어 있어 규칙 순서가 틀리면 뒷문이 열린다
        assertEquals(VehicleCommand.OpenFrunk, command("앞 트렁크 열어줘"))
        assertEquals(VehicleCommand.OpenTrunk, command("트렁크만 열어줘"))
    }

    @Test
    fun `동사가 없으면 열지 않는다`() {
        // "트렁크에 짐 있어" 같은 혼잣말로 열리면 안 된다
        assertTrue(parser.parse("트렁크에 짐 실었어") is VoiceIntent.NotUnderstood)
        assertTrue(parser.parse("보닛 상태 어때") is VoiceIntent.NotUnderstood)
    }

    // ---- 잠금 ----

    @Test
    fun `잠금과 해제를 구분한다`() {
        assertEquals(VehicleCommand.Lock, command("문 잠가줘"))
        assertEquals(VehicleCommand.Unlock, command("잠금 해제"))
        assertEquals(VehicleCommand.Unlock, command("언락"))
    }

    // ---- 공조 / 시트 ----

    @Test
    fun `공조를 켜고 끈다`() {
        assertEquals(VehicleCommand.ClimateOn, command("에어컨 켜줘"))
        assertEquals(VehicleCommand.ClimateOff, command("에어컨 꺼줘"))
        assertEquals(VehicleCommand.ClimateOn, command("히터 틀어줘"))
    }

    @Test
    fun `통풍 세기를 말로 조절한다`() {
        assertEquals(
            VehicleCommand.SetSeatCooler(com.wemade.teslamacro.domain.model.SeatPosition.FRONT_LEFT, Level.MEDIUM),
            command("통풍 켜줘"),
        )
        assertEquals(
            Level.HIGH,
            (command("통풍 세게 켜줘") as VehicleCommand.SetSeatCooler).level,
        )
        assertEquals(
            Level.OFF,
            (command("통풍 꺼줘") as VehicleCommand.SetSeatCooler).level,
        )
    }

    @Test
    fun `창문 환기와 닫기를 구분한다`() {
        assertEquals(VehicleCommand.VentWindows, command("창문 환기"))
        assertEquals(VehicleCommand.CloseWindows, command("창문 닫아줘"))
    }

    // ---- 매크로 ----

    @Test
    fun `매크로 이름을 부르면 그 매크로가 실행된다`() {
        val intent = parser.parse("여름 탑승 쿨링 실행")
        assertTrue(intent is VoiceIntent.RunMacro)
        assertEquals("여름 탑승 쿨링", (intent as VoiceIntent.RunMacro).rule.name)
    }

    @Test
    fun `매크로 이름이 고정 문구보다 우선한다`() {
        // 사용자가 직접 지은 이름을 존중한다
        val intent = parser.parse("폭염 주차 환기")
        assertTrue(intent is VoiceIntent.RunMacro)
    }

    // ---- 안전 ----

    @Test
    fun `모르는 말은 아무것도 하지 않는다`() {
        listOf("", "음악 틀어줘 볼륨 높여", "오늘 날씨 어때", "ㅁㄴㅇㄹ")
            .forEach { assertTrue("'$it' 이 실행되면 안 된다", parser.parse(it) is VoiceIntent.NotUnderstood) }
    }

    @Test
    fun `인식 후보를 순서대로 시도해 첫 성공을 쓴다`() {
        // 인식기는 보통 여러 후보를 준다. 1순위가 헛나가도 2순위로 건진다
        val intent = parser.parseCandidates(listOf("보 닛 여러", "보닛 열어줘"))
        assertEquals(VehicleCommand.OpenFrunk, (intent as VoiceIntent.RunCommand).command)
    }

    @Test
    fun `띄어쓰기와 문장부호가 달라도 인식한다`() {
        listOf("보닛열어", "보닛, 열어!", "보닛   열어 줘")
            .forEach { assertEquals(it, VehicleCommand.OpenFrunk, command(it)) }
    }

    // ---- 호출어 (상시 대기) ----

    @Test
    fun `상시 대기에서는 호출어가 없으면 실행하지 않는다`() {
        // 마이크가 계속 열려 있으므로 잡담이 그대로 명령이 되면 안 된다
        listOf("트렁크 열어", "에어컨 켜줘", "보닛 열어줘").forEach {
            assertTrue("'$it' 이 호출어 없이 실행되면 안 된다", parser.parse(it, requireWake = true) is VoiceIntent.NotUnderstood)
        }
    }

    @Test
    fun `호출어를 붙이면 실행한다`() {
        val intent = parser.parse("테슬라 트렁크 열어", requireWake = true)
        assertEquals(VehicleCommand.OpenTrunk, (intent as VoiceIntent.RunCommand).command)
    }

    @Test
    fun `호출어가 잘못 들려도 비슷한 발음이면 받아준다`() {
        assertEquals(
            VehicleCommand.OpenFrunk,
            (parser.parse("테슬러 보닛 열어줘", requireWake = true) as VoiceIntent.RunCommand).command,
        )
    }

    @Test
    fun `호출어만 부르고 말면 아무것도 하지 않는다`() {
        assertTrue(parser.parse("테슬라", requireWake = true) is VoiceIntent.NotUnderstood)
        assertTrue(parser.parse("테슬라 오늘 날씨 어때", requireWake = true) is VoiceIntent.NotUnderstood)
    }

    @Test
    fun `호출어를 붙여 매크로도 부른다`() {
        val intent = parser.parse("테슬라 여름 탑승 쿨링", requireWake = true)
        assertTrue(intent is VoiceIntent.RunMacro)
    }

    @Test
    fun `버튼으로 부를 때는 호출어가 필요 없다`() {
        // 화면 버튼을 이미 눌렀으니 "나한테 한 말"인지 다시 물을 이유가 없다
        assertEquals(VehicleCommand.OpenTrunk, command("트렁크 열어"))
    }

    @Test
    fun `인식기에 넘길 낱말 목록에 호출어와 핵심 명령어가 들어 있다`() {
        // 목록에서 빠진 낱말은 아예 들리지 않는다
        listOf("테슬라", "트렁크", "보닛", "열어", "통풍", "에어컨").forEach {
            assertTrue("'$it' 이 낱말 목록에 없다", it in VoiceCommandParser.VOCABULARY)
        }
    }
}

package com.wemade.teslamacro.domain.command

import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition

/** 편집 화면 명령 목록의 묶음 */
enum class CommandGroup(val label: String) {
    CLIMATE("공조"),
    SEAT("시트"),
    ACCESS("잠금·개폐"),
    CHARGE("충전"),
    MEDIA("미디어"),
    SECURITY("보안·기타"),
}

/**
 * 명령을 UI에서 만들 수 있게 하는 틀.
 *
 * 파라미터 종류마다 편집 컨트롤이 다르므로 하위 타입으로 나눈다.
 * 새 명령을 추가하면 [CommandCatalog.all]에 한 줄만 더하면 편집 화면에 바로 뜬다.
 */
sealed interface CommandTemplate {
    val label: String
    val group: CommandGroup

    /** 파라미터 없음 */
    data class Simple(
        override val label: String,
        override val group: CommandGroup,
        val command: VehicleCommand,
    ) : CommandTemplate

    /** 켜기/끄기 */
    data class Toggle(
        override val label: String,
        override val group: CommandGroup,
        val build: (Boolean) -> VehicleCommand,
    ) : CommandTemplate

    /** 좌석 + 단계 */
    data class SeatLevel(
        override val label: String,
        override val group: CommandGroup,
        val seats: List<SeatPosition>,
        val build: (SeatPosition, Level) -> VehicleCommand,
    ) : CommandTemplate

    /** 숫자 하나 (온도·퍼센트) */
    data class Number(
        override val label: String,
        override val group: CommandGroup,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String,
        val build: (Double) -> VehicleCommand,
    ) : CommandTemplate

    /** 정해진 보기 중 하나 */
    data class Choice(
        override val label: String,
        override val group: CommandGroup,
        val options: List<Pair<String, VehicleCommand>>,
    ) : CommandTemplate
}

/**
 * 편집 화면이 나열하는 명령 목록.
 *
 * **확장 지점 2/3의 UI 절반**: 여기에 항목을 추가하면 편집 화면 수정 없이 노출된다.
 */
object CommandCatalog {

    private val frontSeats = listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT)

    val all: List<CommandTemplate> = listOf(
        // ---- 공조 ----
        CommandTemplate.Toggle("공조 전원", CommandGroup.CLIMATE) {
            if (it) VehicleCommand.ClimateOn else VehicleCommand.ClimateOff
        },
        CommandTemplate.Number("목표 온도", CommandGroup.CLIMATE, 15.0, 28.0, 0.5, "℃") {
            VehicleCommand.SetTemperature(it)
        },
        CommandTemplate.Choice(
            "Climate Keeper", CommandGroup.CLIMATE,
            ClimateKeeperMode.entries.map { it.label to VehicleCommand.SetClimateKeeper(it) },
        ),
        CommandTemplate.Choice(
            "캐빈 과열보호", CommandGroup.CLIMATE,
            listOf(
                "켜기" to VehicleCommand.SetCabinOverheatProtection(enabled = true),
                "팬만" to VehicleCommand.SetCabinOverheatProtection(true, fanOnly = true),
                "끄기" to VehicleCommand.SetCabinOverheatProtection(enabled = false),
            ),
        ),

        // ---- 시트 ----
        // 통풍은 앞좌석만 가능하다 (프로토콜 제약)
        CommandTemplate.SeatLevel("통풍 시트", CommandGroup.SEAT, frontSeats) { seat, level ->
            VehicleCommand.SetSeatCooler(seat, level)
        },
        CommandTemplate.SeatLevel(
            "열선 시트", CommandGroup.SEAT, SeatPosition.entries.toList()
        ) { seat, level -> VehicleCommand.SetSeatHeater(seat, level) },
        CommandTemplate.Choice(
            "자동 시트 온도", CommandGroup.SEAT,
            frontSeats.flatMap { seat ->
                listOf(
                    "${seat.label} 켜기" to VehicleCommand.SetAutoSeatClimate(seat, true),
                    "${seat.label} 끄기" to VehicleCommand.SetAutoSeatClimate(seat, false),
                )
            },
        ),
        CommandTemplate.Toggle("스티어링 열선", CommandGroup.SEAT) {
            VehicleCommand.SetSteeringWheelHeater(it)
        },

        // ---- 잠금·개폐 ----
        CommandTemplate.Choice(
            "잠금", CommandGroup.ACCESS,
            listOf("잠금" to VehicleCommand.Lock, "해제" to VehicleCommand.Unlock),
        ),
        CommandTemplate.Choice(
            "창문", CommandGroup.ACCESS,
            listOf("환기" to VehicleCommand.VentWindows, "닫기" to VehicleCommand.CloseWindows),
        ),
        CommandTemplate.Choice(
            "트렁크", CommandGroup.ACCESS,
            listOf("열기" to VehicleCommand.OpenTrunk, "닫기" to VehicleCommand.CloseTrunk),
        ),
        CommandTemplate.Simple("보닛(프렁크) 열기", CommandGroup.ACCESS, VehicleCommand.OpenFrunk),

        // ---- 충전 ----
        CommandTemplate.Toggle("충전", CommandGroup.CHARGE) { VehicleCommand.SetCharging(it) },
        CommandTemplate.Number("충전 한도", CommandGroup.CHARGE, 50.0, 100.0, 5.0, "%") {
            VehicleCommand.SetChargeLimit(it.toInt())
        },
        CommandTemplate.Toggle("충전구", CommandGroup.CHARGE) { VehicleCommand.SetChargePort(it) },
        CommandTemplate.Number("충전 전류", CommandGroup.CHARGE, 5.0, 48.0, 1.0, "A") {
            VehicleCommand.SetChargingAmps(it.toInt())
        },

        // ---- 미디어 ----
        CommandTemplate.Simple("재생/일시정지", CommandGroup.MEDIA, VehicleCommand.ToggleMedia),
        CommandTemplate.Simple("다음 곡", CommandGroup.MEDIA, VehicleCommand.NextTrack),
        CommandTemplate.Simple("이전 곡", CommandGroup.MEDIA, VehicleCommand.PreviousTrack),
        CommandTemplate.Simple("다음 즐겨찾기", CommandGroup.MEDIA, VehicleCommand.NextFavorite),
        CommandTemplate.Number("볼륨", CommandGroup.MEDIA, 0.0, 11.0, 1.0, "") {
            VehicleCommand.SetVolume(it)
        },

        // ---- 보안·기타 ----
        CommandTemplate.Toggle("센트리 모드", CommandGroup.SECURITY) {
            VehicleCommand.SetSentryMode(it)
        },
        CommandTemplate.Simple("라이트 점멸", CommandGroup.SECURITY, VehicleCommand.FlashLights),
        CommandTemplate.Simple("경적", CommandGroup.SECURITY, VehicleCommand.Honk),
        CommandTemplate.Simple("차량 깨우기", CommandGroup.SECURITY, VehicleCommand.Wake),
        CommandTemplate.Toggle("발렛 모드", CommandGroup.SECURITY) { VehicleCommand.SetValetMode(it) },
        CommandTemplate.Toggle("게스트 모드", CommandGroup.SECURITY) { VehicleCommand.SetGuestMode(it) },
        CommandTemplate.Toggle("저전력 모드", CommandGroup.SECURITY) {
            VehicleCommand.SetLowPowerMode(it)
        },
        CommandTemplate.Toggle("액세서리 전원 유지", CommandGroup.SECURITY) {
            VehicleCommand.SetKeepAccessoryPower(it)
        },
    )

    val byGroup: Map<CommandGroup, List<CommandTemplate>> = all.groupBy { it.group }

    /** 템플릿에서 기본값으로 명령 하나를 만든다 (목록에서 고르자마자 추가할 때) */
    fun defaultCommand(template: CommandTemplate): VehicleCommand = when (template) {
        is CommandTemplate.Simple -> template.command
        is CommandTemplate.Toggle -> template.build(true)
        is CommandTemplate.SeatLevel -> template.build(template.seats.first(), Level.MEDIUM)
        is CommandTemplate.Number -> template.build(defaultNumber(template))
        is CommandTemplate.Choice -> template.options.first().second
    }

    /** 범위 한가운데를 기본값으로 쓴다 */
    private fun defaultNumber(template: CommandTemplate.Number): Double {
        val middle = (template.min + template.max) / 2
        return (Math.round(middle / template.step) * template.step)
    }
}

package com.wemade.teslamacro.domain.command

import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 주차 중 실내 온도를 유지하는 모드 */
@Serializable
enum class ClimateKeeperMode(val label: String) {
    OFF("끔"), ON("유지"), DOG("도그"), CAMP("캠프")
}

/**
 * 차량에 보낼 수 있는 명령.
 *
 * **확장 지점 2/3**: 새 명령은 여기에 하위 타입 하나를 추가하고,
 * 게이트웨이 인코더에 `when` 분기 하나를 더하면 끝난다. 화면·매크로는 그대로다.
 *
 * 전송 수단(BLE/클라우드)에 의존하지 않는 순수 데이터라 그대로 직렬화해 저장한다.
 */
@Serializable
sealed interface VehicleCommand {

    /** 로그·매크로 목록에 그대로 노출되는 사람이 읽는 이름 */
    val label: String

    // ---- 공조 ----
    @Serializable @SerialName("climate_on")
    data object ClimateOn : VehicleCommand {
        override val label get() = "공조 켜기"
    }

    @Serializable @SerialName("climate_off")
    data object ClimateOff : VehicleCommand {
        override val label get() = "공조 끄기"
    }

    @Serializable @SerialName("set_temp")
    data class SetTemperature(val celsius: Double) : VehicleCommand {
        override val label get() = "목표 온도 ${celsius}℃"
    }

    @Serializable @SerialName("seat_cooler")
    data class SetSeatCooler(val seat: SeatPosition, val level: Level) : VehicleCommand {
        override val label get() = "${seat.label} 통풍 ${level.label}"
    }

    @Serializable @SerialName("seat_heater")
    data class SetSeatHeater(val seat: SeatPosition, val level: Level) : VehicleCommand {
        override val label get() = "${seat.label} 열선 ${level.label}"
    }

    @Serializable @SerialName("auto_seat_climate")
    data class SetAutoSeatClimate(val seat: SeatPosition, val enabled: Boolean) : VehicleCommand {
        override val label get() = "${seat.label} 자동 시트 ${if (enabled) "켜기" else "끄기"}"
    }

    @Serializable @SerialName("steering_wheel_heater")
    data class SetSteeringWheelHeater(val enabled: Boolean) : VehicleCommand {
        override val label get() = "스티어링 열선 ${if (enabled) "켜기" else "끄기"}"
    }

    // ---- 잠금 / 개폐 ----
    @Serializable @SerialName("lock")
    data object Lock : VehicleCommand {
        override val label get() = "잠금"
    }

    @Serializable @SerialName("unlock")
    data object Unlock : VehicleCommand {
        override val label get() = "잠금 해제"
    }

    @Serializable @SerialName("windows_vent")
    data object VentWindows : VehicleCommand {
        override val label get() = "창문 환기"
    }

    @Serializable @SerialName("windows_close")
    data object CloseWindows : VehicleCommand {
        override val label get() = "창문 닫기"
    }

    @Serializable @SerialName("climate_keeper")
    data class SetClimateKeeper(val mode: ClimateKeeperMode) : VehicleCommand {
        override val label get() = "climate keeper ${mode.label}"
    }

    @Serializable @SerialName("cabin_overheat")
    data class SetCabinOverheatProtection(val enabled: Boolean, val fanOnly: Boolean = false) :
        VehicleCommand {
        override val label
            get() = "캐빈 과열보호 " + when {
                !enabled -> "끄기"
                fanOnly -> "팬만"
                else -> "켜기"
            }
    }

    // ---- 적재 공간 ----
    @Serializable @SerialName("trunk")
    data object OpenTrunk : VehicleCommand {
        override val label get() = "트렁크 열기"
    }

    // 닫기는 트렁크만 — 프렁크는 전동이 아니라 손으로 닫아야 한다
    @Serializable @SerialName("trunk_close")
    data object CloseTrunk : VehicleCommand {
        override val label get() = "트렁크 닫기"
    }

    @Serializable @SerialName("frunk")
    data object OpenFrunk : VehicleCommand {
        override val label get() = "보닛(프렁크) 열기"
    }

    @Serializable @SerialName("charge_port")
    data class SetChargePort(val open: Boolean) : VehicleCommand {
        override val label get() = if (open) "충전구 열기" else "충전구 닫기"
    }

    // ---- 보안 / 기타 ----
    @Serializable @SerialName("sentry")
    data class SetSentryMode(val enabled: Boolean) : VehicleCommand {
        override val label get() = "센트리 ${if (enabled) "켜기" else "끄기"}"
    }

    @Serializable @SerialName("flash_lights")
    data object FlashLights : VehicleCommand {
        override val label get() = "라이트 점멸"
    }

    @Serializable @SerialName("honk")
    data object Honk : VehicleCommand {
        override val label get() = "경적"
    }

    @Serializable @SerialName("wake")
    data object Wake : VehicleCommand {
        override val label get() = "차량 깨우기"
    }

    // ---- 미디어 ----
    @Serializable @SerialName("media_toggle")
    data object ToggleMedia : VehicleCommand {
        override val label get() = "재생/일시정지"
    }

    @Serializable @SerialName("media_next")
    data object NextTrack : VehicleCommand {
        override val label get() = "다음 곡"
    }

    @Serializable @SerialName("media_prev")
    data object PreviousTrack : VehicleCommand {
        override val label get() = "이전 곡"
    }

    @Serializable @SerialName("media_volume")
    data class SetVolume(val level: Double) : VehicleCommand {
        override val label get() = "볼륨 ${level.toInt()}"
    }

    @Serializable @SerialName("media_favorite")
    data object NextFavorite : VehicleCommand {
        override val label get() = "다음 즐겨찾기"
    }

    // ---- 접근 제어 ----
    @Serializable @SerialName("valet")
    data class SetValetMode(val enabled: Boolean, val pin: String = "") : VehicleCommand {
        override val label get() = "발렛 모드 ${if (enabled) "켜기" else "끄기"}"
    }

    @Serializable @SerialName("guest")
    data class SetGuestMode(val enabled: Boolean) : VehicleCommand {
        override val label get() = "게스트 모드 ${if (enabled) "켜기" else "끄기"}"
    }

    // ---- 전원 관리 ----
    @Serializable @SerialName("low_power")
    data class SetLowPowerMode(val enabled: Boolean) : VehicleCommand {
        override val label get() = "저전력 모드 ${if (enabled) "켜기" else "끄기"}"
    }

    @Serializable @SerialName("keep_accessory_power")
    data class SetKeepAccessoryPower(val enabled: Boolean) : VehicleCommand {
        override val label get() = "액세서리 전원 유지 ${if (enabled) "켜기" else "끄기"}"
    }

    // ---- 충전 ----
    @Serializable @SerialName("charge_limit")
    data class SetChargeLimit(val percent: Int) : VehicleCommand {
        override val label get() = "충전 한도 ${percent}%"
    }

    @Serializable @SerialName("charging")
    data class SetCharging(val start: Boolean) : VehicleCommand {
        override val label get() = if (start) "충전 시작" else "충전 중지"
    }

    @Serializable @SerialName("charge_amps")
    data class SetChargingAmps(val amps: Int) : VehicleCommand {
        override val label get() = "충전 전류 ${amps}A"
    }
}

/**
 * 주행 중 실행되면 치명적인 명령인가.
 * 이 명령들은 P단이 확인될 때만 차량에 보낸다 (게이트웨이에서 차단).
 * 창문은 사용자 결정으로 제외했다 — 환기는 주행 중에도 쓸 일이 있다.
 */
fun VehicleCommand.requiresPark(): Boolean =
    this is VehicleCommand.OpenFrunk ||
        this is VehicleCommand.OpenTrunk ||
        this is VehicleCommand.CloseTrunk

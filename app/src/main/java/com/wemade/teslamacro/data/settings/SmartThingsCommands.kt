package com.wemade.teslamacro.data.settings

/** 스마트싱스 알림에 연결할 수 있는 기존 빠른 차량 동작. */
data class SmartThingsCommand(
    val action: String,
    val label: String,
)

object SmartThingsCommands {
    val all = listOf(
        SmartThingsCommand("open_frunk", "보닛(프렁크) 열기"),
        SmartThingsCommand("open_trunk", "트렁크 열기"),
        SmartThingsCommand("lock", "문 잠그기"),
        SmartThingsCommand("unlock", "문 잠금 해제"),
        SmartThingsCommand("climate_on", "공조 켜기"),
        SmartThingsCommand("climate_off", "공조 끄기"),
        SmartThingsCommand("vent_windows", "창문 환기"),
        SmartThingsCommand("close_windows", "창문 닫기"),
    )

    /** 기존 프렁크 설정을 유지하면서 새 동작은 빈 문구로 시작한다. */
    fun defaults(frunkText: String = DEFAULT_SMARTTHINGS_FRUNK_TEXT): Map<String, String> =
        all.associate { command ->
            command.action to if (command.action == "open_frunk") frunkText else ""
        }

    /** 저장소에 없는 동작을 채우고 더는 지원하지 않는 동작은 버린다. */
    fun normalized(texts: Map<String, String>): Map<String, String> =
        all.associate { command -> command.action to texts[command.action].orEmpty() }
}

const val DEFAULT_SMARTTHINGS_FRUNK_TEXT = "ㅎㅎㅎㅎㅎ"
const val MAX_SMARTTHINGS_COMMAND_TEXT_LENGTH = 80

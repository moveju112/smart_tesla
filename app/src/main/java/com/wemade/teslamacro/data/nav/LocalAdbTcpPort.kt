package com.wemade.teslamacro.data.nav

/** 현재 데몬 설정이 있으면 비활성 값도 존중하고, 없을 때만 영구 설정을 사용한다. */
internal fun localAdbTcpPort(properties: String): Int? {
    val values = properties.lineSequence().mapNotNull { line ->
        Regex("^\\[(service\\.adb\\.tcp\\.port|persist\\.adb\\.tcp\\.port)]: \\[(.*)]$")
            .matchEntire(line)?.destructured?.let { (key, value) -> key to value }
    }.toMap()
    val current = values["service.adb.tcp.port"]?.takeIf { it.isNotBlank() }
        ?: values["persist.adb.tcp.port"].orEmpty()
    return WirelessNavigation.validPort(current)
}

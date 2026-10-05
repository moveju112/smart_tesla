package com.wemade.teslamacro.data.nav

/** 설정 화면에서 코드와 주소가 함께 보일 때만 페어링 정보로 받아들인다. */
internal object PairingScreen {
    /** 다른 설정 숫자를 코드로 오인하지 않도록 전용 안내와 로컬 포트를 함께 요구한다. */
    fun read(texts: List<String>): Pair<String, String>? {
        val joined = texts.joinToString(" ")
        if (!joined.contains("페어링 코드") && !joined.contains("pairing code", ignoreCase = true)) return null
        val code = texts.map { it.replace(" ", "").trim() }.firstOrNull { it.matches(Regex("[0-9]{6}")) } ?: return null
        val port = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{1,5})").find(joined)?.groupValues?.get(1) ?: return null
        return WirelessNavigation.validPort(port)?.let { port to code }
    }
}

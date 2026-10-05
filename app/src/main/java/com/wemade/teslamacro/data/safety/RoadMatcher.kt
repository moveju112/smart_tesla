package com.wemade.teslamacro.data.safety

import kotlinx.serialization.json.*

internal data class RoadHttpResponse(val code: Int = 0, val body: String = "")

/** 인증 응답에서 문자열이 아닌 값은 헤더·파일로 보내지 않는다. */
private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** 서버가 보낸 30분 이하의 기기별 토큰만 매칭 헤더에 사용한다. */
internal fun parseRoadAccess(body: String): Pair<String, Long>? = runCatching {
    val result = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
    val token = result["accessToken"].asString()
        ?.takeIf { it.length in 8..1024 && it.matches(Regex("rm1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) }
        ?: return@runCatching null
    val expires = (result["expiresInSeconds"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.content?.toLongOrNull()
        ?.takeIf { it in 1..1_800 } ?: return@runCatching null
    token to expires
}.getOrNull()

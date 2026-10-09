package com.wemade.teslamacro.data.nav

import com.wemade.teslamacro.domain.macro.GeoPoint
import java.net.URI
import java.net.URLDecoder
import kotlin.math.cos
import kotlin.math.pow

internal data class TeslaDestinationCandidate(val address: String, val point: GeoPoint? = null) {
    val shareText: String get() = point?.let { "${it.latitude},${it.longitude}" } ?: address
}

internal data class TeslaDestinationSelection(
    val id: Long,
    val query: String,
    val candidates: List<TeslaDestinationCandidate> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val testMode: Boolean = false,
    // 테스트 모드에서 실제로 보냈을 목적지; 값이 있으면 선택 대신 결과만 보여준다.
    val preview: TeslaDestinationCandidate? = null,
)

/** 목적지 자체의 좌표만 읽고 지도 중심·출발 좌표·짧은 링크를 좌표로 추측하지 않는다. */
internal fun teslaDestinationPoint(value: String): GeoPoint? = runCatching {
    teslaCoordinatePair(value.trim())?.let { return@runCatching it }
    val uri = URI(value.trim())
    if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return@runCatching null
    val parameters = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }
    if (parameters.map { it.first }.distinct().size != parameters.size) return@runCatching null
    val query = parameters.toMap()
    when {
        uri.scheme == "nmap" && uri.host == "route" && uri.path == "/car" ->
            teslaCoordinatePair("${query["dlat"]},${query["dlng"]}")
        uri.scheme == "nmap" && uri.host == "place" ->
            teslaCoordinatePair("${query["lat"]},${query["lng"]}")
        uri.scheme == "https" && uri.host in setOf("maps.google.com", "www.google.com") &&
            uri.path in setOf("/maps", "/maps/search/", "/maps/search") ->
            teslaCoordinatePair(query["q"] ?: query["query"].orEmpty())
        else -> null
    }
}.getOrNull()

/** 좌표 범위와 형식을 확인해 주소의 쉼표나 일부 좌표를 지도 위치로 오인하지 않는다. */
private fun teslaCoordinatePair(value: String): GeoPoint? {
    if (!Regex("^-?\\d{1,3}(?:\\.\\d+)?\\s*,\\s*-?\\d{1,3}(?:\\.\\d+)?$").matches(value)) return null
    val parts = value.split(',').map { it.trim().toDoubleOrNull() ?: return null }
    return GeoPoint(parts[0], parts[1]).takeIf { validTeslaDestinationPoint(it) }
}

/** 유효하지 않은 좌표와 누락값을 자동 공유에 사용하지 않는다. */
internal fun validTeslaDestinationPoint(point: GeoPoint): Boolean =
    point.latitude.isFinite() && point.longitude.isFinite() && point.latitude in -90.0..90.0 &&
        point.longitude in -180.0..180.0 && !(point.latitude == 0.0 && point.longitude == 0.0)

/** 좌표 조회 실패 때 시·도와 시·군·구를 포함한 전체 도로명주소만 공유 후보로 쓴다. */
internal fun qualifiedTeslaRoadAddress(value: String): Boolean {
    val text = canonicalTeslaAddress(value)
    return Regex("^(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)\\s+").containsMatchIn(text) &&
        (text.startsWith("세종 ") || Regex("\\s[^ ]+[시군구]\\s").containsMatchIn(text)) &&
        Regex("\\s[^ ]+(?:대로|로|길)\\s+\\d+(?:-\\d+)?$").containsMatchIn(text)
}

/** 행정구역 표기만 통일하고 도로명·건물번호를 흐리게 비교하지 않는다. */
internal fun canonicalTeslaAddress(value: String): String {
    var text = normalizeTeslaRoadSpacing(value.trim().removePrefix("대한민국 ").replace(Regex("\\s+"), " "))
    val regions = mapOf("서울특별시" to "서울", "부산광역시" to "부산", "대구광역시" to "대구", "인천광역시" to "인천",
        "광주광역시" to "광주", "대전광역시" to "대전", "울산광역시" to "울산", "세종특별자치시" to "세종",
        "경기도" to "경기", "강원특별자치도" to "강원", "강원도" to "강원", "충청북도" to "충북", "충청남도" to "충남",
        "전북특별자치도" to "전북", "전라북도" to "전북", "전라남도" to "전남", "경상북도" to "경북", "경상남도" to "경남",
        "제주특별자치도" to "제주")
    for ((full, short) in regions) if (text.startsWith("$full ")) { text = short + text.removePrefix(full); break }
    return text
}

/** 도로명과 숫자 번길 사이 공백만 합쳐 장소명의 일반 공백은 유지한다. */
internal fun normalizeTeslaRoadSpacing(value: String): String =
    value.replace(Regex("([가-힣A-Za-z0-9·]+(?:대로|로))\\s+(\\d+(?:번)?길)(?=\\s|$)"), "$1$2")

/** 도로명주소는 건물번호·명시한 지역까지 맞는 후보만 유지해 단일 오검색도 자동 공유하지 않는다. */
internal fun matchingTeslaAddressCandidates(query: String, candidates: List<TeslaDestinationCandidate>): List<TeslaDestinationCandidate> {
    val roadPattern = Regex("(?:^|\\s)([^ ]+(?:대로|로|길))\\s+(\\d+(?:-\\d+)?)(?=\\s|$)")
    val address = canonicalTeslaAddress(query)
    val requested = roadPattern.find(address) ?: return candidates
    val regions = address.substring(0, requested.range.first).trim().split(' ').filter { it.isNotEmpty() }
    return candidates.filter { candidate ->
        val result = canonicalTeslaAddress(candidate.address)
        val found = roadPattern.find(result)
        found != null && found.groupValues.drop(1) == requested.groupValues.drop(1) &&
            regions.all { it in result.substring(0, found.range.first).trim().split(' ') }
    }
}

/** 가까운 후보는 먼저 보여주되 위치와 첫 결과를 자동 확정 근거로 사용하지 않는다. */
internal fun rankedTeslaCandidates(candidates: List<TeslaDestinationCandidate>, near: GeoPoint?): List<TeslaDestinationCandidate> =
    candidates.filter { it.address.isNotBlank() && it.address.length <= 200 && it.address.none { ch -> ch.code < 32 } &&
        (it.point == null || validTeslaDestinationPoint(it.point)) }
        .distinctBy { canonicalTeslaAddress(it.address) to it.shareText }
        .sortedBy { candidate ->
            if (near == null || !validTeslaDestinationPoint(near) || candidate.point == null) Double.MAX_VALUE
            else (candidate.point.latitude - near.latitude).pow(2) +
                ((candidate.point.longitude - near.longitude) * cos(Math.toRadians(near.latitude))).pow(2)
        }.take(10)

/** 조회 후보가 하나일 때만 자동 선택하고 복수 후보는 가까워도 선택받는다. */
internal fun automaticTeslaCandidate(candidates: List<TeslaDestinationCandidate>): TeslaDestinationCandidate? = candidates.singleOrNull()

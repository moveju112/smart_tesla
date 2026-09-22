package com.wemade.teslamacro.domain.model

/** 두 온도를 모두 읽은 뒤 높은 단계 하나만 고르며 미확인은 추측하지 않는다. */
internal fun recommendedSeatCoolingLevel(inside: Double?, outside: Double?): Int? {
    if (inside == null || outside == null || !inside.isFinite() || !outside.isFinite()) return null
    return when {
        inside >= 29.0 || outside >= 30.0 -> 3
        inside >= 25.0 || outside >= 26.0 -> 2
        inside >= 22.0 || outside >= 23.0 -> 1
        else -> 0
    }
}

package com.wemade.teslamacro.domain.macro

/**
 * 초를 사람이 읽는 길이로 바꾼다.
 *
 * "300초 대기"는 계산을 시킨다. "5분 대기"는 바로 읽힌다.
 * 목록·편집·로그가 전부 같은 표기를 쓰도록 한 곳에 모았다.
 */
fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "${seconds}초"
    seconds % 3600 == 0 -> "${seconds / 3600}시간"
    seconds % 60 == 0 -> "${seconds / 60}분"
    else -> "${seconds / 60}분 ${seconds % 60}초"
}

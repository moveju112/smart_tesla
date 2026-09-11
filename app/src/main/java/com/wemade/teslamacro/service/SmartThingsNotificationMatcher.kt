package com.wemade.teslamacro.service

/** 스마트싱스가 게시한 알림의 개별 텍스트 칸과 설정 문구가 정확히 같은지 판정한다. */
internal fun matchesSmartThingsFrunkNotification(
    packageName: String,
    texts: List<String>,
    triggerText: String,
): Boolean {
    val expected = triggerText.trim()
    return packageName == SMARTTHINGS_PACKAGE_NAME &&
        expected.isNotEmpty() &&
        texts.any { it.trim() == expected }
}

/** 같은 알림 게시 건이 갱신 콜백으로 다시 들어와도 차량 명령은 한 번만 통과시킨다. */
internal class NotificationReceiptGuard(
    private val capacity: Int = 16,
    private val duplicateWindowMillis: Long = 2 * 60 * 1000L,
) {
    private val receivedAtByKey = LinkedHashMap<String, Long>()

    /** 마지막 수신 뒤 프렁크 요청 제한 시간이 지났을 때만 같은 알림 키를 다시 받는다. */
    fun accept(key: String, receivedAtMillis: Long): Boolean {
        val lastReceivedAt = receivedAtByKey[key]
        if (lastReceivedAt != null && receivedAtMillis - lastReceivedAt < duplicateWindowMillis) {
            return false
        }
        receivedAtByKey.remove(key)
        receivedAtByKey[key] = receivedAtMillis
        while (receivedAtByKey.size > capacity) {
            receivedAtByKey.remove(receivedAtByKey.keys.first())
        }
        return true
    }
}

internal const val SMARTTHINGS_PACKAGE_NAME = "com.samsung.android.oneconnect"

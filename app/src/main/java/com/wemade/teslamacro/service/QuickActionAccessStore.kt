package com.wemade.teslamacro.service

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 연동키와 바로가기 서명키를 백업에서 제외하고 앱 전용 저장소에 보관한다. */
class QuickActionAccessStore internal constructor(private val directory: File) {
    /** 앱 설정 백업에 차량 명령 권한이 섞이지 않게 noBackupFilesDir를 사용한다. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, "quick-action-access"))

    /** 외부 앱 인증은 사용자 발급 키로, 런처 인증은 명령에 묶인 서명으로 검사한다. */
    fun isAuthorized(action: String?, macroId: String?, automationKey: String?, shortcutToken: String?): Boolean = synchronized(lock) {
        matchesKey(readKey("automation"), automationKey) ||
            matchesKey(readKey("shortcuts")?.let { sign(it, action, macroId) }, shortcutToken)
    }

    /** 앱이 발행하는 바로가기의 action과 macro_id를 함께 서명해 변조를 막는다. */
    fun shortcutToken(action: String?, macroId: String?): String = synchronized(lock) {
        sign(createKey("shortcuts"), action, macroId)
    }

    /** 사용자가 복사를 요청한 경우에만 외부 자동화 연동키를 발급한다. */
    fun automationKey(): String = synchronized(lock) { createKey("automation") }

    /** UI에는 연동키 원문 대신 발급 여부만 표시한다. */
    fun hasAutomationKey(): Boolean = synchronized(lock) { readKey("automation") != null }

    /** 외부 연동만 해제하고 앱이 발행한 바로가기의 인증은 유지한다. */
    fun revokeAutomationKey() = synchronized(lock) {
        val file = File(directory, "automation")
        check(!file.exists() || file.delete()) { "외부 자동화 연동을 해제하지 못했어요" }
    }

    /** 손상·누락된 키는 인증 실패로 처리하며 원문을 오류에 넣지 않는다. */
    private fun readKey(name: String): String? = runCatching {
        val file = File(directory, name)
        if (file.length() != 64L) return@runCatching null
        file.readText(Charsets.US_ASCII).takeIf { keyPattern.matches(it) }
    }.getOrNull()

    /** 생성과 저장을 같은 잠금에서 처리해 동시에 발급한 키가 엇갈리지 않게 한다. */
    private fun createKey(name: String): String {
        readKey(name)?.let { return it }
        check(directory.isDirectory || directory.mkdirs()) { "명령 인증키를 저장하지 못했어요" }
        val key = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        File(directory, name).writeText(key, Charsets.US_ASCII)
        return key
    }

    /** 명령과 매크로 식별자 모두 서명해 삭제된 매크로의 토큰도 다른 동작에 쓰지 못하게 한다. */
    private fun sign(key: String, action: String?, macroId: String?): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal("${action.orEmpty()}\u0000${macroId.orEmpty()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 키 길이·형식을 먼저 제한하고 동일 길이 값은 상수 시간 비교로 검사한다. */
    private fun matchesKey(expected: String?, supplied: String?): Boolean = expected != null && supplied != null &&
        keyPattern.matches(supplied) && MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))

    private companion object {
        val lock = Any()
        val keyPattern = Regex("[0-9a-f]{64}")
    }
}

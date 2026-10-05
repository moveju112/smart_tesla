package com.wemade.teslamacro.data.nav

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.delay

/** 셸이 전달한 Binder만 보관하며 앱 재시작 때 기존 프로세스와 다시 연결한다. */
class NavigationBridgeProvider : ContentProvider() {
    /** 데이터 저장소 없이 권한 통신의 전달 지점만 제공한다. */
    override fun onCreate() = true

    /** 외부 앱의 가짜 제어 객체 주입을 커널 호출 UID로 차단한다. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != 2000 || method != "attach") throw SecurityException("Shell only")
        bridge = extras?.getBinder("bridge")
        return Bundle.EMPTY
    }

    /** 조회 가능한 데이터는 제공하지 않는다. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    /** 파일 형식 조회는 지원하지 않는다. */
    override fun getType(uri: Uri): String? = null
    /** 데이터 추가는 지원하지 않는다. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    /** 데이터 삭제는 지원하지 않는다. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    /** 데이터 변경은 지원하지 않는다. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        @Volatile internal var bridge: IBinder? = null
        const val DESCRIPTOR = "com.wemade.teslamacro.NavigationControl.v2"

        /** 앱 프로세스 재생성을 감지한 셸의 전달을 제한된 시간만 기다린다. */
        suspend fun request() {
            if (bridge?.isBinderAlive == true) return
            repeat(30) { if (bridge?.isBinderAlive == true) return; delay(100) }
        }
    }
}

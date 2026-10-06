package com.wemade.teslamacro.data.nav

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** 셸이 전달한 Binder로만 내부 통신 파일 디스크립터를 받아 네트워크 포트를 열지 않는다. */
internal class NavigationChannel : Closeable {
    private lateinit var bridge: IBinder
    private var descriptor: ParcelFileDescriptor? = null
    private var reader: java.io.InputStream? = null
    private var writer: java.io.OutputStream? = null
    val input get() = requireNotNull(reader)
    val output get() = requireNotNull(writer)

    /** 공급자가 호출 UID를 확인한 살아 있는 Binder만 사용한다. */
    fun connect(): NavigationChannel {
        bridge = requireNotNull(NavigationBridgeProvider.bridge?.takeIf { it.isBinderAlive })
        return this
    }

    /** 시작할 때만 소유 채널을 만들고 나머지 제어는 그 채널에 보낸다. */
    fun send(command: String) {
        if (descriptor == null) {
            check(NavigatorApp.ofSafeDriveCommand(command) != null)
            transact(2) { descriptor = it.readParcelable(ParcelFileDescriptor::class.java.classLoader) }
            reader = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(descriptor))
            writer = ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(descriptor))
        }
        output.write("$command\n".toByteArray()); output.flush()
    }

    /** 상태 조회는 지도 실행 없이 준비된 프로세스만 확인한다. */
    fun status(): String = transact(1) { it.readString().orEmpty() }

    /** 업데이트 전 서버는 이 명령을 몰라 네이버 외 안심운전 앱을 실행하지 못하므로 재준비 대상으로 본다. */
    fun supportsSafeDriveApps(): Boolean = runCatching { transact(5) { it.readInt() == 1 } }.getOrDefault(false)

    /** 기능 지원만으로 구버전 서버를 재사용하지 않도록 실제 APK 빌드 버전을 조회한다. */
    fun serverVersionCode(): Int? = runCatching { transact(6) { it.readInt() } }.getOrNull()

    /** 구형 서버·잠금·다른 지도 세션은 명령을 보내기 전에 기존 실행 경로로 돌린다. */
    fun canLaunchDestination(packageName: String, uri: String): Boolean =
        transact(3, { it.writeString(packageName); it.writeString(uri) }) { it.readInt() == 1 }

    /** 목적지 실행은 가상 화면 세션과 분리해 채널 종료가 길안내를 종료하지 않게 한다. */
    fun launchDestination(packageName: String, uri: String): String =
        transact(4, { it.writeString(packageName); it.writeString(uri) }) { it.readString().orEmpty() }

    /** 같은 프로토콜의 고정 명령만 전달하고 오류는 호출자에게 반환한다. */
    private fun <T> transact(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NavigationBridgeProvider.DESCRIPTOR)
            write(data)
            check(bridge.transact(code, data, reply, 0))
            reply.readException()
            return read(reply)
        } finally { data.recycle(); reply.recycle() }
    }

    /** 소유 연결의 종료는 서버가 해당 지도만 정리하는 신호가 된다. */
    override fun close() { descriptor?.close(); descriptor = null; reader = null; writer = null }
}

package com.wemade.teslamacro.data.nav

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 라이브러리 페어링 소켓에 타임아웃이 없어 로컬 중계 연결을 제한 시간에 닫는다. */
internal object LocalAdbPairing {
    /** 연결 대상은 같은 휴대폰으로 고정하고 코드 입력 오류도 20초 안에 반환한다. */
    fun pair(manager: AbsAdbConnectionManager, port: Int, code: String): Boolean {
        val local = InetAddress.getByName("127.0.0.1")
        val listener = ServerSocket(0, 1, local)
        val target = Socket()
        val accepted = AtomicReference<Socket?>()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val closer = timer.schedule({
            runCatching { listener.close() }
            runCatching { accepted.get()?.close() }
            runCatching { target.close() }
        }, 20, TimeUnit.SECONDS)
        try {
            target.connect(InetSocketAddress(local, port), 3_000)
            target.soTimeout = 20_000
            thread(isDaemon = true, name = "local-adb-pair") {
                try {
                    val source = listener.accept().also { accepted.set(it); it.soTimeout = 20_000 }
                    thread(isDaemon = true, name = "local-adb-pair-reply") {
                        try { target.getInputStream().copyTo(source.getOutputStream()) }
                        catch (_: Exception) { }
                        finally { runCatching { source.close() } }
                    }
                    source.getInputStream().copyTo(target.getOutputStream())
                } catch (_: Exception) { }
                finally { runCatching { target.close() } }
            }
            return manager.pair("127.0.0.1", listener.localPort, code)
        } finally {
            closer.cancel(false)
            timer.shutdownNow()
            runCatching { listener.close() }
            runCatching { accepted.get()?.close() }
            runCatching { target.close() }
        }
    }
}

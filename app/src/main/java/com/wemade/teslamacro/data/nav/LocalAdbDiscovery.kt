package com.wemade.teslamacro.data.nav

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 발견된 주소가 이 휴대폰에 속할 때만 포트를 사용하며 연결 자체는 루프백으로 고정한다. */
internal object LocalAdbDiscovery {
    /** 광고 해석을 직렬화해 다른 기기가 먼저 발견돼도 로컬 광고를 버리지 않는다. */
    suspend fun port(context: Context): Int? = withTimeoutOrNull(10_000) {
        val manager = context.getSystemService(NsdManager::class.java)
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet()
        val found = Channel<NsdServiceInfo>(32)
        val listener = object : NsdManager.DiscoveryListener {
            /** 해석 전 광고는 작은 큐에 보관한다. */
            override fun onServiceFound(service: NsdServiceInfo) { found.trySend(service) }
            /** 이미 사라진 광고는 해석 실패로 처리된다. */
            override fun onServiceLost(service: NsdServiceInfo) { }
            /** 시작 알림은 포트 발견으로 간주하지 않는다. */
            override fun onDiscoveryStarted(type: String) { }
            /** 종료 뒤에는 새 광고를 받지 않는다. */
            override fun onDiscoveryStopped(type: String) { found.close() }
            /** 탐색 실패는 대기 중인 호출자에게 종료로 전달한다. */
            override fun onStartDiscoveryFailed(type: String, error: Int) { found.close() }
            /** 중복 종료 오류는 사용자 작업에 전파하지 않는다. */
            override fun onStopDiscoveryFailed(type: String, error: Int) { }
        }
        try {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            for (service in found) {
                val resolved = resolve(manager, service) ?: continue
                if (resolved.host?.hostAddress in addresses && resolved.port in 1..65535) return@withTimeoutOrNull resolved.port
            }
            null
        } finally {
            found.close()
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    /** 늦게 도착한 해석 결과는 취소된 요청으로 되돌리지 않는다. */
    private suspend fun resolve(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        manager.resolveService(service, object : NsdManager.ResolveListener {
            /** 해석 실패는 다음 광고로 넘어간다. */
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { if (continuation.isActive) continuation.resume(null) }
            /** 시스템이 확인한 주소와 포트만 반환한다. */
            override fun onServiceResolved(info: NsdServiceInfo) { if (continuation.isActive) continuation.resume(info) }
        })
    }
}

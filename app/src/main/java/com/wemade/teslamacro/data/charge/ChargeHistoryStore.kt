package com.wemade.teslamacro.data.charge

import android.content.Context
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 충전 전류 기록을 앱 파일 하나에 JSON으로 저장한다.
 *
 * 24시간 96칸이라 DB를 두지 않았다. 기간이 길어지거나 구간 조회가 필요해지면 그때 옮긴다.
 * [RuleStore][com.wemade.teslamacro.data.macro.RuleStore]와 같은 방식이다.
 */
class ChargeHistoryStore(context: Context) {

    private val file = File(context.filesDir, "charge_history.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _buckets = MutableStateFlow<List<ChargeBucket>>(emptyList())
    val buckets: StateFlow<List<ChargeBucket>> = _buckets.asStateFlow()

    /** 마지막으로 기록에 쓴 CHARGE 읽기 시각. 같은 읽기를 폴링마다 다시 세지 않는다 */
    private var lastReadAt = 0L

    /** 직전 표본의 시각과 값. 이 사이 구간을 그 값으로 채운다 */
    private var lastSampleAt = 0L
    private var lastAmps = 0
    private var lastWatts = 0

    private var lastPersistAt = 0L

    /** 앱 시작 시 1회. 파일이 없거나 깨졌으면 빈 기록으로 시작한다 */
    suspend fun load() = withContext(Dispatchers.IO) {
        _buckets.value = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString(bucketListSerializer, file.readText())
        }.getOrDefault(emptyList())
    }

    /**
     * 폴링 결과 하나를 기록한다.
     *
     * CHARGE를 이번 사이클에 실제로 읽었을 때만 센다 — 병합된 스냅샷은 옛 전류를 계속
     * 들고 있어서, 그대로 세면 읽지도 않은 값이 관측 시간만 부풀린다.
     *
     * 적산은 **직전 표본부터 지금까지**를 직전 전류로 채우는 방식이다. 지금 읽은 값은
     * 다음 표본이 올 때 채워진다 — 아직 얼마나 유지될지 모르기 때문이다.
     */
    suspend fun record(snapshot: VehicleSnapshot, nowMillis: Long) {
        val readAt = snapshot.categoryReadAt[StateCategory.CHARGE] ?: return
        if (readAt == lastReadAt) return
        lastReadAt = readAt

        // 충전 중이 아니면 0A. 빈 칸으로 두면 "안 읽었다"와 "안 했다"가 구분되지 않는다
        val charging = snapshot.isCharging == true
        // 설정값(chargingAmps)이 아니라 실제로 흐르는 전류를 먼저 쓴다. 옛 펌웨어라 없으면 설정값
        val amps = if (charging) {
            snapshot.actualChargingAmps ?: snapshot.chargingAmps ?: return
        } else 0
        val watts = if (charging) chargingWatts(amps, snapshot.chargerVoltage, snapshot.chargerPowerKw) else 0

        if (lastSampleAt > 0) {
            _buckets.value = ChargeHistory.accumulate(
                buckets = _buckets.value,
                fromMillis = lastSampleAt,
                toMillis = nowMillis,
                amps = lastAmps,
                watts = lastWatts,
            )
        }
        lastSampleAt = nowMillis
        lastAmps = amps
        lastWatts = watts

        // 폴링마다 파일을 쓰면 하룻밤에 수천 번이다. 1분에 한 번이면 재시작에도 거의 안 잃는다
        if (nowMillis - lastPersistAt < PERSIST_INTERVAL_MILLIS) return
        lastPersistAt = nowMillis
        persist(_buckets.value)
    }

    private suspend fun persist(buckets: List<ChargeBucket>) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(bucketListSerializer, buckets)) }
    }

    private companion object {
        /**
         * 전압을 읽었으면 전류×전압, 아니면 차량이 보고한 kW를 쓴다.
         *
         * charger_power는 kW 정수라 저전류에서 0으로 내려앉는다. 전압이 있을 때 굳이 쓰지 않는다.
         */
        fun chargingWatts(amps: Int, voltage: Int?, powerKw: Int?): Int = when {
            voltage != null && voltage > 0 -> amps * voltage
            powerKw != null -> powerKw * 1_000
            else -> 0
        }

        const val PERSIST_INTERVAL_MILLIS = 60_000L
        val bucketListSerializer = ListSerializer(ChargeBucket.serializer())
    }
}

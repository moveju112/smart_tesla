package com.wemade.teslamacro.data.safety

import android.app.Application
import android.location.Location
import android.util.AtomicFile
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.domain.safety.CameraDataset
import com.wemade.teslamacro.domain.safety.CameraIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.time.Duration.Companion.minutes

/**
 * 집에서 실주행을 대신하는 주행 재현. `tools/replay/make_route.py`로 만든 1초 GPS 경로를 실제 안내기에 넣고
 * 카메라 후보·음성 문구·경고음 구간을 초 단위 보고서로 남긴다.
 * 경로는 생활 동선일 수 있어 Git 제외 폴더(SAFETY_REPLAY_DIR)에만 두며, 없으면 테스트를 건너뛴다.
 * 매칭은 SAFETY_REPLAY_MATCH_URL(서버 터널)로 한 번 받은 응답을 같은 폴더 캐시에 저장해 다음부터는 오프라인으로 재생한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeDriveReplayTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    private data class Sample(val latitude: Double, val longitude: Double, val bearing: Float,
                              val speedMps: Float, val accuracy: Float)

    @Test fun replayRoutes() = runTest(timeout = 20.minutes) {
        val directory = System.getenv("SAFETY_REPLAY_DIR")?.let(::File)
        assumeTrue("SAFETY_REPLAY_DIR 없음 → 주행 재현 생략", directory?.isDirectory == true)
        val routes = directory!!.listFiles { file -> file.name.endsWith(".json") && file.name != CACHE_FILE }
            .orEmpty().sortedBy { it.name }
        assumeTrue("재현 경로 json 없음", routes.isNotEmpty())
        val dataset = Json { ignoreUnknownKeys = true }.decodeFromString(CameraDataset.serializer(),
            File(System.getenv("SAFETY_REPLAY_DATASET") ?: "src/main/assets/safety_cameras.json").readText())
        val cache = MatchCache(File(directory, CACHE_FILE), System.getenv("SAFETY_REPLAY_MATCH_URL"),
            System.getenv("SAFETY_REPLAY_TOKEN"))
        val summary = StringBuilder()
        val failures = mutableListOf<String>()
        try {
            for (route in routes) for (matching in listOf(true, false)) {
                val routeJson = Json.parseToJsonElement(route.readText()).jsonObject
                val mode = if (matching) "Matched" else "Offline"
                val expected = routeJson.cameraIds("expectedCameraIds") + routeJson.cameraIds("expected${mode}CameraIds")
                val excluded = routeJson.cameraIds("excludedCameraIds") + routeJson.cameraIds("excluded${mode}CameraIds")
                val stats = MatchStats()
                val report = replay(routeJson, CameraIndex(dataset.cameras),
                    cache.takeIf { matching }, stats, expected, excluded)
                val name = "report-${route.nameWithoutExtension}-${if (matching) "matched" else "offline"}.txt"
                File(directory, name).writeText(report.text)
                summary.append("${route.nameWithoutExtension} ${if (matching) "매칭" else "오프라인"}: ${report.summary}\n")
                failures += report.failures.map { "${route.nameWithoutExtension} $mode: $it" }
            }
        } finally {
            cache.save()
            File(directory, "summary.txt").writeText(summary.toString())
            println(summary)
        }
        org.junit.Assert.assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private data class ReplayResult(val text: String, val summary: String, val failures: List<String>)
    private class MatchStats(var cacheHits: Int = 0, var serverHits: Int = 0,
                             var unmatched: Int = 0, var failures: Int = 0, var cacheMisses: Int = 0)

    // 모드별 기대값이 지정된 경로만 oracle로 검사하며, 미지정 경로는 관찰 모드로 둔다.
    private fun JsonObject.cameraIds(name: String): Set<String> =
        this[name]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    // 경로 한 개를 1초씩 넣고 후보·음성·경고음 변화만 기록한다.
    private fun TestScope.replay(route: JsonObject, index: CameraIndex, cache: MatchCache?,
                                 stats: MatchStats, expected: Set<String>, excluded: Set<String>): ReplayResult {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val samples = route["samples"]!!.jsonArray.map {
            val sample = it.jsonObject
            Sample(sample.number("latitude"), sample.number("longitude"), sample.number("bearing").toFloat(),
                sample.number("speedMps").toFloat(), sample.number("accuracy").toFloat())
        }

        var second = 0
        val baseNanos = 100_000_000_000L
        val baseWallMillis = 1_900_000_000_000L
        val spoken = mutableListOf<Pair<Int, String>>()
        val guide = SafeDriveGuide(Application(), voiceOutput = { spoken += second to it },
            wallClockMillis = { baseWallMillis + second * 1_000L }) { baseNanos + second * 1_000_000_000L }
        field("index").set(guide, index)
        if (cache != null) field("roadMatcher").set(guide, replayMatcher(cache, stats))
        val lines = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val cameraBySecond = mutableMapOf<Int, String>()
        var lastCamera: String? = null
        var lastRoadName: String? = null
        var warningSeconds = 0
        var warningStarts = 0
        var wasWarning = false
        try {
            guide.setSound(true, 2, toleranceKph = 5)
            guide.setAlertOptions(500, voice = true)
            guide.start()
            runCurrent()
            guide.setAutomaticAlertsAllowed(true)
            guide.setRoadMatchEnabled(cache != null)
            for ((position, sample) in samples.withIndex()) {
                second = position
                guide.onLocation(Location("gps").apply {
                    latitude = sample.latitude; longitude = sample.longitude
                    bearing = sample.bearing; speed = sample.speedMps; accuracy = sample.accuracy
                    time = baseWallMillis + second * 1_000L
                    elapsedRealtimeNanos = baseNanos + second * 1_000_000_000L
                })
                // 매칭은 IO 스레드에서 끝나므로 결과가 다음 측위에 반영되도록 기다린다.
                while ((field("matchJob").get(guide) as Job?)?.isActive == true) {
                    Thread.sleep(2)
                    runCurrent()
                }
                advanceTimeBy(999)
                runCurrent()
                val alert = guide.state.value.alert
                @Suppress("UNCHECKED_CAST")
                val matched = (field("matchedRoad").get(guide) as Pair<Long, MatchedRoad>?)?.second
                // 매칭 도로명 변화도 남겨 카메라가 도로명 때문에 빠졌는지 보고서에서 바로 보이게 한다.
                if (cache != null && matched?.roadName != lastRoadName) {
                    lines += "%4ds 매칭 도로 %s → %s".format(second, lastRoadName ?: "-", matched?.roadName ?: "-")
                    lastRoadName = matched?.roadName
                }
                val camera = alert?.let { "${it.cameraId}(제한 ${it.speedLimitKph ?: "?"}, 카메라 도로 ${it.cameraRoadName ?: "-"})" }
                alert?.cameraId?.let { seen += it.split("|"); cameraBySecond[second] = it }
                if (camera != lastCamera) {
                    lines += "%4ds 후보 %s · 거리 %sm · 매칭 도로 %s · 위치 %.6f,%.6f".format(second,
                        camera ?: "없음", alert?.distanceMeters ?: "-", matched?.roadName ?: "-", sample.latitude, sample.longitude)
                    lastCamera = camera
                }
                val warning = (field("warningJob").get(guide) as Job?)?.isActive == true
                if (warning) warningSeconds++
                if (warning && !wasWarning) {
                    warningStarts++
                    lines += "%4ds 경고음 시작 (GPS %.0fkm/h, 제한 %s)".format(second, sample.speedMps * 3.6, alert?.speedLimitKph ?: "?")
                }
                if (!warning && wasWarning) lines += "%4ds 경고음 멈춤".format(second)
                wasWarning = warning
            }
        } finally {
            guide.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
        spoken.forEach { (at, text) -> lines += "%4ds 음성 \"%s\"".format(at, text) }
        lines.sortBy { it.trim().substringBefore("s ").toIntOrNull() ?: 0 }
        val limits = Regex("시속 (\\d+)킬로미터").findAll(spoken.joinToString(" ") { it.second })
            .groupingBy { it.groupValues[1] }.eachCount().toSortedMap()
        val missing = expected - seen
        val unexpected = excluded intersect seen
        val voiced = spoken.flatMap { cameraBySecond[it.first]?.split("|").orEmpty() }.toSet()
        val voicedExcluded = excluded intersect voiced
        val failures = mutableListOf<String>()
        if (missing.isNotEmpty()) failures += "필수 후보 누락: $missing"
        val missingVoice = expected - voiced
        if (missingVoice.isNotEmpty()) failures += "필수 카메라 음성 누락: $missingVoice"
        if (unexpected.isNotEmpty()) failures += "제외 후보 노출: $unexpected"
        if (voicedExcluded.isNotEmpty()) failures += "제외 후보 음성 노출: $voicedExcluded"
        if (cache != null && (expected.isNotEmpty() || excluded.isNotEmpty()) && stats.failures > 0)
            failures += "매칭 서버 실패 ${stats.failures}회"
        if (cache != null && stats.cacheMisses > 0 &&
            (expected.isNotEmpty() || excluded.isNotEmpty() ||
                System.getenv("SAFETY_REPLAY_REQUIRE_MATCH") == "true"))
            failures += "서버 없는 캐시 누락 ${stats.cacheMisses}회 (매칭 미실행)"
        if (cache != null && (expected.isNotEmpty() || excluded.isNotEmpty()) && stats.cacheHits + stats.serverHits == 0)
            failures += "확정 도로 매칭 응답 없음 (HTTP 200 불확실 응답은 확정 아님)"
        val matchingStatus = if (cache == null) "매칭 미사용" else
            "확정 매칭 응답 캐시 ${stats.cacheHits}회/서버 ${stats.serverHits}회, 불확실 ${stats.unmatched}회, " +
                "HTTP 실패 ${stats.failures}회, 캐시누락 ${stats.cacheMisses}회"
        val result = "${samples.size}초, 후보 ${seen.size}개, 음성 ${spoken.size}회 ${limits.map { "${it.key}:${it.value}" }}, " +
            "경고음 ${warningStarts}회/${warningSeconds}초, $matchingStatus"
        return ReplayResult((lines + "" + "요약: $result" + failures.map { "검증 실패: $it" }).joinToString("\n"),
            result, failures)
    }

    // 2. 인증은 가짜로 통과시키고 매칭만 캐시 또는 실제 서버로 보낸다.
    private fun replayMatcher(cache: MatchCache, stats: MatchStats): RoadMatcher {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = RoadDeviceIdentity(AtomicFile(File(temporary.root, "device.txt"))) { key }
        return RoadMatcher(Application(), bootstrapToken = "replay", identityOverride = identity) { path, body, _ ->
            when (path) {
                "/v1/devices" -> RoadHttpResponse(200, """{"certificate":"dc1.replay"}""")
                "/v1/session" -> RoadHttpResponse(200, """{"accessToken":"rm1.replay.token","expiresInSeconds":1800}""")
                else -> cache.match(body.toString(Charsets.UTF_8), stats)
            }
        }
    }

    /** 요청 좌표·상대 시각이 같으면 같은 응답을 재사용해 재실행을 서버 없이 결정적으로 만든다. */
    private class MatchCache(private val file: File, private val serverUrl: String?, private val token: String?) {
        private val entries: MutableMap<String, String> = if (file.isFile) {
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
        } else mutableMapOf()
        private var lastServerRequestNanos = 0L

        // 서버는 오래된 시각을 거절하므로 마지막 점을 현재 시각에 맞춰 옮겨 보낸다.
        fun match(body: String, stats: MatchStats): RoadHttpResponse {
            val points = Json.parseToJsonElement(body).jsonObject["points"]!!.jsonArray.map { it.jsonObject }
            val last = points.last()["timestamp"]!!.jsonPrimitive.long
            val cacheKey = points.joinToString(";") { point ->
                val coordinate = point["coordinate"]!!.jsonArray
                "${coordinate[0]},${coordinate[1]},${point["timestamp"]!!.jsonPrimitive.long - last},${point["accuracyMeters"]}"
            }
            entries[cacheKey]?.let {
                if (parseRoadMatch(it) != null) stats.cacheHits++ else stats.unmatched++
                return RoadHttpResponse(200, it)
            }
            if (serverUrl == null || token == null) {
                stats.cacheMisses++
                return RoadHttpResponse()
            }
            val now = System.currentTimeMillis() / 1_000
            val shifted = buildJsonObject {
                put("points", buildJsonArray {
                    points.forEach { point ->
                        add(buildJsonObject {
                            put("coordinate", point["coordinate"] as JsonArray)
                            put("timestamp", point["timestamp"]!!.jsonPrimitive.long - last + now)
                            put("accuracyMeters", point["accuracyMeters"] as JsonPrimitive)
                        })
                    }
                })
            }.toString().toByteArray()
            // 가상 1초 GPS가 실서버에 몰리지 않도록 캐시 미스 호출을 초당 8회 이하로 제한한다.
            val elapsed = System.nanoTime() - lastServerRequestNanos
            if (lastServerRequestNanos != 0L && elapsed < 125_000_000L)
                Thread.sleep((125_000_000L - elapsed + 999_999L) / 1_000_000L)
            lastServerRequestNanos = System.nanoTime()
            return try {
                val connection = URL("$serverUrl/v1/match").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(shifted) }
                    val code = connection.responseCode
                    if (code != 200) {
                        stats.failures++
                        RoadHttpResponse(code)
                    } else {
                        val response = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        entries[cacheKey] = response
                        if (parseRoadMatch(response) != null) stats.serverHits++ else stats.unmatched++
                        RoadHttpResponse(200, response)
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: java.io.IOException) {
                stats.failures++
                RoadHttpResponse()
            }
        }

        // 실패한 재현도 이미 받은 응답을 보존해 다음 실행이 같은 지도 근거를 재사용한다.
        fun save() {
            file.writeText(JsonObject(entries.mapValues { JsonPrimitive(it.value) }).toString())
        }
    }

    private fun JsonObject.number(name: String): Double = this[name]!!.jsonPrimitive.double

    private fun field(name: String) = SafeDriveGuide::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val CACHE_FILE = "match-cache.json"
    }
}

package com.wemade.teslamacro.data.poll

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.macro.RuleStore
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.EnrollmentState
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.gateway.VehicleGateway
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.GeoPoint
import com.wemade.teslamacro.domain.macro.ForecastMetric
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.MacroRunner
import com.wemade.teslamacro.domain.macro.Reading
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Signal
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 기존 Android 테스트 환경으로 실제 폴러의 확인·종료·중복 방지 흐름을 가상 시간에 검증한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortableBoardingPollTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `미착석과 UNKNOWN 다음 착석을 추가 조회 없이 한번 전달하고 연결을 놓는다`() = runTest {
        val fixture = fixture()
        fixture.gateway.onRead = { count ->
            fixture.snapshot(when (count) { 1 -> false; 2 -> null; else -> true })
        }
        fixture.start()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, fixture.boardings)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        assertEquals(0, fixture.locationReads)
        assertEquals(0, fixture.forecastReads)
        assertTrue(fixture.gateway.reads.all { it == setOf(StateCategory.BODY_CONTROLLER) })
        val readsAfterBoarding = fixture.gateway.reads.size
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(1, fixture.boardings)
        assertEquals(readsAfterBoarding, fixture.gateway.reads.size)
        fixture.poller.stop()
    }

    @Test
    fun `빈 차는 60초에 연결을 놓고 같은 전원에서 다시 시작하지 않는다`() = runTest {
        val fixture = fixture()
        fixture.start()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        assertEquals(0, fixture.boardings)
        assertEquals(1, fixture.gateway.connections)
        assertTrue(fixture.gateway.reads.size in 20..32)
        val readsAtDeadline = fixture.gateway.reads.size
        fixture.poller.setVehiclePowerConnected(true)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(readsAtDeadline, fixture.gateway.reads.size)
        assertEquals(1, fixture.gateway.connections)
        fixture.poller.stop()
    }

    @Test
    fun `일시 연결 실패와 읽기 실패를 지나 착석을 확인한다`() = runTest {
        val fixture = fixture()
        fixture.gateway.onConnect = { count ->
            if (count == 1) Result.failure(IllegalStateException("일시 연결 실패"))
            else Result.success(Unit)
        }
        fixture.gateway.onRead = { count ->
            if (count == 1) Result.failure(IllegalStateException("일시 읽기 실패"))
            else fixture.snapshot(true)
        }
        fixture.start()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(2, fixture.gateway.connections)
        assertEquals(1, fixture.boardings)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    @Test
    fun `취소를 실패 Result로 바꾸는 느린 연결도 총 60초와 두번을 넘지 않는다`() = runTest {
        val fixture = fixture()
        fixture.gateway.onConnect = {
            runCatching { delay(45_000) }
        }
        fixture.start()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, fixture.gateway.connections)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        assertEquals(0, fixture.gateway.reads.size)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, fixture.gateway.connections)
        fixture.poller.stop()
    }

    @Test
    fun `전원 재연결 뒤 이전 요청의 늦은 착석 응답을 버리고 새 응답을 기다린다`() = runTest {
        val fixture = fixture()
        val newPresence = CompletableDeferred<Unit>()
        fixture.gateway.onRead = { count ->
            if (count == 1) delay(5_000)
            if (count > 2) newPresence.await()
            fixture.snapshot(count != 2)
        }
        fixture.start()
        advanceTimeBy(1_000)
        runCurrent()
        fixture.poller.setVehiclePowerConnected(false)
        fixture.poller.enforceConnectionGuard()
        fixture.poller.setVehiclePowerConnected(true)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(0, fixture.boardings)
        newPresence.complete(Unit)
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(1, fixture.boardings)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    @Test
    fun `수동 해제 중 들어온 착석 응답은 자동 실행하지 않는다`() = runTest {
        val fixture = fixture()
        fixture.gateway.onRead = {
            delay(5_000)
            fixture.snapshot(true)
        }
        fixture.start()
        advanceTimeBy(1_000)
        runCurrent()
        fixture.poller.disconnectUntilNextUse()
        advanceTimeBy(90_000)
        runCurrent()

        assertEquals(0, fixture.boardings)
        assertEquals(1, fixture.gateway.connections)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    @Test
    fun `앱 화면과 직접 명령은 안심운전 전용 제한 없이 기존 조회를 유지한다`() = runTest {
        val fixture = fixture()
        fixture.poller.setAppVisible(true)
        fixture.poller.beginCommandConnection()
        fixture.start()
        runCurrent()

        assertTrue(fixture.gateway.reads.first().contains(StateCategory.CLIMATE))
        assertEquals(1, fixture.locationReads)
        assertEquals(LinkState.Ready, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    @Test
    fun `전면의 미착석 응답 뒤 잠가도 남은 확인 창으로 착석을 잡는다`() = runTest {
        val fixture = fixture()
        var seated = false
        fixture.gateway.onRead = { fixture.snapshot(seated) }
        fixture.poller.setAppVisible(true)
        fixture.start()
        assertEquals(0, fixture.boardings)
        assertEquals(LinkState.Ready, fixture.gateway.linkState.value)

        fixture.poller.setAppVisible(false)
        seated = true
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, fixture.boardings)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        assertEquals(setOf(StateCategory.BODY_CONTROLLER), fixture.gateway.reads.last())
        fixture.poller.stop()
    }

    /** 거치 전원 상승이 착석보다 빨라도 앱을 열지 않고 탑승을 잡으며 기존 조회를 유지한다. */
    @Test
    fun `거치는 미착석과 UNKNOWN 뒤 착석을 한번 전달하고 연결을 유지한다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED)
        fixture.gateway.onRead = { count ->
            fixture.snapshot(when (count) { 1 -> false; 2 -> null; else -> true })
        }
        fixture.start()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, fixture.boardings)
        assertEquals(LinkState.Ready, fixture.gateway.linkState.value)
        assertTrue(fixture.gateway.reads.first().contains(StateCategory.CLIMATE))
        assertTrue(fixture.locationReads > 0)
        assertTrue(fixture.forecastReads > 0)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(1, fixture.boardings)
        assertEquals(LinkState.Ready, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    /** 빈 거치 차량의 인증 연결을 확인 창 안으로 제한하고 같은 전원으로 재시작하지 않는다. */
    @Test
    fun `거치 미탑승은 60초에 종료하고 같은 전원에서 다시 확인하지 않는다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED)
        fixture.start()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        assertEquals(0, fixture.boardings)
        assertTrue(fixture.gateway.reads.size in 20..32)
        val readsAtDeadline = fixture.gateway.reads.size
        fixture.poller.setVehiclePowerConnected(true)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(readsAtDeadline, fixture.gateway.reads.size)
        assertEquals(1, fixture.gateway.connections)
        fixture.poller.stop()
    }

    /** 전원을 끊기 전에 시작한 조회가 뒤늦게 성공해도 자동 안내를 열지 않는다. */
    @Test
    fun `거치 확인 중 전원이 끊기면 늦은 착석 응답을 버린다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED)
        fixture.gateway.onRead = {
            delay(5_000)
            fixture.snapshot(true)
        }
        fixture.start()
        advanceTimeBy(1_000)
        runCurrent()
        fixture.poller.setVehiclePowerConnected(false, endAppSession = true)
        fixture.poller.enforceConnectionGuard()
        advanceTimeBy(90_000)
        runCurrent()

        assertEquals(0, fixture.boardings)
        assertEquals(1, fixture.gateway.connections)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    /** 사용자가 연결을 중단한 뒤의 늦은 응답으로 자동 안내가 되살아나지 않게 한다. */
    @Test
    fun `거치 확인 중 수동 해제하면 늦은 착석 응답을 버린다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED)
        fixture.gateway.onRead = {
            delay(5_000)
            fixture.snapshot(true)
        }
        fixture.start()
        advanceTimeBy(1_000)
        runCurrent()
        fixture.poller.disconnectUntilNextUse()
        advanceTimeBy(90_000)
        runCurrent()

        assertEquals(0, fixture.boardings)
        assertEquals(1, fixture.gateway.connections)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    /** 자동 안내를 끈 거치는 기존처럼 첫 미착석 확인만으로 연결 사유를 끝낸다. */
    @Test
    fun `거치 자동 안내가 꺼져 있으면 첫 미착석 뒤 추가 조회하지 않는다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED, autoStart = false)
        fixture.start()
        advanceTimeBy(121_000)
        runCurrent()

        assertEquals(1, fixture.gateway.reads.size)
        assertEquals(0, fixture.boardings)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    /** 차량 응답 뒤 위치 조회가 지연돼도 확인 시간 종료 후 탑승으로 처리하지 않는다. */
    @Test
    fun `거치 위치 조회가 60초를 넘으면 안내 없이 연결을 종료한다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED, locationDelayMillis = 90_000L)
        fixture.gateway.onRead = { fixture.snapshot(true) }
        fixture.start()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, fixture.locationReads)
        assertEquals(0, fixture.boardings)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        val readsAtDeadline = fixture.gateway.reads.size
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(0, fixture.boardings)
        assertEquals(readsAtDeadline, fixture.gateway.reads.size)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    /** 위치를 기다리는 동안 전원이 끊기면 앞서 읽은 착석값도 자동 실행에 쓰지 않는다. */
    @Test
    fun `거치 위치 조회 중 전원이 끊기면 늦은 안내를 버린다`() = runTest {
        val fixture = fixture(mode = DeviceMode.MOUNTED, locationDelayMillis = 5_000L)
        fixture.gateway.onRead = { fixture.snapshot(true) }
        fixture.start()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fixture.locationReads)
        fixture.poller.setVehiclePowerConnected(false, endAppSession = true)
        fixture.poller.enforceConnectionGuard()
        advanceTimeBy(90_000)
        runCurrent()

        assertEquals(0, fixture.boardings)
        assertEquals(1, fixture.gateway.connections)
        assertEquals(LinkState.Idle, fixture.gateway.linkState.value)
        fixture.poller.stop()
    }

    /** 저장 상태를 초기화하고 휴대 기본값을 유지한 채 거치 모드도 같은 폴러로 검증한다. */
    private suspend fun TestScope.fixture(
        mode: DeviceMode = DeviceMode.PORTABLE,
        autoStart: Boolean = true,
        locationDelayMillis: Long = 0L,
    ): Fixture {
        val context = object : ContextWrapper(paparazzi.context) {
            /** 매크로 파일도 테스트 종료 때 함께 지워지도록 임시 폴더로 격리한다. */
            override fun getFilesDir(): File = temporaryFolder.root
        }
        val settings = SettingsStore(
            context,
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
            ),
        )
        settings.setVin("5YJS0000000000000")
        settings.setEnrolled(true)
        settings.setDeviceMode(mode)
        settings.setAutoStartNavigatorSafeDrive(autoStart)
        settings.savePresence(false)
        val rules = RuleStore(context)
        rules.upsert(MacroRule(
            id = "portable-poll-test",
            name = "조회 지연 회귀",
            triggers = listOf(Trigger.Manual),
            conditions = listOf(
                Condition.NearLocation(),
                Condition.InRange(Signal.INSIDE_TEMP, gte = 20.0),
                Condition.ForecastInRange(ForecastMetric.MAX_TEMP, gte = 20.0),
            ),
            actions = listOf(ActionStep.Run(VehicleCommand.ClimateOn)),
        ))
        return Fixture(this, settings, rules, locationDelayMillis)
    }

    /** 실제 StatePoller에 저장소와 가짜 BLE만 연결하며 시간을 코루틴 스케줄러와 맞춘다. */
    private class Fixture(
        val scope: TestScope,
        settings: SettingsStore,
        rules: RuleStore,
        locationDelayMillis: Long,
    ) {
        val gateway = TestGateway()
        var boardings = 0
        var locationReads = 0
        var forecastReads = 0
        private val epoch = System.currentTimeMillis()
        private val reading = MutableStateFlow<Reading?>(null)
        val poller = StatePoller(
            gateway = gateway,
            ruleStore = rules,
            settingsStore = settings,
            runner = MacroRunner(gateway, scope.backgroundScope, reading),
            latestReading = reading,
            now = { epoch + scope.testScheduler.currentTime },
            locationReader = {
                locationReads++
                delay(locationDelayMillis)
                GeoPoint(0.0, 0.0)
            },
            forecastReader = { _, _ -> forecastReads++; null },
        )

        init {
            gateway.onRead = { snapshot(false) }
        }

        /** VCSEC 소유권까지 포함해 옛 스냅샷이 아닌 새 차량 응답을 만든다. */
        fun snapshot(present: Boolean?): Result<VehicleSnapshot> = Result.success(
            VehicleSnapshot(
                timestampMillis = epoch + scope.testScheduler.currentTime,
                isUserPresent = present,
                isLocked = true,
                categoryReadAt = mapOf(StateCategory.BODY_CONTROLLER to epoch + scope.testScheduler.currentTime),
            )
        )

        /** 이벤트 수신을 먼저 연결해 폴러가 보낸 탑승 횟수를 그대로 센다. */
        fun start() {
            scope.backgroundScope.launch { poller.boardingEvents.collect { boardings++ } }
            poller.setVehiclePowerConnected(true)
            poller.start(scope.backgroundScope)
            scope.runCurrent()
        }
    }

    /** 연결·조회 지연과 실패만 바꾸고 실제 폴러의 연결 정책은 그대로 실행한다. */
    private class TestGateway : VehicleGateway {
        override val linkState = MutableStateFlow<LinkState>(LinkState.Idle)
        override val enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.Enrolled)
        var connections = 0
        val reads = mutableListOf<Set<StateCategory>>()
        var onConnect: suspend (Int) -> Result<Unit> = { Result.success(Unit) }
        var onRead: suspend (Int) -> Result<VehicleSnapshot> = { Result.success(VehicleSnapshot.Empty) }

        /** 실패도 링크 상태에 반영해 재연결 분기를 실제처럼 태운다. */
        override suspend fun connect(vin: String, allowProbe: Boolean): Result<Unit> {
            connections++
            linkState.value = LinkState.Scanning
            return onConnect(connections).also {
                linkState.value = if (it.isSuccess) LinkState.Ready else LinkState.Failed("테스트 실패")
            }
        }

        /** 연결 해제를 관측 가능한 상태로 남긴다. */
        override suspend fun disconnect() { linkState.value = LinkState.Idle }

        /** 이 테스트는 등록 절차를 실행하지 않는다. */
        override suspend fun requestKeyEnrollment() = Result.success(Unit)

        /** 직접 차량 명령 대신 조회·연결 정책만 검증한다. */
        override suspend fun send(command: VehicleCommand) = Result.success(Unit)

        /** 단일 조회도 같은 응답 제어를 사용한다. */
        override suspend fun read(category: StateCategory) = readBundle(setOf(category))

        /** 실제 요청 카테고리를 기록해 VCSEC 외 조회가 추가되면 실패하게 한다. */
        override suspend fun readBundle(categories: Set<StateCategory>): Result<VehicleSnapshot> {
            reads += categories
            return onRead(reads.size)
        }
    }
}

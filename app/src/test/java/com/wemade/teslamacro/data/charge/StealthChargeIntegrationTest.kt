package com.wemade.teslamacro.data.charge

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.data.macro.RuleStore
import com.wemade.teslamacro.data.poll.StatePoller
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.data.settings.SettingsStore
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.gateway.*
import com.wemade.teslamacro.domain.macro.MacroRunner
import com.wemade.teslamacro.domain.macro.Reading
import com.wemade.teslamacro.domain.model.StateCategory
import com.wemade.teslamacro.domain.model.VehicleSnapshot
import com.wemade.teslamacro.feature.settings.stealthSettingsSummary
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 실제 폴러·컨트롤러·설정 저장소로 미확인 중단과 원래 전류 보존을 검사한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class StealthChargeIntegrationTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    // 부분 응답과 CHARGE의 UNKNOWN 모두 조절을 멈추고 새 충전 확인 뒤 원래 기준으로 재개한다.
    @Test fun lostChargeStatePausesAndRecoveryPreservesOriginalCurrent() = runTest {
        val fixture = fixture()
        fixture.settings.setStealthMaxAmps(12)
        fixture.settings.setStealthCharging(true)
        fixture.poller.start(backgroundScope)
        fixture.controller.start(backgroundScope)
        withTimeout(30_000) { fixture.gateway.commands.first { it.isNotEmpty() } }
        for (categoryReceived in listOf(false, true)) {
            val before = fixture.gateway.commands.value.size
            fixture.gateway.chargeKnown = false
            fixture.gateway.chargeCategoryReceived = categoryReceived
            fixture.poller.nudge()
            advanceTimeBy(310_000)
            runCurrent()
            assertEquals(before, fixture.gateway.commands.value.size)
            assertNull(fixture.poller.snapshot.value.isCharging)
            assertFalse(fixture.controller.runtime.value.running)
            val settings = fixture.settings.settings.first()
            assertTrue(settings.stealthCharging)
            assertEquals(16, settings.stealthChargeOriginalAmps)
            fixture.gateway.chargeKnown = true
            fixture.gateway.chargeCategoryReceived = true
            fixture.poller.nudge()
            withTimeout(30_000) { fixture.gateway.commands.first { it.size > before } }
            assertEquals(16, fixture.settings.settings.first().stealthChargeOriginalAmps)
        }
        fixture.settings.setStealthCharging(false)
        withTimeout(30_000) { fixture.settings.settings.first { !it.stealthChargeStarted } }
        assertEquals(VehicleCommand.SetChargingAmps(16), fixture.gateway.commands.value.last())
        assertFalse(fixture.settings.settings.first().stealthChargeRestorePending)
    }

    // 원복이 실패하면 값과 실패 상태를 보존하고 다음 서비스 시작에서 실제 원복 성공 후 해제한다.
    @Test fun restoreFailureRemainsVisibleUntilRestoreSucceeds() = runTest {
        val fixture = fixture()
        fixture.settings.beginStealthCharge(16)
        fixture.settings.setStealthChargeModified(true)
        fixture.settings.setStealthCharging(false)
        fixture.gateway.failCommands = true
        fixture.poller.start(backgroundScope)
        withTimeout(30_000) { fixture.poller.snapshot.first { it.isCharging == true } }
        fixture.controller.start(backgroundScope)
        val pending = withTimeout(30_000) { fixture.settings.settings.first { it.stealthChargeRestorePending } }
        assertEquals(3, fixture.gateway.commands.value.size)
        assertEquals(16, pending.stealthChargeOriginalAmps)
        assertTrue(stealthSettingsSummary(pending).contains("복구 실패"))
        fixture.controller.stop()
        runCurrent()
        fixture.gateway.failCommands = false
        fixture.controller.start(backgroundScope)
        val restored = withTimeout(30_000) { fixture.settings.settings.first { !it.stealthChargeRestorePending } }
        assertNull(restored.stealthChargeOriginalAmps)
        assertFalse(stealthSettingsSummary(restored).contains("복구 실패"))
        assertEquals(VehicleCommand.SetChargingAmps(16), fixture.gateway.commands.value.last())
    }

    // 기존 Android 단위 테스트 환경에 가짜 차량만 연결한다.
    private suspend fun TestScope.fixture(): Fixture {
        val context = object : ContextWrapper(paparazzi.context) {
            // 파일 생성은 이 테스트의 임시 디렉터리로 한정한다.
            override fun getFilesDir(): File = temporary.root
        }
        val settings = SettingsStore(context, PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temporary.root, "settings.preferences_pb")
        })
        settings.setVin("5YJS0000000000000")
        settings.setEnrolled(true)
        settings.setDeviceMode(DeviceMode.MOUNTED)
        settings.setProtectPhoneKey(false)
        return Fixture(this, settings, RuleStore(context))
    }

    private class Fixture(scope: TestScope, val settings: SettingsStore, rules: RuleStore) {
        val gateway = TestGateway { 1_700_000_000_000L + scope.testScheduler.currentTime }
        val reading = MutableStateFlow<Reading?>(null)
        val runner = MacroRunner(gateway, scope.backgroundScope, reading)
        val poller = StatePoller(gateway, rules, settings, runner, reading,
            now = { 1_700_000_000_000L + scope.testScheduler.currentTime },
            presenceClock = { scope.testScheduler.currentTime })
        val controller = StealthChargeController(gateway, poller, settings)
    }

    private class TestGateway(val now: () -> Long) : VehicleGateway {
        override val linkState = MutableStateFlow<LinkState>(LinkState.Idle)
        override val enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.Enrolled)
        val commands = MutableStateFlow<List<VehicleCommand>>(emptyList())
        var chargeKnown = true
        var chargeCategoryReceived = true
        var failCommands = false

        // 실제 차량에 연결하지 않는다.
        override suspend fun connect(vin: String, allowProbe: Boolean): Result<Unit> {
            linkState.value = LinkState.Ready
            return Result.success(Unit)
        }
        // 가짜 연결 해제를 폴러에 전달한다.
        override suspend fun disconnect() { linkState.value = LinkState.Idle }
        // 실제 키 등록은 실행하지 않는다.
        override suspend fun requestKeyEnrollment() = Result.success(Unit)
        // 실제 명령 대신 전송 횟수와 전류를 기록한다.
        override suspend fun send(command: VehicleCommand): Result<Unit> {
            commands.value += command
            return if (failCommands) Result.failure(IllegalStateException("가짜 차량 거부")) else Result.success(Unit)
        }
        // 단일 조회도 같은 가짜 응답을 사용한다.
        override suspend fun read(category: StateCategory) = readBundle(setOf(category))
        // 차체는 살아 있고 CHARGE만 실패하거나 값이 UNKNOWN인 상황을 만든다.
        override suspend fun readBundle(categories: Set<StateCategory>): Result<VehicleSnapshot> {
            val read = categories.filter { chargeCategoryReceived || it != StateCategory.CHARGE }
            return Result.success(VehicleSnapshot(timestampMillis = now(), isUserPresent = true,
                isCharging = true.takeIf { chargeKnown }, chargingAmps = 16.takeIf { chargeKnown },
                maxChargingAmps = 16.takeIf { chargeKnown }, categoryReadAt = read.associateWith { now() }))
        }
    }
}

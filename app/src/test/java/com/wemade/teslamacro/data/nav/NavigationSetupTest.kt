package com.wemade.teslamacro.data.nav

import org.junit.Assert.*
import org.junit.Test

class NavigationSetupTest {
    private val ready = NavigationSetup.State(true, true, true, true, true)

    /** 개발자 옵션·USB가 꺼져 있으면 저장된 인증으로 무반응 대기를 시작하지 않는다. */
    @Test fun protectedSettingsPrecedeRecovery() {
        assertEquals(NavigationSetup.Step.DEVELOPER, ready.copy(developer = false, usb = false).nextStep(true))
        assertEquals(NavigationSetup.Step.USB, ready.copy(usb = false).nextStep(true))
    }

    /** 이미 준비된 인증은 Wi-Fi나 알림 권한을 다시 요구하지 않고 먼저 확인한다. */
    @Test fun savedConnectionWorksBeforeWifiAndNotificationRequirements() {
        assertEquals(NavigationSetup.Step.CONNECT, ready.copy(wifi = false, notifications = false).nextStep(true))
    }

    /** 인증 복구 실패 뒤에는 부족한 조건부터 열고 앱·채널 차단을 구분한다. */
    @Test fun failedRecoveryFallsBackToSpecificSettings() {
        val blocked = ready.copy(wifi = false, notifications = false, channel = false)
        assertEquals(NavigationSetup.Step.WIFI, blocked.nextStep(false))
        assertEquals(NavigationSetup.Step.NOTIFICATIONS, blocked.copy(wifi = true).nextStep(false))
        assertEquals(NavigationSetup.Step.CHANNEL, blocked.copy(wifi = true, notifications = true).nextStep(false))
        assertEquals(NavigationSetup.Step.PAIR, ready.nextStep(false))
    }

    /** 같은 권한을 거부하거나 무관한 설정을 바꾸고 돌아오면 화면을 다시 열지 않는다. */
    @Test fun unchangedReturnDoesNotLoop() {
        assertFalse(ready.copy(notifications = false).completed(NavigationSetup.Step.NOTIFICATIONS))
        assertFalse(ready.copy(usb = false).completed(NavigationSetup.Step.USB))
        assertTrue(ready.completed(NavigationSetup.Step.NOTIFICATIONS))
        assertFalse(ready.completed(NavigationSetup.Step.PAIR))
        assertFalse(ready.completed(null))
    }
}

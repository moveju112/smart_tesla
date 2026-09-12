package com.wemade.teslamacro

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import app.cash.paparazzi.Paparazzi
import com.wemade.teslamacro.ui.component.openTrustedDeviceSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 제조사 설정 화면 유무에 따른 공개 설정 경로를 검증한다. */
class TrustedDeviceSettingsTest {
    @get:Rule val paparazzi = Paparazzi()

    /** 보안 설정을 지원하면 다른 설정 화면을 중복 실행하지 않는다. */
    @Test fun `보안 설정으로 이동한다`() {
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(paparazzi.context) {
            override fun startActivity(intent: Intent) { actions += intent.action }
        }
        openTrustedDeviceSettings(context)
        assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), actions)
    }

    /** 보안 설정이 없는 기기에서도 기본 설정 화면으로 이동한다. */
    @Test fun `보안 설정 미지원이면 기본 설정으로 대체한다`() {
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(paparazzi.context) {
            override fun startActivity(intent: Intent) {
                actions += intent.action
                if (intent.action == Settings.ACTION_SECURITY_SETTINGS) throw ActivityNotFoundException()
            }
        }
        openTrustedDeviceSettings(context)
        assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS), actions)
    }
}

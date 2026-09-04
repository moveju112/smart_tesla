package com.wemade.teslamacro.data.backup

import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.domain.command.VehicleCommand
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.Condition
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.domain.model.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 백업 파일 왕복 검증.
 *
 * 여기서 제일 중요한 건 **담기지 않아야 할 것이 안 담기는 것**이다 —
 * 이 파일은 메일로도 클라우드로도 나갈 수 있어서, VIN이나 BLE 주소가 새면
 * 백업이 아니라 유출이 된다.
 */
class BackupFileTest {

    private val rule = MacroRule(
        id = "test-1",
        name = "여름 탑승 쿨링",
        triggers = listOf(Trigger.SignalBecomes(Signal.DOOR_DRIVER_FRONT, to = true)),
        conditions = listOf(Condition.InRange(Signal.INSIDE_TEMP, gte = 27.0)),
        actions = listOf(ActionStep.Run(VehicleCommand.ClimateOn)),
    )

    @Test
    fun `매크로와 설정이 왕복해도 그대로다`() {
        val original = BackupFile(
            createdAtMillis = 1_700_000_000_000L,
            appVersion = "0.9.1",
            macros = listOf(rule),
            settings = BackupSettings(automationEnabled = false),
        )
        val text = BackupFile.json.encodeToString(BackupFile.serializer(), original)
        val restored = BackupFile.json.decodeFromString(BackupFile.serializer(), text)

        assertEquals(original, restored)
        assertEquals("여름 탑승 쿨링", restored.macros.single().name)
        assertFalse(restored.settings.automationEnabled)
    }

    /** 차를 특정하거나 여는 정보는 파일에 한 글자도 없어야 한다 */
    @Test
    fun `차량 식별자는 백업에 담기지 않는다`() {
        val settings = AppSettings(
            vin = "5YJS0000000000000",
            vehicleAddress = "AA:BB:CC:DD:EE:FF",
            vehicleName = "내 차",
            isEnrolled = true,
        )
        val text = BackupFile.json.encodeToString(
            BackupFile.serializer(),
            BackupFile(macros = emptyList(), settings = settings.toBackup()),
        )

        assertFalse(text.contains("5YJS0000000000000"))
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(text.contains("내 차"))
        assertFalse(text.contains("isEnrolled"))
        // 담기로 한 취향은 제대로 들어간다
        assertTrue(text.contains("automationEnabled"))
    }

    /** 앱이 새 필드를 추가해도 옛 파일이 열려야 한다 */
    @Test
    fun `모르는 필드가 있어도 읽는다`() {
        val text = """{"version":1,"macros":[],"settings":{"idlePollSeconds":45},"미래필드":true}"""
        val restored = BackupFile.json.decodeFromString(BackupFile.serializer(), text)
        assertTrue(restored.settings.automationEnabled)
    }
}

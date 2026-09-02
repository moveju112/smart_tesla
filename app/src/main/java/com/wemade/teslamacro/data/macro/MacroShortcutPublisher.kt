package com.wemade.teslamacro.data.macro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.wemade.teslamacro.MainActivity
import com.wemade.teslamacro.R
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.domain.macro.Trigger
import com.wemade.teslamacro.service.QuickActionActivity
import com.wemade.teslable.DiagLog

/** 저장된 매크로를 런처·빅스비 루틴이 읽는 동적 바로가기로 발행한다. */
class MacroShortcutPublisher(context: Context) {

    private val appContext = context.applicationContext

    /** 정적 바로가기와 시스템 상한을 침범하지 않는 범위에서 매크로 바로가기를 갱신한다. */
    fun publish(rules: List<MacroRule>) {
        runCatching {
            val manager = appContext.getSystemService(ShortcutManager::class.java)
            val available = (manager.maxShortcutCountPerActivity - manager.manifestShortcuts.size)
                .coerceAtLeast(0)
            val selected = selectMacroShortcuts(rules, available)
            val shortcuts = selected.mapIndexed { rank, rule -> shortcut(rule, rank) }

            check(manager.setDynamicShortcuts(shortcuts)) { "시스템이 바로가기 갱신을 거부함" }
            DiagLog.add(
                "빅스비 바로가기 갱신 — " +
                    if (selected.isEmpty()) "노출 가능 슬롯 없음"
                    else selected.joinToString { it.name }
            )
        }.onFailure { error ->
            // 바로가기 실패가 차량 연결과 매크로 실행까지 막아서는 안 된다.
            DiagLog.add("빅스비 바로가기 갱신 실패: ${error.message}")
        }
    }

    /** 매크로 id를 숨은 실행 화면에 전달하는 시스템 바로가기를 만든다. */
    private fun shortcut(rule: MacroRule, rank: Int): ShortcutInfo {
        val data = Uri.Builder()
            .scheme("teslamacro")
            .authority("run")
            .appendQueryParameter(QuickActionActivity.QUERY_MACRO_ID, rule.id)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, data, appContext, QuickActionActivity::class.java)
            .putExtra(QuickActionActivity.EXTRA_MACRO_ID, rule.id)

        return ShortcutInfo.Builder(appContext, "$SHORTCUT_PREFIX${rule.id}")
            .setShortLabel(rule.name.trim().take(SHORT_LABEL_LIMIT))
            .setLongLabel("${rule.name} 매크로 실행")
            .setIcon(Icon.createWithResource(appContext, R.drawable.ic_launcher_foreground))
            .setIntent(intent)
            .setActivity(ComponentName(appContext, MainActivity::class.java))
            .setRank(rank)
            .build()
    }

    private companion object {
        const val SHORTCUT_PREFIX = "macro-"
        const val SHORT_LABEL_LIMIT = 20
    }
}

/** 슬롯이 적어도 애프터블로우와 수동 매크로가 먼저 보이도록 순서를 정한다. */
internal fun selectMacroShortcuts(rules: List<MacroRule>, limit: Int): List<MacroRule> = rules
    .asSequence()
    .filter { it.name.isNotBlank() && it.actions.isNotEmpty() }
    .distinctBy { it.id }
    .sortedWith(
        compareBy<MacroRule> { it.id != AFTER_BLOW_PRESET_ID }
            .thenBy { rule -> rule.triggers.none { it is Trigger.Manual } }
            .thenBy { it.id.startsWith("preset-") }
            .thenByDescending { it.enabled }
            .thenBy { it.name }
    )
    .take(limit.coerceAtLeast(0))
    .toList()

private const val AFTER_BLOW_PRESET_ID = "preset-after-blow"

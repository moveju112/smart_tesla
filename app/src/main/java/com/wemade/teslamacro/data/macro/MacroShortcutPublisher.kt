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
import com.wemade.teslamacro.service.QuickActionAccessStore
import com.wemade.teslable.DiagLog

/** 저장된 매크로를 런처·빅스비 루틴이 읽는 동적 바로가기로 발행한다. */
class MacroShortcutPublisher(context: Context, private val access: QuickActionAccessStore) {

    private val appContext = context.applicationContext

    /** 기본 동작과 매크로를 시스템 상한 안에서 서명된 바로가기로 갱신한다. */
    fun publish(rules: List<MacroRule>) {
        runCatching {
            val manager = appContext.getSystemService(ShortcutManager::class.java)
            val builtIns = listOf(
                actionShortcut("open_frunk", R.string.shortcut_frunk_short, 0),
                actionShortcut("open_trunk", R.string.shortcut_trunk_short, 1),
                actionShortcut("vent_windows", R.string.shortcut_vent_short, 2),
            ).take(manager.maxShortcutCountPerActivity)
            val available = (manager.maxShortcutCountPerActivity - builtIns.size)
                .coerceAtLeast(0)
            val selected = selectMacroShortcuts(rules, available)
            val shortcuts = selected.mapIndexed { rank, rule -> shortcut(rule, rank) }

            // 정적 XML에는 실행 시점 인증을 넣을 수 없어 같은 동작을 서명된 동적 바로가기로 발행한다.
            check(manager.setDynamicShortcuts(builtIns + shortcuts)) { "시스템이 바로가기 갱신을 거부함" }
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
            .putExtra(QuickActionActivity.EXTRA_SHORTCUT_TOKEN, access.shortcutToken(null, rule.id))

        return ShortcutInfo.Builder(appContext, "$SHORTCUT_PREFIX${rule.id}")
            .setShortLabel(rule.name.trim().take(SHORT_LABEL_LIMIT))
            .setLongLabel("${rule.name} 매크로 실행")
            .setIcon(Icon.createWithResource(appContext, R.drawable.ic_launcher_foreground))
            .setIntent(intent)
            .setActivity(ComponentName(appContext, MainActivity::class.java))
            .setRank(rank + 3)
            .build()
    }

    /** 기본 차량 동작도 외부 앱이 명령을 바꿀 수 없는 서명된 바로가기로 발행한다. */
    private fun actionShortcut(action: String, label: Int, rank: Int): ShortcutInfo {
        val data = Uri.Builder().scheme("teslamacro").authority("run").appendQueryParameter("action", action).build()
        val intent = Intent(Intent.ACTION_VIEW, data, appContext, QuickActionActivity::class.java)
            .putExtra(QuickActionActivity.EXTRA_ACTION, action)
            .putExtra(QuickActionActivity.EXTRA_SHORTCUT_TOKEN, access.shortcutToken(action, null))
        // 홈에 고정된 구버전 정적 ID는 immutable이므로 새 동적 ID와 분리한다.
        return ShortcutInfo.Builder(appContext, "$QUICK_SHORTCUT_PREFIX$action")
            .setShortLabel(appContext.getString(label))
            .setIcon(Icon.createWithResource(appContext, R.drawable.ic_launcher_foreground))
            .setIntent(intent)
            .setActivity(ComponentName(appContext, MainActivity::class.java))
            .setRank(rank)
            .build()
    }

    private companion object {
        const val QUICK_SHORTCUT_PREFIX = "quick-v2-"
        const val SHORTCUT_PREFIX = "macro-"
        const val SHORT_LABEL_LIMIT = 20
    }
}

/** 슬롯이 적어도 사용자가 직접 실행할 수동 매크로가 먼저 보이도록 순서를 정한다. */
internal fun selectMacroShortcuts(rules: List<MacroRule>, limit: Int): List<MacroRule> = rules
    .asSequence()
    .filter { it.name.isNotBlank() && it.actions.isNotEmpty() }
    .distinctBy { it.id }
    .sortedWith(
        compareBy<MacroRule> { rule -> rule.triggers.none { it is Trigger.Manual } }
            .thenBy { it.id.startsWith("preset-") }
            .thenByDescending { it.enabled }
            .thenBy { it.name }
    )
    .take(limit.coerceAtLeast(0))
    .toList()

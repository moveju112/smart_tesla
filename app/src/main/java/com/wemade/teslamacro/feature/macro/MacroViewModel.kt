package com.wemade.teslamacro.feature.macro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.feature.macro.edit.MacroDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MacroViewModel(private val container: AppContainer) : ViewModel() {

    val rules = container.ruleStore.rules
    val running = container.runner.running
    val progress = container.runner.progress
    val log = container.runner.log

    /** null이면 목록, 값이 있으면 편집 화면 */
    private val _draft = MutableStateFlow<MacroDraft?>(null)
    val draft: StateFlow<MacroDraft?> = _draft.asStateFlow()

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { container.ruleStore.setEnabled(id, enabled) }
    }

    /** 조건과 무관하게 즉시 실행 (매크로 동작을 눈으로 확인할 때) */
    fun runNow(rule: MacroRule) {
        container.runner.launch(rule, System.currentTimeMillis())
    }

    fun stopAll() = container.runner.cancelAll()

    // ---- 편집 ----

    fun createMacro() {
        _draft.value = MacroDraft.blank()
    }

    fun editMacro(rule: MacroRule) {
        _draft.value = MacroDraft.from(rule)
    }

    fun updateDraft(draft: MacroDraft) {
        _draft.value = draft
    }

    fun cancelEdit() {
        _draft.value = null
    }

    fun saveDraft() {
        val current = _draft.value ?: return
        if (!current.canSave) return
        viewModelScope.launch {
            container.ruleStore.upsert(current.toRule())
            _draft.value = null
        }
    }

    /** 목록 카드의 삭제 버튼. 편집 화면에 들어가지 않고 바로 지운다 */
    fun delete(rule: MacroRule) {
        viewModelScope.launch { container.ruleStore.delete(rule.id) }
    }

    fun deleteDraft() {
        val current = _draft.value ?: return
        viewModelScope.launch {
            container.ruleStore.delete(current.id)
            _draft.value = null
        }
    }

    /** 프리셋을 복제해 새 매크로의 출발점으로 쓴다 */
    fun duplicate(rule: MacroRule) {
        _draft.value = MacroDraft.from(rule).copy(
            id = "macro-${java.util.UUID.randomUUID()}",
            name = "${rule.name} 복사본",
            isNew = true,
        )
    }
}

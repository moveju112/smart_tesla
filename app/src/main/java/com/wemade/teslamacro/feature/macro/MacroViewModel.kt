package com.wemade.teslamacro.feature.macro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.domain.gateway.LinkState
import com.wemade.teslamacro.domain.macro.ActionStep
import com.wemade.teslamacro.domain.macro.MacroRule
import com.wemade.teslamacro.feature.macro.edit.MacroDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MacroViewModel(private val container: AppContainer) : ViewModel() {

    val rules = container.ruleStore.rules
    val folders = container.ruleStore.folders
    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()
    private var saving = false

    /** 폴더 작업 결과를 소비해 같은 실패도 다음 요청에서 다시 알린다. */
    fun clearFolderError() { _folderError.value = null }

    /** 편집 내용은 보존하고 표시가 끝난 저장 오류만 비운다. */
    fun clearSaveError() { _saveError.value = null }

    /** 생성·이름 변경 결과를 저장하고 실패는 목록에서 알린다. */
    fun saveFolder(id: String?, name: String) {
        viewModelScope.launch {
            try {
                container.ruleStore.saveFolder(id, name)
                _folderError.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _folderError.value = error.message ?: "폴더를 저장하지 못했어요."
            }
        }
    }

    /** 폴더 밖 이동까지 같은 경로로 처리하며 실패를 숨기지 않는다. */
    fun moveToFolder(ruleId: String, folderId: String?) {
        viewModelScope.launch {
            try {
                container.ruleStore.moveToFolder(ruleId, folderId)
                _folderError.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _folderError.value = error.message ?: "매크로를 이동하지 못했어요."
            }
        }
    }
    val running = container.runner.running
    val progress = container.runner.progress
    val log = container.runner.log

    /** null이면 목록, 값이 있으면 편집 화면 */
    private val _draft = MutableStateFlow<MacroDraft?>(null)
    val draft: StateFlow<MacroDraft?> = _draft.asStateFlow()

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                container.ruleStore.setEnabled(id, enabled)
                if (enabled) container.poller.rearmMacro(id)
                _folderError.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _folderError.value = "자동 실행 설정을 저장하지 못했어요."
            }
        }
    }

    /** 조건과 무관하게 즉시 실행 (매크로 동작을 눈으로 확인할 때) */
    fun runNow(rule: MacroRule) {
        val needsVehicle = rule.actions.any { it is ActionStep.Run }
        // 직접 실행은 끝날 때까지 연결 사용권을 쥔다 — 연결 대기 시간만큼 시작 대기를 늘린다
        if (needsVehicle) {
            container.poller.holdConnectionWhileMacroRuns(rule.id, container.appScope, RUN_NOW_CONNECT_WAIT_MILLIS + 2_000L)
        }
        viewModelScope.launch {
            // 연결 보호로 끊긴 상태에서 바로 보내면 모든 단계가 즉시 실패한다 — 폴러가 다시 붙을 때까지 잠깐 기다린다
            if (needsVehicle && container.settingsStore.settings.first().isReady) {
                withTimeoutOrNull(RUN_NOW_CONNECT_WAIT_MILLIS) {
                    container.gateway.linkState.first { it is LinkState.Ready }
                }
            }
            // 수동 실행은 기존 실행을 끊고 처음부터 + 쿨다운 기록 (직후 트리거 재발동 방지)
            container.runner.launch(rule, System.currentTimeMillis(), restartIfRunning = true,
                onAccepted = { container.poller.recordFired(rule.id) })
        }
    }

    fun stopAll() = container.runner.cancelAll()

    // ---- 편집 ----

    private var draftFolderId: String? = null

    /** 현재 폴더에서 만든 매크로는 저장 후 같은 폴더에 넣는다. */
    fun createMacroInFolder(folderId: String?) {
        createMacro()
        draftFolderId = folderId
    }

    fun createMacro() {
        draftFolderId = null
        _saveError.value = null
        _draft.value = MacroDraft.blank()
    }

    fun editMacro(rule: MacroRule) {
        draftFolderId = null
        _saveError.value = null
        _draft.value = MacroDraft.from(rule)
    }

    fun updateDraft(draft: MacroDraft) {
        _saveError.value = null
        _draft.value = draft
    }

    fun cancelEdit() {
        _saveError.value = null
        _draft.value = null
    }
    fun saveDraft() {
        val current = _draft.value ?: return
        if (!current.canSave || saving) return
        saving = true
        _saveError.value = null
        val folderId = draftFolderId
        viewModelScope.launch {
            try {
                val saved = current.toRule()
                val before = container.ruleStore.rules.value.firstOrNull { it.id == saved.id }
                container.ruleStore.upsert(saved)
                // 발동 조건이 바뀌었거나 다시 켠 매크로는 이미 조건 안이어도 1회 발동하게 래치를 비운다
                if (before != null && (before.conditions != saved.conditions ||
                        before.triggers != saved.triggers || (!before.enabled && saved.enabled))) {
                    container.poller.rearmMacro(saved.id)
                }
                if (current.isNew && folderId != null) container.ruleStore.moveToFolder(current.id, folderId)
                if (_draft.value == current) _draft.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (_draft.value?.id == current.id) {
                    _saveError.value = error.message ?: "매크로를 저장하지 못했어요."
                }
            } finally {
                saving = false
            }
        }
    }

    /** 목록 카드의 삭제 버튼. 편집 화면에 들어가지 않고 바로 지운다 */
    fun delete(rule: MacroRule) {
        viewModelScope.launch {
            try {
                container.ruleStore.delete(rule.id)
                _folderError.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _folderError.value = "매크로를 삭제하지 못했어요."
            }
        }
    }

    fun deleteDraft() {
        val current = _draft.value ?: return
        viewModelScope.launch {
            try {
                container.ruleStore.delete(current.id)
                if (_draft.value?.id == current.id) _draft.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (_draft.value?.id == current.id) _saveError.value = "매크로를 삭제하지 못했어요."
            }
        }
    }

    /** 프리셋을 복제해 새 매크로의 출발점으로 쓴다 */
    fun duplicate(rule: MacroRule) {
        draftFolderId = folders.value.firstOrNull { rule.id in it.ruleIds }?.id
        _saveError.value = null
        _draft.value = MacroDraft.from(rule).copy(
            id = "macro-${java.util.UUID.randomUUID()}",
            name = "${rule.name} 복사본",
            // 같은 트리거로 원본과 함께 두 번 실행되지 않게 자동 실행은 꺼 둔다. 편집 화면에서 켤 수 있다
            enabled = false,
            isNew = true,
        )
    }

    private companion object {
        // 저장 주소 직행 연결이 보통 이 안에 끝난다. 더 기다리면 누른 뒤 반응이 없어 보인다
        const val RUN_NOW_CONNECT_WAIT_MILLIS = 15_000L
    }
}

package com.wemade.teslamacro.ui

import kotlinx.coroutines.CancellationException

/** 저장 실패는 화면에 알리되 자동 재시도나 취소 후 후속 작업을 실행하지 않는다. */
internal suspend fun applySettingChange(change: suspend () -> Unit, onFailure: (Exception) -> Unit) {
    try {
        change()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}

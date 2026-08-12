package com.wemade.teslamacro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.feature.dashboard.DashboardViewModel
import com.wemade.teslamacro.feature.macro.MacroViewModel
import com.wemade.teslamacro.feature.pairing.PairingViewModel
import com.wemade.teslamacro.feature.settings.SettingsViewModel

/** 컨테이너를 ViewModel에 넘겨주는 팩토리. DI 프레임워크 대신 쓰는 최소 장치 */
class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
            DashboardViewModel(container) as T
        modelClass.isAssignableFrom(MacroViewModel::class.java) ->
            MacroViewModel(container) as T
        modelClass.isAssignableFrom(PairingViewModel::class.java) ->
            PairingViewModel(container) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container) as T
        else -> error("등록되지 않은 ViewModel: ${modelClass.name}")
    }
}

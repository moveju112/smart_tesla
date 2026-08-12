package com.wemade.teslamacro

import android.app.Application
import com.wemade.teslamacro.di.AppContainer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TeslaMacroApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val _ready = MutableStateFlow(false)
    /** 컨테이너 초기화가 끝났는지. 화면은 준비될 때까지 스플래시를 보여준다 */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MainScope().launch {
            container.initialize()
            _ready.value = true
        }
    }
}

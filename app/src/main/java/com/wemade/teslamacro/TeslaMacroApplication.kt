package com.wemade.teslamacro

import android.app.Application
import com.wemade.teslamacro.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val initializationScope = MainScope()
    private var initializationJob: Job? = null
    private val _initializationError = MutableStateFlow<String?>(null)
    val initializationError: StateFlow<String?> = _initializationError.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        retryInitialization()
    }

    /** 읽기 실패를 기본값으로 덮거나 앱을 종료하지 않고, 사용자가 다시 시도할 때만 준비를 재개한다. */
    fun retryInitialization() {
        if (_ready.value || initializationJob?.isActive == true) return
        _initializationError.value = null
        initializationJob = initializationScope.launch {
            try {
                container.initialize()
                _ready.value = true
                container.diagnosticUploader.start()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _initializationError.value = "데이터를 불러오지 못했어요. 기존 데이터는 지우지 않았어요.\n저장 공간을 확인한 뒤 다시 시도해 주세요."
                com.wemade.teslable.DiagLog.add("앱 초기화 실패 · ${error.javaClass.simpleName}")
            }
        }
    }
}

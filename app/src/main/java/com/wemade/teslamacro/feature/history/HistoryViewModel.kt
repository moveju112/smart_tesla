package com.wemade.teslamacro.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wemade.teslamacro.di.AppContainer
import com.wemade.teslamacro.data.history.HistorySample
import com.wemade.teslamacro.data.history.HistoryOverview
import com.wemade.teslamacro.data.history.HistorySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 기록 목록은 화면을 보는 동안만 조회하고 선택한 경로만 압축 해제한다. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    private val limit = MutableStateFlow(50)
    private val selected = MutableStateFlow<HistorySession?>(null)
    private val detail = MutableStateFlow(HistoryDetail())
    private val settings = container.settingsStore.settings
    private val overview = combine(settings, container.vehicleHistory.revision, limit) { options, _, count ->
        options.vin to count
    }.mapLatest { (identity, count) -> container.vehicleHistory.overview(identity, count) }
    val state = combine(settings, overview, detail) { options, history, selection ->
        HistoryUiState(options.historyEnabled, options.isReady, options.deviceMode,
            options.historyBatteryCapacityKwh, history, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        viewModelScope.launch {
            combine(selected, settings, container.vehicleHistory.revision) { session, options, _ -> session to options.vin }
                .collectLatest { (session, identity) ->
                    if (session == null) { detail.value = HistoryDetail(); return@collectLatest }
                    detail.value = HistoryDetail(session = session, loading = true)
                    try {
                        val points = container.vehicleHistory.samples(identity, session.id)
                        if (points.isEmpty()) { selected.value = null; detail.value = HistoryDetail(); return@collectLatest }
                        val updated = container.vehicleHistory.overview(identity, limit.value).sessions.firstOrNull { it.id == session.id }
                        detail.value = HistoryDetail(updated ?: session, points)
                    } catch (error: CancellationException) { throw error
                    } catch (_: Exception) { detail.value = HistoryDetail(session, error = "경로 기록을 읽지 못했어요") }
                }
        }
    }

    /** 해제 경계는 기록 저장소에도 전달해 다음 연결과 합치지 않는다. */
    fun setEnabled(enabled: Boolean) { viewModelScope.launch {
        container.settingsStore.setHistoryEnabled(enabled)
        if (!enabled) container.vehicleHistory.finish(settings.first().vin)
        container.poller.nudge()
    } }

    /** 용량 변경은 원본을 수정하지 않고 화면 계산에만 적용한다. */
    fun setCapacity(value: Double) { viewModelScope.launch { container.settingsStore.setHistoryBatteryCapacity(value) } }

    /** 선택 변경은 collectLatest가 이전 경로 조회를 취소한다. */
    fun select(session: HistorySession?) { selected.value = session }

    /** 오래된 기록은 요청할 때만 목록에 추가한다. */
    fun loadMore() { limit.update { it + 50 } }
}

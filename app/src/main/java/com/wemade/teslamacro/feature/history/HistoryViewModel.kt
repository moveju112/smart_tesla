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

/** 화면을 보는 동안 목록과 기간 요약을 조회하고 선택한 기록의 상세를 갱신한다. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    private val limit = MutableStateFlow(50)
    private val periodDays = MutableStateFlow(30)
    private val selected = MutableStateFlow<HistorySession?>(null)
    private val detail = MutableStateFlow(HistoryDetail())
    private val settings = container.settingsStore.settings
    private val overview = combine(settings, container.vehicleHistory.revision, limit, periodDays) { options, _, count, days ->
        Triple(options.vin, count, days)
    }.mapLatest { (identity, count, days) -> container.vehicleHistory.overview(identity, count, days) }
    val state = combine(settings, overview, detail) { options, history, selection ->
        HistoryUiState(options.historyEnabled, options.isReady, options.deviceMode,
            history, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        viewModelScope.launch {
            var loadedIdentity: String? = null
            combine(selected, settings, container.vehicleHistory.revision) { session, options, _ -> session to options.vin }
                .collectLatest { (session, identity) ->
                    if (session == null) { detail.value = HistoryDetail(); return@collectLatest }
                    // 같은 기록의 갱신마다 WebView를 제거하면 지도 로딩이 반복돼 흰 화면만 남는다.
                    if (loadedIdentity != identity || detail.value.session?.id != session.id) {
                        loadedIdentity = identity
                        detail.value = HistoryDetail(session = session, loading = true)
                    }
                    try {
                        val points = container.vehicleHistory.samples(identity, session.id)
                        if (points.isEmpty()) { selected.value = null; detail.value = HistoryDetail(); return@collectLatest }
                        val updated = container.vehicleHistory.overview(identity, limit.value, periodDays.value).sessions.firstOrNull { it.id == session.id }
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

    /** 선택 변경은 collectLatest가 이전 경로 조회를 취소한다. */
    fun select(session: HistorySession?) { selected.value = session }

    /** 오래된 기록은 요청할 때만 목록에 추가한다. */
    fun loadMore() { limit.update { it + 50 } }

    /** 지원 기간만 선택하고 기존 전체 기록 목록은 유지한다. */
    fun setPeriod(days: Int) { if (days in setOf(7, 30, 90)) periodDays.value = days }
}

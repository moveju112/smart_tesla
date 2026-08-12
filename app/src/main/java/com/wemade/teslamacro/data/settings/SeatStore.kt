package com.wemade.teslamacro.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wemade.teslamacro.domain.model.Level
import com.wemade.teslamacro.domain.model.SeatClimate
import com.wemade.teslamacro.domain.model.SeatMode
import com.wemade.teslamacro.domain.model.SeatPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.seatDataStore: DataStore<Preferences> by preferencesDataStore("seat_climate")

/**
 * 좌석 통풍/열선 설정을 기기에 저장한다.
 *
 * 차량이 좌석 상태를 안정적으로 돌려주지 않으므로, 사용자가 고른 값을
 * 여기에 저장해 화면의 기준으로 쓴다. 앱을 껐다 켜도 마지막 설정이 남는다.
 */
class SeatStore(private val context: Context) {

    /** 앞좌석 두 자리만 저장한다 (통풍은 앞좌석만 지원) */
    private val seats = listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT)

    val state: Flow<Map<SeatPosition, SeatClimate>> = context.seatDataStore.data.map { prefs ->
        seats.associateWith { seat ->
            SeatClimate(
                mode = runCatching {
                    SeatMode.valueOf(prefs[modeKey(seat)] ?: SeatMode.COOL.name)
                }.getOrDefault(SeatMode.COOL),
                level = Level.fromStep(prefs[levelKey(seat)] ?: 0),
            )
        }
    }

    suspend fun set(seat: SeatPosition, climate: SeatClimate) {
        context.seatDataStore.edit {
            it[modeKey(seat)] = climate.mode.name
            it[levelKey(seat)] = climate.level.ordinal
        }
    }

    private fun modeKey(seat: SeatPosition) = stringPreferencesKey("mode_${seat.name}")
    private fun levelKey(seat: SeatPosition) = intPreferencesKey("level_${seat.name}")
}

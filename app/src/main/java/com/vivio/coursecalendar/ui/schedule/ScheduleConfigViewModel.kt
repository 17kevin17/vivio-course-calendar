package com.vivio.coursecalendar.ui.schedule

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vivio.coursecalendar.VivioApp
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.schedule.Season
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 作息配置 UI 状态：显示某季五大节 */
data class PeriodUi(
    val number: Int,
    val startHour: Int?,
    val startMinute: Int?,
    val endHour: Int?,
    val endMinute: Int?
)

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState
    data class Ready(val season: Season, val periods: List<PeriodUi>, val version: Int) : ScheduleUiState
    data class Saved(val season: Season) : ScheduleUiState
    data class Failed(val message: String) : ScheduleUiState
}

class ScheduleConfigViewModel(
    private val repo: ScheduleRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ScheduleUiState>(ScheduleUiState.Loading)
    val state: StateFlow<ScheduleUiState> = _state

    private var editSeason: Season = Season.SUMMER

    init {
        load(editSeason)
    }

    fun switchSeason(season: Season) {
        editSeason = season
        load(season)
    }

    fun load(season: Season) {
        _state.value = ScheduleUiState.Loading
        viewModelScope.launch {
            val table = repo.get(season)
            val version = repo.getVersion(season)
            val periods = (1..5).map { no ->
                val p = table.period(no)
                PeriodUi(
                    number = no,
                    startHour = p?.start?.hour,
                    startMinute = p?.start?.minute,
                    endHour = p?.end?.hour,
                    endMinute = p?.end?.minute
                )
            }
            _state.value = ScheduleUiState.Ready(season, periods, version)
        }
    }

    fun updatePeriod(no: Int, startHour: Int?, startMinute: Int?, endHour: Int?, endMinute: Int?) {
        val current = _state.value as? ScheduleUiState.Ready ?: return
        _state.value = current.copy(periods = current.periods.map {
            if (it.number == no) it.copy(
                startHour = startHour, startMinute = startMinute,
                endHour = endHour, endMinute = endMinute
            ) else it
        })
    }

    fun save() {
        val current = _state.value as? ScheduleUiState.Ready ?: return
        val incomplete = current.periods.filter {
            it.startHour == null || it.startMinute == null || it.endHour == null || it.endMinute == null
        }
        if (incomplete.isNotEmpty()) {
            _state.value = ScheduleUiState.Failed("第 ${incomplete.joinToString("、") { it.number.toString() }} 大节时间不完整")
            return
        }
        viewModelScope.launch {
            try {
                val periods = current.periods.map {
                    com.vivio.coursecalendar.domain.schedule.SchedulePeriod(
                        it.number,
                        java.time.LocalTime.of(it.startHour!!, it.startMinute!!),
                        java.time.LocalTime.of(it.endHour!!, it.endMinute!!)
                    )
                }
                repo.save(current.season, periods, current.version + 1)
                _state.value = ScheduleUiState.Saved(current.season)
            } catch (e: com.vivio.coursecalendar.data.repository.ScheduleValidationException) {
                _state.value = ScheduleUiState.Failed(e.message ?: "作息配置不合法")
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext as VivioApp
            @Suppress("UNCHECKED_CAST")
            return ScheduleConfigViewModel(app.container.scheduleRepository) as T
        }
    }
}

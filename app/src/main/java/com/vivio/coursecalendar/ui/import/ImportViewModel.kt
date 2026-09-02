package com.vivio.coursecalendar.ui.import

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vivio.coursecalendar.VivioApp
import com.vivio.coursecalendar.domain.import.CommitResult
import com.vivio.coursecalendar.domain.import.ImportManager
import com.vivio.coursecalendar.domain.import.ImportPreview
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.schedule.Season
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 导入流程 UI 状态 */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Loading : ImportUiState
    /** 已选文件，等待用户确认课表类型（自动识别失败时） */
    data class NeedSource(val fileName: String, val bytes: ByteArray) : ImportUiState
    /** 校内课表：等待用户选择季节（春季下午未配置时会提示） */
    data class NeedSeason(val fileName: String, val bytes: ByteArray) : ImportUiState
    /** 预览就绪，等待校对与确认 */
    data class PreviewReady(val preview: ImportPreview) : ImportUiState
    /** 导入完成 */
    data class Done(val preview: ImportPreview, val result: CommitResult) : ImportUiState
    data class Failed(val message: String, val fileName: String, val bytes: ByteArray?) : ImportUiState
}

class ImportViewModel(
    private val context: Context,
    private val importManager: ImportManager,
    private val scheduleRepository: ScheduleRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    private var pendingSeason: Season? = null
    private var reminderMinutes: Int? = null
    private var excludedSet = mutableSetOf<String>()

    fun onFilePicked(uri: Uri, fileName: String) {
        _state.value = ImportUiState.Loading
        viewModelScope.launch {
            val bytes = readBytes(uri)
            if (bytes == null || bytes.isEmpty()) {
                _state.value = ImportUiState.Failed("无法读取所选文件", fileName, null)
                return@launch
            }
            when (importManager.detectSource(bytes)) {
                EventSource.PART_TIME -> parse(bytes, fileName, null)
                EventSource.UNIVERSITY -> _state.value = ImportUiState.NeedSeason(fileName, bytes)
                null -> _state.value = ImportUiState.NeedSource(fileName, bytes)
            }
        }
    }

    fun onSourceChosen(source: EventSource, fileName: String, bytes: ByteArray) {
        _state.value = ImportUiState.Loading
        viewModelScope.launch {
            if (source == EventSource.UNIVERSITY) {
                _state.value = ImportUiState.NeedSeason(fileName, bytes)
            } else {
                parse(bytes, fileName, null)
            }
        }
    }

    fun onSeasonChosen(season: Season, fileName: String, bytes: ByteArray) {
        pendingSeason = season
        _state.value = ImportUiState.Loading
        viewModelScope.launch { parse(bytes, fileName, season) }
    }

    private suspend fun parse(bytes: ByteArray, fileName: String, season: Season?) {
        val schedule = season?.let { scheduleRepository.get(it) }
        // 春季下午未配置：引导去配置作息，不直接导入
        if (season != null && schedule != null) {
            val missing = scheduleRepository.missingPeriods(season, schedule)
            if (missing.isNotEmpty()) {
                _state.value = ImportUiState.Failed(
                    "「${season.label}」第 ${missing.joinToString("、")} 大节时间尚未配置，请先到「作息设置」补齐后再导入",
                    fileName, bytes
                )
                return
            }
        }
        when (val outcome = importManager.parseAndPreview(bytes, fileName, season, schedule)) {
            is ImportManager.ParseOutcome.Previewed -> {
                excludedSet.clear()
                excludedSet.addAll(outcome.preview.items.filter { it.excluded }.map { it.event.identityKey })
                _state.value = ImportUiState.PreviewReady(outcome.preview)
            }
            is ImportManager.ParseOutcome.Error -> {
                if (outcome.detectedSource == null) {
                    _state.value = ImportUiState.NeedSource(fileName, bytes)
                } else {
                    _state.value = ImportUiState.Failed(outcome.message, fileName, bytes)
                }
            }
        }
    }

    /** 预览校对：切换某条事件的排除标记 */
    fun toggleExclude(identityKey: String) {
        val current = _state.value as? ImportUiState.PreviewReady ?: return
        if (!excludedSet.add(identityKey)) excludedSet.remove(identityKey)
        _state.value = ImportUiState.PreviewReady(current.preview.copy(items = current.preview.items.map {
            if (it.event.identityKey == identityKey) it.copy(excluded = identityKey in excludedSet) else it
        }))
    }

    fun onReminderChosen(minutes: Int?) {
        reminderMinutes = minutes
    }

    fun confirmImport() {
        val current = _state.value as? ImportUiState.PreviewReady ?: return
        _state.value = ImportUiState.Loading
        viewModelScope.launch {
            val result = importManager.commit(current.preview, reminderMinutes, excludedSet)
            _state.value = ImportUiState.Done(current.preview, result)
        }
    }

    fun reset() {
        pendingSeason = null
        reminderMinutes = null
        excludedSet.clear()
        _state.value = ImportUiState.Idle
    }

    private fun readBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext as VivioApp
            @Suppress("UNCHECKED_CAST")
            return ImportViewModel(
                context.applicationContext,
                app.container.importManager,
                app.container.scheduleRepository
            ) as T
        }
    }
}

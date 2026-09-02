package com.vivio.coursecalendar.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vivio.coursecalendar.VivioApp
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.domain.import.ImportManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BatchHistoryViewModel(
    private val db: AppDatabase,
    private val importManager: ImportManager
) : ViewModel() {

    val batches: Flow<List<com.vivio.coursecalendar.data.local.entity.ImportBatchEntity>> =
        db.importBatchDao().observeAll()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun undo(batchId: Long) {
        viewModelScope.launch {
            val ok = importManager.undo(batchId)
            _message.value = if (ok) "已撤销该批次导入" else "撤销失败"
        }
    }

    fun clearMessage() { _message.value = null }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext as VivioApp
            @Suppress("UNCHECKED_CAST")
            return BatchHistoryViewModel(app.container.database, app.container.importManager) as T
        }
    }
}

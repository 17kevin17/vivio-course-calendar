package com.vivio.coursecalendar.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: BatchHistoryViewModel = viewModel(factory = BatchHistoryViewModel.Factory(context))
    val batches by viewModel.batches.collectAsState(initial = emptyList())
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var undoTarget by remember { mutableStateOf<ImportBatchEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (batches.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂无导入记录", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(batches, key = { it.id }) { batch ->
                    BatchCard(batch = batch, onUndo = { undoTarget = batch })
                }
            }
        }
    }

    undoTarget?.let { batch ->
        AlertDialog(
            onDismissRequest = { undoTarget = null },
            title = { Text("撤销导入") },
            text = { Text("将删除该批次写入日历的 ${batch.createdCount + batch.updatedCount} 条事件，且不影响其他日历事件。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.undo(batch.id)
                    undoTarget = null
                }) { Text("确定撤销") }
            },
            dismissButton = {
                TextButton(onClick = { undoTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BatchCard(batch: ImportBatchEntity, onUndo: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val sourceLabel = if (batch.source == "UNIVERSITY") "校内" else "兼职"
    val seasonLabel = batch.season?.let { if (it == "SPRING") "春季" else "夏季" }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${batch.fileName}（$sourceLabel${seasonLabel?.let { "·$it" } ?: ""}）",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    dateFmt.format(Date(batch.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "新增 ${batch.createdCount}　更新 ${batch.updatedCount}　跳过 ${batch.invalidCount}${if (batch.status == "PARTIAL") "　部分失败" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (batch.status != "UNDONE") {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUndo) { Text("撤销") }
            } else {
                Spacer(Modifier.width(8.dp))
                Text("已撤销", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

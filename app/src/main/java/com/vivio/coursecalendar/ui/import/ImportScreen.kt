package com.vivio.coursecalendar.ui.import

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivio.coursecalendar.domain.import.PreviewItem
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.schedule.Season
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val TIME_RANGE_FMT = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory(context))
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingConfirm by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFilePicked(uri, queryFileName(context, uri))
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val calendarGranted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        if (pendingConfirm && calendarGranted) {
            pendingConfirm = false
            viewModel.confirmImport()
        } else if (pendingConfirm) {
            pendingConfirm = false
            scope.launch { snackbarHostState.showSnackbar("需要日历权限才能写入系统日历") }
        }
    }

    fun requestPermissionsAndConfirm() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.READ_CALENDAR
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.WRITE_CALENDAR
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            viewModel.confirmImport()
        } else {
            pendingConfirm = true
            calendarPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入课表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val s = state) {
                is ImportUiState.Idle -> IdleContent(onPickFile = {
                    filePicker.launch(arrayOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "*/*"
                    ))
                })
                is ImportUiState.Loading -> LoadingContent()
                is ImportUiState.NeedSource -> NeedSourceContent(
                    fileName = s.fileName,
                    onChoose = { source -> viewModel.onSourceChosen(source, s.fileName, s.bytes) }
                )
                is ImportUiState.NeedSeason -> NeedSeasonContent(
                    fileName = s.fileName,
                    onChoose = { season -> viewModel.onSeasonChosen(season, s.fileName, s.bytes) }
                )
                is ImportUiState.PreviewReady -> PreviewContent(
                    preview = s.preview,
                    excludedSet = viewModel.excludedFingerprints(),
                    onToggle = { viewModel.toggleExclude(it) },
                    onReminder = { viewModel.onReminderChosen(it) },
                    onConfirm = { requestPermissionsAndConfirm() }
                )
                is ImportUiState.Done -> DoneContent(
                    preview = s.preview,
                    result = s.result,
                    onFinish = { viewModel.reset() }
                )
                is ImportUiState.Failed -> FailedContent(
                    message = s.message,
                    onBack = { viewModel.reset() }
                )
            }
        }
    }
}

private fun queryFileName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx) ?: uri.lastPathSegment ?: "未命名"
        }
    }
    return uri.lastPathSegment ?: "未命名"
}

@Composable
private fun IdleContent(onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("选择课程表 Excel 文件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 .xls / .xlsx\n自动识别校内课表与兼职课表，无需手动选择模板",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFile) { Text("选择 Excel 文件") }
    }
}

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在解析…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NeedSourceContent(fileName: String, onChoose: (EventSource) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("无法自动识别课表类型", style = MaterialTheme.typography.titleMedium)
        Text(
            "文件：$fileName\n请手动选择课表类型",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onChoose(EventSource.UNIVERSITY) }, modifier = Modifier.fillMaxWidth()) {
            Text("校内课表")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onChoose(EventSource.PART_TIME) }, modifier = Modifier.fillMaxWidth()) {
            Text("兼职课表")
        }
    }
}

@Composable
private fun NeedSeasonContent(fileName: String, onChoose: (Season) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("选择本学期季节", style = MaterialTheme.typography.titleMedium)
        Text(
            "文件：$fileName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "校内课表需要按季节映射大节时间。春季下午时间未配置时，请先到「作息设置」补齐。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onChoose(Season.SPRING) }, modifier = Modifier.fillMaxWidth()) { Text("春季") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onChoose(Season.SUMMER) }, modifier = Modifier.fillMaxWidth()) { Text("夏季") }
    }
}

@Composable
private fun PreviewContent(
    preview: com.vivio.coursecalendar.domain.import.ImportPreview,
    excludedSet: Set<String>,
    onToggle: (String) -> Unit,
    onReminder: (Int?) -> Unit,
    onConfirm: () -> Unit
) {
    var reminder by remember { mutableStateOf<Int?>(20) }
    val counts = preview.counts

    Column(Modifier.fillMaxSize()) {
        SummaryHeader(preview, counts)
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(preview.items, key = { it.event.id.ifBlank { it.event.eventFingerprint } }) { item ->
                PreviewItemRow(
                    item = item,
                    excluded = item.event.eventFingerprint in excludedSet,
                    onToggle = { onToggle(item.event.eventFingerprint) }
                )
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("课前提醒", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                listOf(0 to "关闭", 10 to "10分", 20 to "20分", 30 to "30分").forEach { (min, label) ->
                    FilterChip(
                        selected = (if (min == 0) null else min) == reminder,
                        onClick = {
                            reminder = if (min == 0) null else min
                            onReminder(reminder)
                        },
                        label = { Text(label) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("确认导入（${preview.items.count { it.event.eventFingerprint !in excludedSet && it.event.blocker == null }} 条）")
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    preview: com.vivio.coursecalendar.domain.import.ImportPreview,
    counts: Map<EventState, Int>
) {
    Column(Modifier.padding(12.dp)) {
        Text(
            "${preview.fileName}　·　${if (preview.source == EventSource.UNIVERSITY) "校内课表" else "兼职课表"}${preview.season?.let { "（${it.label}）" } ?: ""}",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("新增 ${counts[EventState.NEW] ?: 0}")
                append("　更新 ${counts[EventState.MODIFIED] ?: 0}")
                append("　无变化 ${counts[EventState.UNCHANGED] ?: 0}")
                append("　异常 ${counts[EventState.INVALID] ?: 0}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (preview.missing.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "旧课表中 ${preview.missing.size} 条未出现在新文件（不会自动删除）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PreviewItemRow(
    item: PreviewItem,
    excluded: Boolean,
    onToggle: () -> Unit
) {
    val event = item.event
    val stateColor = when (item.state) {
        EventState.INVALID -> MaterialTheme.colorScheme.error
        EventState.CONFLICT -> MaterialTheme.colorScheme.tertiary
        EventState.MODIFIED -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (excluded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = !excluded, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (excluded) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                )
                Text(
                    "${event.startTime.format(TIME_FMT)} - ${event.endTime.format(TIME_RANGE_FMT)}${event.location?.let { "　·　$it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.state == EventState.INVALID && event.blocker != null) {
                    Text(
                        "⚠ ${event.blocker}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (item.conflictWith.isNotEmpty()) {
                    Text(
                        "冲突：与 ${item.conflictWith.joinToString("、")} 时间重叠",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                stateLabel(item.state),
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

private fun stateLabel(state: EventState): String = when (state) {
    EventState.NEW -> "新增"
    EventState.UNCHANGED -> "无变化"
    EventState.MODIFIED -> "更新"
    EventState.CANCELLED -> "取消"
    EventState.MISSING -> "缺失"
    EventState.CONFLICT -> "冲突"
    EventState.INVALID -> "异常"
}

@Composable
private fun DoneContent(
    preview: com.vivio.coursecalendar.domain.import.ImportPreview,
    result: com.vivio.coursecalendar.domain.import.CommitResult,
    onFinish: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp).height(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("导入完成", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            buildString {
                append("新增 ${result.created}　")
                append("更新 ${result.updated}　")
                append("无变化 ${result.unchanged}　")
                append("删除 ${result.deleted}\n")
                append("跳过（异常/已排除）${result.invalid}")
                if (result.failed > 0) append("\n${result.failed} 条写入失败，其余已成功")
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}

@Composable
private fun FailedContent(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.width(48.dp).height(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("返回") }
    }
}

// ViewModel 暴露辅助：预览页读取当前排除集合
fun ImportViewModel.excludedFingerprints(): Set<String> {
    val s = state.value as? ImportUiState.PreviewReady ?: return emptySet()
    return s.preview.items.filter { it.excluded }.map { it.event.eventFingerprint }.toSet()
}

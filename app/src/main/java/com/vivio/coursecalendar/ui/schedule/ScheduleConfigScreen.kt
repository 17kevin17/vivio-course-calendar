package com.vivio.coursecalendar.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivio.coursecalendar.domain.schedule.Season
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ScheduleConfigViewModel = viewModel(factory = ScheduleConfigViewModel.Factory(context))
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    val ready = state as? ScheduleUiState.Ready
    val selectedSeason = ready?.season ?: Season.SUMMER

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("作息设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            TabRow(
                selectedTabIndex = if (selectedSeason == Season.SPRING) 0 else 1
            ) {
                Tab(
                    selected = selectedSeason == Season.SPRING,
                    onClick = { viewModel.switchSeason(Season.SPRING) },
                    text = { Text("春季") }
                )
                Tab(
                    selected = selectedSeason == Season.SUMMER,
                    onClick = { viewModel.switchSeason(Season.SUMMER) },
                    text = { Text("夏季") }
                )
            }

            if (ready != null) {
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(
                        "每个大节：45 分钟上课 + 10 分钟休息 + 45 分钟上课 = 100 分钟日历事件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ready.periods.forEach { p ->
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("第${p.number}大节", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            TimeButton(
                                label = p.startHour?.let { "%02d:%02d".format(it, p.startMinute ?: 0) } ?: "开始时间",
                                filled = p.startHour != null,
                                onClick = { picker = PickerRequest(p.number, isStart = true) }
                            )
                            Text("—", modifier = Modifier.padding(horizontal = 8.dp))
                            TimeButton(
                                label = p.endHour?.let { "%02d:%02d".format(it, p.endMinute ?: 0) } ?: "结束时间",
                                filled = p.endHour != null,
                                onClick = { picker = PickerRequest(p.number, isStart = false) }
                            )
                        }
                    }
                    HorizontalDivider()
                }
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Text("保存${ready.season.label}作息")
                }
            }
        }
    }

    picker?.let { req ->
        val startHour = ready?.periods?.firstOrNull { it.number == req.period }?.startHour
        val startMinute = ready?.periods?.firstOrNull { it.number == req.period }?.startMinute
        val timePickerState = rememberTimePickerState(
            initialHour = (if (req.isStart) startHour else ready?.periods?.firstOrNull { it.number == req.period }?.endHour) ?: 8,
            initialMinute = (if (req.isStart) startMinute else ready?.periods?.firstOrNull { it.number == req.period }?.endMinute) ?: 0
        )
        AlertDialog(
            onDismissRequest = { picker = null },
            confirmButton = {
                TextButton(onClick = {
                    val h = timePickerState.hour
                    val m = timePickerState.minute
                    if (req.isStart) {
                        viewModel.updatePeriod(req.period, h, m, startHour, startMinute)
                    } else {
                        val cur = ready?.periods?.firstOrNull { it.number == req.period }
                        viewModel.updatePeriod(req.period, cur?.startHour, cur?.startMinute, h, m)
                    }
                    picker = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picker = null }) { Text("取消") }
            },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }

    // 保存/失败反馈：状态变化时执行一次
    LaunchedEffect(state) {
        when (state) {
            is ScheduleUiState.Saved -> {
                snackbarHostState.showSnackbar("已保存${(state as ScheduleUiState.Saved).season.label}作息")
                viewModel.switchSeason((state as ScheduleUiState.Saved).season)
            }
            is ScheduleUiState.Failed -> {
                snackbarHostState.showSnackbar((state as ScheduleUiState.Failed).message)
                viewModel.load((state as ScheduleUiState.Ready?)?.season ?: Season.SUMMER)
            }
            else -> Unit
        }
    }
}

private data class PickerRequest(val period: Int, val isStart: Boolean)

@Composable
private fun TimeButton(label: String, filled: Boolean, onClick: () -> Unit) {
    if (filled) {
        OutlinedButton(onClick = onClick) { Text(label) }
    } else {
        Button(onClick = onClick) { Text(label) }
    }
}

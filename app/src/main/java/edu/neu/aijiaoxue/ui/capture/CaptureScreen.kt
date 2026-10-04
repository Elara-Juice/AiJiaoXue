package edu.neu.aijiaoxue.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.model.MaterialType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 课堂督导采集页（Routes.capture(taskId)）。
 * NF-01：录音、拍照、互动标注、参与状态都在本页一步触达。已完成录音（US08）、拍照（US09），
 * 互动标注（US12）、参与状态（US13）、材料删除（US11）的组件做好后放在拍照卡片下面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(onBack: () -> Unit, viewModel: CaptureViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askLeave by rememberSaveable { mutableStateOf(false) }
    var showMicRationale by rememberSaveable { mutableStateOf(false) }
    var viewingPhotoId by rememberSaveable { mutableStateOf(0L) }
    val editingPhotoId by viewModel.editingPhotoId.collectAsState()
    val editingPhotoIsNew by viewModel.editingPhotoIsNew.collectAsState()

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    // US08 异常处理：未授予麦克风权限时引导开启；通知权限只影响“正在录音”通知是否显示，不强制
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) viewModel.startRecording() else showMicRationale = true
    }
    val onStart = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startRecording()
        } else {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    val leave = { if (state.isRecordingHere) askLeave = true else onBack() }
    BackHandler(enabled = state.isRecordingHere) { askLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.task?.courseName ?: "课堂督导") },
                navigationIcon = { TextButton(onClick = leave) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            state.isLoading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.blockedMessage != null -> Box(modifier, contentAlignment = Alignment.Center) {
                Text(state.blockedMessage!!, style = MaterialTheme.typography.bodyLarge)
            }
            else -> CaptureContent(
                state = state,
                playingId = viewModel.player.playingId,
                onStart = onStart,
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                onStop = { viewModel.stopRecording() },
                onPlay = viewModel::togglePlay,
                onPreparePhoto = viewModel::preparePhoto,
                onPhotoTaken = viewModel::onPhotoTaken,
                onImport = viewModel::importPhoto,
                onPhotoError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                onOpenPhoto = { viewingPhotoId = it.material.id },
                modifier = modifier,
            )
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("正在录音") },
            text = { Text("离开前需要先结束录音，已录部分会保存到材料列表。") },
            confirmButton = {
                TextButton(onClick = {
                    askLeave = false
                    viewModel.stopRecording(then = onBack)
                }) { Text("结束录音并离开") }
            },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text("继续录音") } },
        )
    }

    // US09：查看大图
    state.materials.firstOrNull { it.material.id == viewingPhotoId }?.let { item ->
        PhotoViewer(
            item = item,
            editable = state.canCapture,
            onEdit = {
                viewingPhotoId = 0L
                viewModel.editPhoto(item.material.id)
            },
            onDismiss = { viewingPhotoId = 0L },
        )
    }

    // US09：选择材料类型、填写备注
    state.materials.firstOrNull { it.material.id == editingPhotoId }?.let { item ->
        PhotoTypeDialog(
            material = item.material,
            isNew = editingPhotoIsNew,
            onSave = { type, note -> viewModel.savePhoto(item.material, type, note) },
            onDismiss = viewModel::dismissPhotoDialog,
        )
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text("需要麦克风权限") },
            text = { Text("录制课堂音频需要麦克风权限。请在系统设置中为“爱教学”开启麦克风权限后重试。") },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { showMicRationale = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CaptureContent(
    state: CaptureUiState,
    playingId: Long?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onPlay: (MaterialItem) -> Unit,
    onPreparePhoto: () -> Uri?,
    onPhotoTaken: (Boolean) -> Unit,
    onImport: (Uri) -> Unit,
    onPhotoError: (String) -> Unit,
    onOpenPhoto: (MaterialItem) -> Unit,
    modifier: Modifier,
) {
    // 每秒刷新一次课堂计时和录音时长
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            elapsedNow = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ClassHeader(state, now)
        }
        item {
            RecordingCard(
                state = state,
                durationMs = state.recorder.durationAt(elapsedNow),
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
            )
        }
        item {
            PhotoCaptureCard(
                enabled = state.canCapture,
                onPreparePhoto = onPreparePhoto,
                onPhotoTaken = onPhotoTaken,
                onImport = onImport,
                onError = onPhotoError,
            )
        }
        item {
            Text("课堂材料", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.materials.isEmpty()) {
            item {
                Text(
                    "暂无材料",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.materials, key = { it.material.id }) { item ->
            if (item.material.type == MaterialType.AUDIO) {
                MaterialRow(
                    item = item,
                    isActive = item.material.id == state.recorder.materialId,
                    isPlaying = item.material.id == playingId,
                    onPlay = { onPlay(item) },
                )
            } else {
                PhotoRow(item = item, onOpen = { onOpenPhoto(item) })
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ClassHeader(state: CaptureUiState, now: Long) {
    val task = state.task ?: return
    Column {
        Text(
            "${task.code} · ${task.teacherName} · 第 ${task.period} 节",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.classStartAt?.let { start ->
            Text(
                "课堂计时 ${formatDuration(now - start)}",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (!state.canCapture) {
            Text(
                "课堂采集已结束，只能查看和回放材料",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RecordingCard(
    state: CaptureUiState,
    durationMs: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val status = if (state.isRecordingHere) state.recorder.status else AudioRecorder.Status.IDLE
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("课堂录音", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val label = when (status) {
                    AudioRecorder.Status.RECORDING -> "录音中 ${formatDuration(durationMs)}"
                    AudioRecorder.Status.PAUSED -> "已暂停 ${formatDuration(durationMs)}"
                    AudioRecorder.Status.IDLE -> "未录音"
                }
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    color = if (status == AudioRecorder.Status.RECORDING) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    // 读屏只在状态切换时播报，不每秒播报时长
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = when (status) {
                            AudioRecorder.Status.RECORDING -> "录音中"
                            AudioRecorder.Status.PAUSED -> "录音已暂停"
                            AudioRecorder.Status.IDLE -> "未录音"
                        }
                    },
                )
            }

            if (state.isRecordingElsewhere) {
                Text(
                    "另一个任务正在录音，请先回到该任务结束录音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val buttonModifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                when (status) {
                    AudioRecorder.Status.IDLE -> Button(
                        onClick = onStart,
                        enabled = state.canCapture && !state.isRecordingElsewhere,
                        modifier = buttonModifier,
                    ) { Text("开始录音") }

                    AudioRecorder.Status.RECORDING -> {
                        OutlinedButton(onClick = onPause, modifier = buttonModifier) { Text("暂停") }
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = buttonModifier,
                        ) { Text("结束录音") }
                    }

                    AudioRecorder.Status.PAUSED -> {
                        OutlinedButton(onClick = onResume, modifier = buttonModifier) { Text("继续") }
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = buttonModifier,
                        ) { Text("结束录音") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialRow(item: MaterialItem, isActive: Boolean, isPlaying: Boolean, onPlay: () -> Unit) {
    val m = item.material
    val time = remember(m.capturedAt) { SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(m.capturedAt)) }
    val detail = buildString {
        append(time)
        append(" · 课堂第 ").append(formatDuration(m.offsetMs)).append(" 开始")
        m.durationMs?.let { append(" · 时长 ").append(formatDuration(it)) }
    }
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = {
            Text(
                when {
                    item.fileMissing -> "文件缺失"
                    isActive -> "正在录制…"
                    else -> detail
                },
                color = if (item.fileMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            // 录制中的那段还没写完，不能回放
            if (m.durationMs != null && !item.fileMissing && !isActive) {
                TextButton(onClick = onPlay) { Text(if (isPlaying) "停止" else "播放") }
            }
        },
    )
}

@Composable
private fun PhotoRow(item: MaterialItem, onOpen: () -> Unit) {
    val m = item.material
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = {
            Text(
                if (item.fileMissing) "文件缺失" else listOfNotNull(photoTimeLabel(m), m.note).joinToString(" · "),
                color = if (item.fileMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        },
        leadingContent = { if (!item.fileMissing) PhotoThumbnail(m) },
        // US09 主流程 3：点击查看大图
        modifier = Modifier.clickable(enabled = !item.fileMissing, onClickLabel = "查看大图", onClick = onOpen),
    )
}

/** 毫秒 → “mm:ss”，超过 1 小时为 “h:mm:ss”。 */
internal fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

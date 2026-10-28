package edu.neu.aijiaoxue.ui.capture

import edu.neu.aijiaoxue.ui.theme.AppBackAction

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.repository.MaterialTimeResolver
import edu.neu.aijiaoxue.ui.components.TaskContextHeader
import edu.neu.aijiaoxue.ui.components.WorkflowStepBar
import edu.neu.aijiaoxue.ui.components.SectionHeading
import edu.neu.aijiaoxue.ui.engagement.DeleteMaterialButton
import edu.neu.aijiaoxue.ui.engagement.DeleteMaterialDialog
import edu.neu.aijiaoxue.ui.engagement.EngagementCard
import edu.neu.aijiaoxue.ui.engagement.EngagementDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 课堂督导采集页（Routes.capture(taskId)）。
 * NF-01：录音（US08）、拍照（US09）、互动标注（US12）、参与状态（US13）都在本页一步触达；
 * 材料列表每行可删除无效材料（US11），组件在 ui/engagement。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onClassEnded: () -> Unit = {},
    onAnalysis: () -> Unit = {},
    viewModel: CaptureViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askLeave by rememberSaveable { mutableStateOf(false) }
    var askEndClass by rememberSaveable { mutableStateOf(false) }
    var showMicRationale by rememberSaveable { mutableStateOf(false) }
    var viewingPhotoId by rememberSaveable { mutableStateOf(0L) }
    var deletingEventId by rememberSaveable { mutableStateOf(0L) }
    var deletingMaterialId by rememberSaveable { mutableStateOf(0L) }
    var recordingEngagement by rememberSaveable { mutableStateOf(false) }
    val editingEventId by viewModel.editingEventId.collectAsState()
    val editingEventIsNew by viewModel.editingEventIsNew.collectAsState()
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
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAudio)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.task?.courseName ?: "课堂督导") },
                navigationIcon = { AppBackAction(leave) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!state.isLoading && state.blockedMessage == null) CaptureActionBar(
                state = state,
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                onStop = { viewModel.stopRecording() },
                onEndClass = { askEndClass = true },
                onAnalysis = onAnalysis,
            )
        },
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
                onImportAudio = { importAudio.launch(arrayOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4")) },
                onCancelImport = viewModel::cancelAudioImport,
                onPhotoError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                onOpenPhoto = { viewingPhotoId = it.material.id },
                onMarkEvent = viewModel::markEvent,
                onUndoEvent = viewModel::undoLastEvent,
                onEditEventNote = { viewModel.editEvent(it) },
                onDeleteEvent = { deletingEventId = it.id },
                onEndClass = { askEndClass = true },
                onAnalysis = onAnalysis,
                onRecordEngagement = { recordingEngagement = true },
                onDeleteMaterial = { deletingMaterialId = it.material.id },
                modifier = modifier,
            )
        }
    }

    // 结束后状态不可退回（规范第 4 节），所以要二次确认
    if (askEndClass) {
        AlertDialog(
            onDismissRequest = { askEndClass = false },
            title = { Text("结束课堂采集") },
            text = {
                Text(
                    buildString {
                        if (state.isRecordingHere) append("正在录音，将先结束录音并保存。")
                        append("结束后任务变为“待评价”，不能再现场录音和标注互动，仍可补充材料。接下来检查材料并准备分析。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askEndClass = false
                    viewModel.endClass(onEnded = onClassEnded)
                }) { Text("结束并检查材料") }
            },
            dismissButton = { TextButton(onClick = { askEndClass = false }) { Text("继续采集") } },
        )
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
            editable = state.canAddPhotos,
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

    // US12 主流程 3：填写备注；点“其他”后自动弹出
    state.events.firstOrNull { it.id == editingEventId }?.takeIf { state.canEditEvents }?.let { event ->
        InteractionNoteDialog(
            event = event,
            isNewOther = editingEventIsNew && event.type == InteractionType.OTHER,
            onSave = { note -> viewModel.saveEventNote(event, note) },
            onDismiss = viewModel::dismissEventDialog,
        )
    }

    // US12 主流程 4：删除误标事件
    state.events.firstOrNull { it.id == deletingEventId }?.takeIf { state.canEditEvents }?.let { event ->
        DeleteEventDialog(
            event = event,
            onConfirm = {
                deletingEventId = 0L
                viewModel.deleteEvent(event)
            },
            onDismiss = { deletingEventId = 0L },
        )
    }

    // US13：采集结束后关掉还开着的对话框，不能再新增
    if (recordingEngagement && state.canCapture) {
        EngagementDialog(
            onSave = { input ->
                recordingEngagement = false
                viewModel.recordEngagement(input)
            },
            onDismiss = { recordingEngagement = false },
        )
    }

    // US11：删除前二次确认
    state.materials.firstOrNull { it.material.id == deletingMaterialId }?.takeIf { state.canDeleteMaterials }?.let { item ->
        DeleteMaterialDialog(
            title = item.title,
            onConfirm = {
                deletingMaterialId = 0L
                viewModel.deleteMaterial(item)
            },
            onDismiss = { deletingMaterialId = 0L },
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
@OptIn(ExperimentalLayoutApi::class)
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
    onImportAudio: () -> Unit,
    onCancelImport: () -> Unit,
    onPhotoError: (String) -> Unit,
    onOpenPhoto: (MaterialItem) -> Unit,
    onMarkEvent: (InteractionType) -> Unit,
    onUndoEvent: () -> Unit,
    onEditEventNote: (Long) -> Unit,
    onDeleteEvent: (InteractionEvent) -> Unit,
    onEndClass: () -> Unit,
    onAnalysis: () -> Unit,
    onRecordEngagement: () -> Unit,
    onDeleteMaterial: (MaterialItem) -> Unit,
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

    var materialTab by rememberSaveable { mutableStateOf(0) }
    val audioCount = state.materials.count { it.material.type == MaterialType.AUDIO }
    val imageCount = state.materials.size - audioCount
    val filteredMaterials = state.materials.filter {
        when (materialTab) {
            1 -> it.material.type == MaterialType.AUDIO
            2 -> it.material.type != MaterialType.AUDIO
            3 -> false
            else -> true
        }
    }
    LazyColumn(
        modifier = modifier.testTag(CAPTURE_PAGE_TAG),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ClassHeader(state, now, onEndClass, onAnalysis) }
        item { WorkflowStepBar(1) }
        item { SectionHeading("采集工具", "材料即时保存，退出后可继续") }
        if (!state.isRecordingHere) item {
            RecordingCard(state, state.recorder.durationAt(elapsedNow), onStart, onPause, onResume, onStop)
        }
        item {
            AudioImportCard(state, onImportAudio, onCancelImport)
        }
        item {
            PhotoCaptureCard(
                enabled = state.canAddPhotos,
                onPreparePhoto = onPreparePhoto,
                onPhotoTaken = onPhotoTaken,
                onImport = onImport,
                onError = onPhotoError,
            )
        }
        item {
            InteractionPanel(
                enabled = state.canCapture, counts = state.eventCounts,
                undoable = state.undoableEvent, onMark = onMarkEvent,
                onUndo = onUndoEvent, onAddNote = onEditEventNote,
            )
        }
        // NF-01: the observation action remains directly reachable, regardless of the material filter.
        item { EngagementCard(records = state.engagements, enabled = state.canCapture, onRecord = onRecordEngagement) }
        item {
            SectionHeading("课堂材料", "音频 $audioCount 份 · 图片 $imageCount 份 · 互动 ${state.events.size} 条")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "音频 $audioCount", "图片 $imageCount", "课堂记录 ${state.events.size}").forEachIndexed { index, label ->
                    FilterChip(selected = materialTab == index, onClick = { materialTab = index }, label = { Text(label) }, modifier = Modifier.testTag("capture-material-filter-$index"))
                }
            }
        }
        if (materialTab == 0 || materialTab == 3) item {
            InteractionRecordCard(events = state.events, editable = state.canEditEvents, onEditNote = onEditEventNote, onDelete = onDeleteEvent)
        }
        if (filteredMaterials.isEmpty() && materialTab != 3) item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (materialTab == 0) "暂无材料" else if (materialTab == 1) "暂无音频材料" else "暂无图片材料", style = MaterialTheme.typography.titleSmall)
                    Text(if (materialTab == 2) "使用上方拍照或相册导入，保留本堂课的教学资料。" else "使用上方工具录制课堂或导入已有材料。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(filteredMaterials, key = { it.material.id }) { item ->
            val isActive = item.material.id == state.recorder.materialId
            val onDelete = if (state.canDeleteMaterials && !isActive) ({ onDeleteMaterial(item) }) else null
            if (item.material.type == MaterialType.AUDIO) {
                MaterialRow(item, isActive, item.material.id == playingId, { onPlay(item) }, onDelete)
            } else {
                PhotoRow(item, { onOpenPhoto(item) }, onDelete)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

internal const val CAPTURE_PAGE_TAG = "capture-page"

@Composable
private fun ClassHeader(state: CaptureUiState, now: Long, onEndClass: () -> Unit, onAnalysis: () -> Unit) {
    val task = state.task ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaskContextHeader(
            title = "课堂采集",
            subtitle = "${task.code} · ${task.teacherName} · 第 ${task.period} 节",
            status = state.status?.label,
        )
        state.classStartAt?.let { start ->
            Text("课堂计时 ${formatDuration((state.classEndAt ?: now) - start)}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
        if (state.status == TaskStatus.PENDING_EVALUATION) {
            Text("课堂采集已结束，待评价。仍可补充拍照", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!state.canCapture) {
            Text("课堂采集已结束，只能查看和回放材料", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                    .heightIn(min = 52.dp)
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
private fun MaterialRow(
    item: MaterialItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val m = item.material
    val time = remember(m.capturedAt) { SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(m.capturedAt)) }
    val detail = buildString {
        append(time)
        append(if (m.isImported) " · 导入音频" else " · 现场录制")
        MaterialTimeResolver.classroomOffsetMs(m)?.let { append(" · 课堂第 ").append(formatDuration(it)).append(" 开始") }
            ?: append(" · 课堂时间未知")
        m.durationMs?.let { append(" · 时长 ").append(formatDuration(it)) }
    }
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = {
            Text(
                when {
                    item.fileMissing -> "文件缺失"
                    isActive -> "正在录制…"
                    else -> "已保存 · $detail"
                },
                color = if (item.fileMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row {
                // 录制中的那段还没写完，不能回放
                if (m.durationMs != null && !item.fileMissing && !isActive) {
                    TextButton(onClick = onPlay) { Text(if (isPlaying) "停止" else "播放") }
                }
                onDelete?.let { DeleteMaterialButton(item.title, it) }
            }
        },
    )
}

@Composable
private fun PhotoRow(item: MaterialItem, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
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
        // US11：文件缺失的材料也能删掉，再重新拍
        trailingContent = { onDelete?.let { DeleteMaterialButton(item.title, it) } },
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

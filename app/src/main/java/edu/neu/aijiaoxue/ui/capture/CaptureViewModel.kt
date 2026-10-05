package edu.neu.aijiaoxue.ui.capture

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.dao.TaskRow
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 材料列表中的一项。fileMissing 时显示“文件缺失”（US07 异常处理）。 */
data class MaterialItem(
    val material: Material,
    val title: String,
    val fileMissing: Boolean,
)

/** US12：刚标注、5 秒内仍可撤销的事件。 */
data class UndoableEvent(
    val eventId: Long,
    val type: InteractionType,
    val offsetMs: Long,
)

data class CaptureUiState(
    val isLoading: Boolean = true,
    /** 任务不存在或不是本人任务时的提示，页面只显示这句话。 */
    val blockedMessage: String? = null,
    val task: TaskRow? = null,
    /** 课堂计时 0 点，所有 offsetMs 以此为准。 */
    val classStartAt: Long? = null,
    val status: TaskStatus? = null,
    /** 结束课堂采集时写入；之后课堂计时停在这里。 */
    val classEndAt: Long? = null,
    /** US08 前置条件：只有“待开始”“进行中”的任务可以采集。 */
    val canCapture: Boolean = false,
    /** 需求 3.3：“待评价”状态下仍可补充材料（拍照、相册导入），但不再录音和标注互动。 */
    val canAddPhotos: Boolean = false,
    val materials: List<MaterialItem> = emptyList(),
    val recorder: AudioRecorder.State = AudioRecorder.State(),
    /** US12 主流程 4：课堂结束（待评价）后仍可编辑备注、删除误标事件；评价提交后只读。 */
    val canEditEvents: Boolean = false,
    /** 按发生时间升序。 */
    val events: List<InteractionEvent> = emptyList(),
    /** US12 业务规则 3：各类型实时计数，没有记录的类型不在表里。 */
    val eventCounts: Map<InteractionType, Int> = emptyMap(),
    val undoableEvent: UndoableEvent? = null,
) {
    /** 当前录音属于本任务。 */
    val isRecordingHere: Boolean
        get() = recorder.status != AudioRecorder.Status.IDLE && recorder.taskId == task?.id

    /** 别的任务正在录音（从通知或返回栈进入了另一个任务）。 */
    val isRecordingElsewhere: Boolean
        get() = recorder.status != AudioRecorder.Status.IDLE && recorder.taskId != task?.id
}

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val taskId: Long = checkNotNull(savedStateHandle[Routes.ARG_TASK_ID])
    private val db = AppDatabase.get(application)
    private val taskDao = db.taskDao()
    private val captureDao = db.captureDao()

    val player = AudioPlayer()

    /**
     * US09：正在选择类型/备注的照片 id，0 表示没有。isNew 区分“刚拍完”和“从大图里修改”。
     * 存在 SavedStateHandle 里：调用系统相机时本进程可能被回收，回来后对话框和待保存的照片都不能丢。
     */
    val editingPhotoId: StateFlow<Long> = savedStateHandle.getStateFlow(KEY_EDIT_PHOTO, 0L)
    val editingPhotoIsNew: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_EDIT_PHOTO_NEW, false)

    /**
     * US12：正在填写备注的互动事件 id，0 表示没有。isNew 表示刚点了“其他”，需要补充具体内容。
     * 和照片一样存在 SavedStateHandle 里，旋转屏幕或进程被回收后对话框不丢。
     */
    val editingEventId: StateFlow<Long> = savedStateHandle.getStateFlow(KEY_EDIT_EVENT, 0L)
    val editingEventIsNew: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_EDIT_EVENT_NEW, false)

    private val access = MutableStateFlow<Access>(Access.Checking)

    private val localMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 一次性提示（Snackbar），包括录音被动停止时 AudioRecorder 发出的提示。 */
    val messages: Flow<String> = merge(localMessages, AudioRecorder.messages)

    private val undoableEvent = MutableStateFlow<UndoableEvent?>(null)
    private var undoJob: Job? = null
    private var undoDeadline = 0L

    /** 防止连点“结束课堂采集”重复执行。 */
    private var isEnding = false

    val uiState: StateFlow<CaptureUiState> = access.flatMapLatest { a ->
        when (a) {
            Access.Checking -> flowOf(CaptureUiState())
            is Access.Blocked -> flowOf(CaptureUiState(isLoading = false, blockedMessage = a.message))
            Access.Granted -> combine(
                taskDao.observeById(taskId),
                taskDao.observeRow(taskId),
                capturedData(),
                AudioRecorder.state,
                undoableEvent,
            ) { task, row, data, recorder, undoable ->
                CaptureUiState(
                    isLoading = false,
                    task = row,
                    classStartAt = task?.classStartAt,
                    status = task?.status,
                    classEndAt = task?.classEndAt,
                    canCapture = task?.status in CAPTURABLE,
                    canAddPhotos = task?.status in PHOTO_ADDABLE,
                    materials = data.materials,
                    recorder = recorder,
                    canEditEvents = task?.status in EVENT_EDITABLE,
                    events = data.events,
                    eventCounts = data.eventCounts,
                    undoableEvent = undoable,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureUiState())

    init {
        viewModelScope.launch { enter() }
    }

    /**
     * 进入采集页：校验归属 → 首次进入开始课堂计时 → 补写异常退出时未结束的录音。
     * F0 业务规则 2：不能只靠首页入口，直接按 id 进入也要校验是不是本人任务。
     */
    private suspend fun enter() {
        val user = Session.currentUser
        val task = taskDao.getById(taskId)
        when {
            task == null -> access.value = Access.Blocked("任务不存在")
            user == null || task.supervisorId != user.id -> access.value = Access.Blocked("无权限访问该任务")
            else -> {
                // 规范第 4 节：首次进入采集页 NOT_STARTED → IN_PROGRESS，写 classStartAt；再次进入不变
                if (task.status == TaskStatus.NOT_STARTED) taskDao.startClass(taskId, System.currentTimeMillis())
                recoverUnfinishedAudios(task)
                access.value = Access.Granted
            }
        }
    }

    /**
     * US07 业务规则 3：录音中进程被杀时 endedAt 为空。按文件实际时长补写；
     * 文件为空或无法解析（刚开始就被杀）时删除该段，避免列表里留下放不出来的录音。
     */
    private suspend fun recoverUnfinishedAudios(task: SupervisionTask) {
        val ctx = getApplication<Application>()
        val active = AudioRecorder.state.value.materialId
        for (m in captureDao.getUnfinishedAudios(task.id)) {
            if (m.id == active) continue
            val duration = AudioRecorder.probeDurationMs(FileStore.resolve(ctx, m.filePath))
            if (duration != null && duration > 0) {
                captureDao.updateMaterial(m.copy(endedAt = m.capturedAt + duration, durationMs = duration))
            } else {
                FileStore.delete(ctx, m.filePath)
                captureDao.deleteMaterial(m)
            }
        }
    }

    private fun materialItems() = captureDao.observeMaterials(taskId).map { list ->
        val ctx = getApplication<Application>()
        // 按类型分别编号：录音 1、PPT 1、板书 1……
        val counters = mutableMapOf<MaterialType, Int>()
        list.map { m ->
            val no = counters.merge(m.type, 1, Int::plus)
            val name = if (m.type == MaterialType.AUDIO) "录音" else m.type.label
            MaterialItem(m, "$name $no", fileMissing = !FileStore.exists(ctx, m.filePath))
        }
    }.flowOn(Dispatchers.IO)

    // US12 业务规则 3：计数从同一份事件列表算出，不另查 observeEventCounts，
    // 否则两条查询先后刷新时，删除后会短暂出现“列表已空、计数未减”
    private fun capturedData() = combine(
        materialItems(),
        captureDao.observeEvents(taskId),
    ) { materials, events ->
        CapturedData(materials, events, events.groupingBy { it.type }.eachCount())
    }

    fun startRecording() {
        val state = uiState.value
        val start = state.classStartAt ?: return
        if (!state.canCapture) return
        player.stop()
        viewModelScope.launch {
            AudioRecorder.start(getApplication(), taskId, start)?.let { localMessages.emit(it) }
        }
    }

    fun pauseRecording() {
        viewModelScope.launch { AudioRecorder.pause() }
    }

    fun resumeRecording() {
        viewModelScope.launch { AudioRecorder.resume() }
    }

    /** 结束录音；[then] 在保存完成后执行（如离开页面）。 */
    fun stopRecording(then: () -> Unit = {}) {
        viewModelScope.launch {
            val msg = AudioRecorder.stop()
            localMessages.emit(msg ?: "录音已保存")
            then()
        }
    }

    /**
     * 需求 3.3 课堂采集结束：仍在录音则先结束录音，再 IN_PROGRESS → PENDING_EVALUATION、写 classEndAt
     * （规范第 4 节），成功后 [onEnded] 进入评价页。
     */
    fun endClass(onEnded: () -> Unit) {
        if (uiState.value.status != TaskStatus.IN_PROGRESS || isEnding) return
        isEnding = true
        viewModelScope.launch {
            try {
                if (uiState.value.isRecordingHere) {
                    AudioRecorder.stop()?.let { localMessages.emit(it) }
                }
                player.stop()
                // 结束后不能再撤销：撤销入口只属于采集中
                undoJob?.cancel()
                undoableEvent.value = null
                // 只有 IN_PROGRESS 的任务会被更新；重复点击或已被结束时返回 0
                if (taskDao.endClass(taskId, System.currentTimeMillis()) > 0) {
                    onEnded()
                } else {
                    localMessages.emit("课堂采集已结束")
                }
            } finally {
                isEnding = false
            }
        }
    }

    fun togglePlay(item: MaterialItem) {
        if (item.fileMissing) return
        val ok = player.toggle(item.material.id, FileStore.resolve(getApplication(), item.material.filePath))
        if (!ok) localMessages.tryEmit("无法播放该音频")
    }

    /**
     * US09 主流程 1：拍照前先用 FileStore 建好目标文件，交给系统相机直接写入。
     * 返回 FileProvider Uri；不能采集时返回 null。
     */
    fun preparePhoto(): Uri? {
        if (!uiState.value.canAddPhotos) return null
        val ctx = getApplication<Application>()
        val (file, path) = FileStore.newPhotoFile(ctx, taskId)
        savedStateHandle[KEY_PENDING_PHOTO] = path
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    /** 相机返回。成功时立即写库（NF-03），再弹出类型选择；取消或失败时删掉空文件。 */
    fun onPhotoTaken(success: Boolean) {
        val path = savedStateHandle.remove<String>(KEY_PENDING_PHOTO) ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val file = FileStore.resolve(ctx, path)
            if (!success || withContext(Dispatchers.IO) { !file.exists() || file.length() == 0L }) {
                FileStore.delete(ctx, path)
                if (!success) localMessages.emit("未拍摄照片，可点击“拍照”重试")
                return@launch
            }
            // 业务规则 1：记录拍摄时间。相机写完文件的时间最接近按下快门的时间
            val takenAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            insertPhoto(path, takenAt, isImported = false)
        }
    }

    /** US09 业务规则 2：从相册补充导入，转存到任务目录，记录导入时间。 */
    fun importPhoto(uri: Uri) {
        if (!uiState.value.canAddPhotos) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val (file, path) = FileStore.newPhotoFile(ctx, taskId, now)
            val ok = withContext(Dispatchers.IO) { PhotoFiles.importAsJpeg(ctx, uri, file) }
            if (!ok) {
                FileStore.delete(ctx, path)
                localMessages.emit("无法读取该图片，请换一张重试")
                return@launch
            }
            insertPhoto(path, now, isImported = true)
        }
    }

    private suspend fun insertPhoto(path: String, at: Long, isImported: Boolean) {
        val start = taskDao.getById(taskId)?.classStartAt ?: at
        val id = captureDao.insertMaterial(
            Material(
                taskId = taskId,
                // 课堂上拍得最多的是 PPT，先按 PPT 保存，对话框里可改
                type = MaterialType.PPT,
                filePath = path,
                capturedAt = at,
                offsetMs = at - start,
                isImported = isImported,
            ),
        )
        editPhoto(id, isNew = true)
    }

    fun editPhoto(materialId: Long, isNew: Boolean = false) {
        savedStateHandle[KEY_EDIT_PHOTO_NEW] = isNew
        savedStateHandle[KEY_EDIT_PHOTO] = materialId
    }

    fun dismissPhotoDialog() {
        savedStateHandle[KEY_EDIT_PHOTO] = 0L
    }

    /** US09 主流程 2：保存材料类型和备注。备注为空存 null（规范 2.2）。 */
    fun savePhoto(material: Material, type: MaterialType, note: String) {
        dismissPhotoDialog()
        viewModelScope.launch {
            captureDao.updateMaterial(material.copy(type = type, note = note.trim().ifEmpty { null }))
        }
    }

    /**
     * US12 主流程 1、2：一键标注，立即写库（NF-03），自动记录绝对时间和课堂偏移。
     * 业务规则 2：标注本身不等待任何对话框，点击即入库。
     * “其他”入库后再弹出填写具体内容的对话框，跳过也不影响已记录的事件和时间。
     */
    fun markEvent(type: InteractionType) {
        val state = uiState.value
        val start = state.classStartAt ?: return
        if (!state.canCapture) return
        // 取点击时刻，不取写库完成时刻
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            val id = captureDao.insertEvent(
                InteractionEvent(taskId = taskId, type = type, occurredAt = now, offsetMs = now - start),
            )
            offerUndo(UndoableEvent(id, type, now - start))
            if (type == InteractionType.OTHER) editEvent(id, isNew = true)
        }
    }

    fun editEvent(eventId: Long, isNew: Boolean = false) {
        if (!uiState.value.canEditEvents) return
        savedStateHandle[KEY_EDIT_EVENT_NEW] = isNew
        savedStateHandle[KEY_EDIT_EVENT] = eventId
    }

    fun dismissEventDialog() {
        savedStateHandle[KEY_EDIT_EVENT] = 0L
    }

    /** 事件被撤销或删除时，关掉还开着的备注对话框。 */
    private fun closeDialogFor(eventId: Long) {
        if (editingEventId.value == eventId) dismissEventDialog()
    }

    /** 连续标注时只能撤销最近一条，每次标注重新计时 5 秒。 */
    private fun offerUndo(event: UndoableEvent) {
        undoJob?.cancel()
        undoDeadline = SystemClock.elapsedRealtime() + UNDO_WINDOW_MS
        undoableEvent.value = event
        undoJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            undoableEvent.value = null
        }
    }

    /** US12 异常处理：误触 5 秒内可撤销；超时只能去互动记录里删除。 */
    fun undoLastEvent() {
        val event = undoableEvent.value ?: return
        undoJob?.cancel()
        undoableEvent.value = null
        // 计时协程可能稍晚于截止时间才清掉入口，这里再按时钟校验一次
        if (SystemClock.elapsedRealtime() > undoDeadline) return
        closeDialogFor(event.eventId)
        viewModelScope.launch {
            captureDao.deleteEventById(event.eventId)
            localMessages.emit("已撤销“${event.type.label}”")
        }
    }

    /** US12 主流程 3、4：补充或修改备注。备注为空存 null（规范 2.2）。 */
    fun saveEventNote(event: InteractionEvent, note: String) {
        dismissEventDialog()
        if (!uiState.value.canEditEvents) return
        viewModelScope.launch {
            captureDao.updateEvent(event.copy(note = note.trim().ifEmpty { null }))
        }
    }

    /** US12 主流程 4：在互动记录中删除误标事件。 */
    fun deleteEvent(event: InteractionEvent) {
        if (!uiState.value.canEditEvents) return
        if (undoableEvent.value?.eventId == event.id) {
            undoJob?.cancel()
            undoableEvent.value = null
        }
        closeDialogFor(event.id)
        viewModelScope.launch {
            captureDao.deleteEvent(event)
            localMessages.emit("已删除该互动记录")
        }
    }

    override fun onCleared() {
        player.stop()
    }

    private data class CapturedData(
        val materials: List<MaterialItem>,
        val events: List<InteractionEvent>,
        val eventCounts: Map<InteractionType, Int>,
    )

    private sealed interface Access {
        data object Checking : Access
        data object Granted : Access
        data class Blocked(val message: String) : Access
    }

    private companion object {
        val CAPTURABLE = setOf(TaskStatus.NOT_STARTED, TaskStatus.IN_PROGRESS)
        val EVENT_EDITABLE = CAPTURABLE + TaskStatus.PENDING_EVALUATION
        val PHOTO_ADDABLE = CAPTURABLE + TaskStatus.PENDING_EVALUATION
        const val UNDO_WINDOW_MS = 5_000L
        const val KEY_PENDING_PHOTO = "pendingPhotoPath"
        const val KEY_EDIT_PHOTO = "editPhotoId"
        const val KEY_EDIT_PHOTO_NEW = "editPhotoNew"
        const val KEY_EDIT_EVENT = "editEventId"
        const val KEY_EDIT_EVENT_NEW = "editEventNew"
    }
}

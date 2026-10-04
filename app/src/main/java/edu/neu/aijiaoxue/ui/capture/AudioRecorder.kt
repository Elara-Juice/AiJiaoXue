package edu.neu.aijiaoxue.ui.capture

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.MaterialType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * US08 课堂录音。整个进程只有一个录音，状态不随页面销毁丢失。
 *
 * - 录音期间由 [RecordingService] 保持前台，锁屏、切后台、调用系统相机拍照（US09）时不中断（NF-04、US07 业务规则 3）。
 * - 每段录音开始时立即写入 Material（endedAt 为空），结束时补写结束时间和有效时长（NF-03）。
 *   进程被杀时 endedAt 留空，下次进入采集页由 CaptureViewModel 补写。
 * - 格式 m4a（AAC 单声道 64kbps），45 分钟约 22MB，满足 Sprint 2 转写要求。
 */
object AudioRecorder {
    enum class Status { IDLE, RECORDING, PAUSED }

    data class State(
        val status: Status = Status.IDLE,
        val taskId: Long? = null,
        val materialId: Long? = null,
        /** 本段在当前这一截之前已录的时长（不含暂停）。 */
        val recordedMs: Long = 0,
        /** 当前这一截开始时的 elapsedRealtime；暂停或未录音时为 null。 */
        val runningSince: Long? = null,
    ) {
        /** 本段有效时长，不含暂停（US08 业务规则 1）。 */
        fun durationAt(elapsedNow: Long): Long = recordedMs + (runningSince?.let { elapsedNow - it } ?: 0)
    }

    /** 剩余空间低于此值不允许开始录音。 */
    private const val MIN_FREE_BYTES = 20L * 1024 * 1024
    /** 录音文件最多写到“剩余空间 - 预留”，写满后自动停止并保存（US08 异常处理）。 */
    private const val RESERVE_BYTES = 10L * 1024 * 1024
    private const val SAMPLE_RATE = 44_100
    private const val BIT_RATE = 64_000

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 录音被动停止（空间不足、出错）时的提示，由采集页显示。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var recorder: MediaRecorder? = null
    private var material: Material? = null
    private var file: File? = null
    private var appContext: Context? = null

    /** 开始一段新录音。成功返回 null，失败返回提示文字。classStartAt 用于计算 offsetMs。 */
    suspend fun start(context: Context, taskId: Long, classStartAt: Long): String? = mutex.withLock {
        if (_state.value.status != Status.IDLE) return "已有录音正在进行"
        val ctx = context.applicationContext
        appContext = ctx
        val usable = ctx.filesDir.usableSpace
        if (usable < MIN_FREE_BYTES) return "存储空间不足，无法开始录音"

        val now = System.currentTimeMillis()
        val (out, path) = FileStore.newAudioFile(ctx, taskId, now)
        val r = newRecorder(ctx, out, usable - RESERVE_BYTES)
        try {
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            return "无法开始录音，请检查麦克风是否被其他应用占用"
        }

        val m = Material(
            taskId = taskId,
            type = MaterialType.AUDIO,
            filePath = path,
            capturedAt = now,
            offsetMs = now - classStartAt,
        )
        val id = AppDatabase.get(ctx).captureDao().insertMaterial(m)
        recorder = r
        file = out
        material = m.copy(id = id)
        _state.value = State(Status.RECORDING, taskId, id, 0, SystemClock.elapsedRealtime())
        startService(ctx)
        null
    }

    suspend fun pause() = mutex.withLock {
        val r = recorder ?: return@withLock
        if (_state.value.status != Status.RECORDING) return@withLock
        r.pause()
        _state.update {
            it.copy(status = Status.PAUSED, recordedMs = it.durationAt(SystemClock.elapsedRealtime()), runningSince = null)
        }
    }

    suspend fun resume() = mutex.withLock {
        val r = recorder ?: return@withLock
        if (_state.value.status != Status.PAUSED) return@withLock
        r.resume()
        _state.update { it.copy(status = Status.RECORDING, runningSince = SystemClock.elapsedRealtime()) }
    }

    /** 结束本段录音并保存。成功返回 null；录音太短无法生成有效文件时删除该段并返回提示。 */
    suspend fun stop(): String? = mutex.withLock { stopLocked() }

    private suspend fun stopLocked(): String? {
        val r = recorder ?: return null
        val m = checkNotNull(material)
        val out = checkNotNull(file)
        val ctx = checkNotNull(appContext)
        val duration = _state.value.durationAt(SystemClock.elapsedRealtime())
        recorder = null
        material = null
        file = null

        val stopped = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // 录音不足约 1 秒或写满后已自动停止时 stop() 会抛异常，下面按文件实际情况判断
            false
        } finally {
            r.release()
        }
        _state.value = State()
        ctx.stopService(Intent(ctx, RecordingService::class.java))

        val dao = AppDatabase.get(ctx).captureDao()
        val probed = if (stopped) null else probeDurationMs(out)
        return if (stopped || (probed != null && probed > 0)) {
            dao.updateMaterial(m.copy(endedAt = System.currentTimeMillis(), durationMs = probed ?: duration))
            null
        } else {
            FileStore.delete(ctx, m.filePath)
            dao.deleteMaterial(m)
            "录音时间过短，未保存"
        }
    }

    /** 读取音频文件实际时长；文件损坏或不可读时返回 null。 */
    suspend fun probeDurationMs(file: File): Long? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun newRecorder(context: Context, out: File, maxBytes: Long): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(SAMPLE_RATE)
        r.setAudioEncodingBitRate(BIT_RATE)
        r.setMaxFileSize(maxBytes)
        r.setOutputFile(out.absolutePath)
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                stopPassively("存储空间不足，录音已停止，已录部分已保存")
            }
        }
        r.setOnErrorListener { _, _, _ -> stopPassively("录音出错，已录部分已保存") }
        return r
    }

    private fun stopPassively(message: String) {
        scope.launch {
            val result = mutex.withLock { stopLocked() }
            _messages.emit(result ?: message)
        }
    }

    private fun startService(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        } catch (e: IllegalStateException) {
            // 系统不允许启动前台服务时，录音在前台仍可继续，只是切后台后可能被静音
        }
    }
}

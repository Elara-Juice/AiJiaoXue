package edu.neu.aijiaoxue.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.*
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.capture.AudioImportInspector
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class AudioImportRepository(private val db: AppDatabase, private val context: Context) {
    suspend fun importAudio(actorId: Long, taskId: Long, uri: Uri, requestKey: String,
                            maxBytes: Long = 262_144_000, maxDurationMs: Long = 7_200_000,
                            onProgress: (Float?) -> Unit = {}): Long = locks.getOrPut(requestKey) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            suspend fun authorize() {
                val task = requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权导入本课堂录音" }
                require(task.status in setOf(TaskStatus.IN_PROGRESS, TaskStatus.PENDING_EVALUATION)) { "当前课堂不能添加材料" }
            }
            authorize()
            val stem = "tasks/$taskId/audio/IMP_$requestKey."
            db.captureDao().getMaterials(taskId).firstOrNull { it.filePath.startsWith(stem) }?.let { return@withContext it.id }
            val (temporary, _) = FileStore.importedAudioFile(context, taskId, requestKey, "part")
            var destination: File? = null
            var relativePath: String? = null
            try {
                val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
                }
                val limit = minOf(262_144_000L, maxBytes)
                require(size == null || size <= limit) { "音频超过上传大小限制" }
                val hash = MessageDigest.getInstance("SHA-256")
                var total = 0L
                requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取选择的音频" }.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= limit) { "音频超过上传大小限制" }
                            output.write(buffer, 0, n); hash.update(buffer, 0, n)
                            onProgress(size?.takeIf { it > 0 }?.let { (total.toFloat() / it).coerceIn(0f, 1f) })
                        }
                    }
                }
                require(total > 0) { "音频文件为空" }
                val info = AudioImportInspector.inspect(temporary)
                require(info.durationMs <= minOf(7_200_000L, maxDurationMs)) { "音频超过服务允许时长" }
                val (target, path) = FileStore.importedAudioFile(context, taskId, requestKey, info.extension)
                destination = target; relativePath = path
                if (target.exists()) {
                    val expected = hash.digest().joinToString("") { "%02x".format(it) }
                    require(edu.neu.aijiaoxue.data.ai.AnalysisInputs.hashFile(target) == expected) { "导入请求标识已用于其他文件" }
                } else check(temporary.renameTo(target)) { "无法保存导入文件" }
                coroutineContext.ensureActive()
                db.withTransaction {
                    authorize()
                    db.captureDao().getMaterials(taskId).firstOrNull { it.filePath == path }?.id ?: run {
                        val id = db.captureDao().insertMaterial(Material(taskId = taskId, type = MaterialType.AUDIO,
                            filePath = path, capturedAt = System.currentTimeMillis(), offsetMs = 0,
                            durationMs = info.durationMs, endedAt = null, isImported = true))
                        db.analysisSourceDao().bumpInputRevision(taskId)
                        id
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    temporary.delete()
                    if (destination != null && db.captureDao().getMaterials(taskId).none { it.filePath == relativePath }) destination?.delete()
                }
            }
        }
    }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}

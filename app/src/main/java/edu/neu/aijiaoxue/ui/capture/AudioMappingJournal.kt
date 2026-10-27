package edu.neu.aijiaoxue.ui.capture

import android.content.Context
import android.util.AtomicFile
import edu.neu.aijiaoxue.data.entity.AudioTimeSpan
import edu.neu.aijiaoxue.data.model.TimeMappingQuality
import edu.neu.aijiaoxue.data.repository.RecordedTimeSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** 暂停映射随每次操作刷盘；崩溃后只恢复已闭合段，不猜测未记录的结束时间。 */
internal class AudioMappingJournal(private val context: Context, val materialId: Long, private val classOffset: Long, private val baseElapsed: Long) {
    private val closed = mutableListOf<RecordedTimeSpan>()
    private var activeSince: Long? = baseElapsed
    private var activeClassStart = classOffset
    suspend fun start() = persist()
    suspend fun pause(elapsed: Long) { close(elapsed); persist() }
    suspend fun resume(elapsed: Long) { activeSince = elapsed; activeClassStart = classOffset + elapsed - baseElapsed; persist() }
    suspend fun finish(elapsed: Long, durationMs: Long): List<AudioTimeSpan> { close(elapsed); persist(); return normalize(materialId, closed, durationMs) }
    private fun close(elapsed: Long) {
        val start = activeSince ?: return
        val length = elapsed - start
        if (length > 0) {
            val mediaStart = closed.lastOrNull()?.mediaEndMs ?: 0
            closed += RecordedTimeSpan(mediaStart, mediaStart + length, activeClassStart, activeClassStart + length)
        }
        activeSince = null
    }
    private suspend fun persist() = withContext(Dispatchers.IO) {
        val json = JSONObject().put("materialId", materialId).put("hasUnclosedSpan", activeSince != null).put("closedSpans", JSONArray().apply { closed.forEach { s -> put(JSONObject().put("mediaStartMs", s.mediaStartMs).put("mediaEndMs", s.mediaEndMs).put("classStartMs", s.classStartMs).put("classEndMs", s.classEndMs)) } })
        val atomic = AtomicFile(path(context, materialId))
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    companion object {
        private fun path(context: Context, id: Long): File = File(context.filesDir, "recording_sessions/$id.json").also { it.parentFile?.mkdirs() }
        suspend fun clear(context: Context, id: Long) = withContext(Dispatchers.IO) { AtomicFile(path(context, id)).delete() }
        suspend fun recover(context: Context, id: Long, durationMs: Long): List<AudioTimeSpan> = withContext(Dispatchers.IO) {
            val file = path(context, id)
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val json = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
                require(json.getLong("materialId") == id)
                val a = json.getJSONArray("closedSpans")
                (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { s ->
                    val ms = s.getLong("mediaStartMs"); val me = s.getLong("mediaEndMs")
                    val cs = s.getLong("classStartMs"); val ce = s.getLong("classEndMs")
                    if (ms >= 0 && me > ms && me <= durationMs && cs >= 0 && ce > cs) AudioTimeSpan(materialId = id, ordinal = i, mediaStartMs = ms, mediaEndMs = me, classStartMs = cs, classEndMs = ce, mappingQuality = TimeMappingQuality.ESTIMATED) else null
                } }
            }.getOrDefault(emptyList())
        }
        fun normalize(id: Long, spans: List<RecordedTimeSpan>, actualDuration: Long): List<AudioTimeSpan> {
            val recorded = spans.lastOrNull()?.mediaEndMs ?: return emptyList()
            if (recorded <= 0 || actualDuration <= 0) return emptyList()
            val quality = if (abs(recorded - actualDuration) <= 500) TimeMappingQuality.EXACT else TimeMappingQuality.ESTIMATED
            return spans.mapIndexedNotNull { i, s ->
                val from = (s.mediaStartMs.toDouble() * actualDuration / recorded).toLong()
                val to = (s.mediaEndMs.toDouble() * actualDuration / recorded).toLong()
                if (to <= from) null else AudioTimeSpan(materialId = id, ordinal = i, mediaStartMs = from, mediaEndMs = to, classStartMs = s.classStartMs, classEndMs = s.classEndMs, mappingQuality = quality)
            }
        }
    }
}

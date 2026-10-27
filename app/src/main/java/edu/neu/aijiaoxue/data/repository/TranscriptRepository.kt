package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.TranscriptVersion
import edu.neu.aijiaoxue.data.entity.TermExclusionVersion
import edu.neu.aijiaoxue.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/** 云端只提供候选文本；说话角色只能由人工显式确认。每次校订追加版本。 */
class TranscriptRepository(private val db: AppDatabase) {
    suspend fun list(actorId: Long, taskId: Long): List<TranscriptVersion> = db.withTransaction {
        requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权查看课堂转写" }
        db.analysisSourceDao().transcripts(taskId)
    }

    suspend fun saveGenerated(actorId: Long, taskId: Long, materialId: Long, payloadJson: String, fileHash: String, provider: String, model: String, methodVersion: String, state: ResultState = ResultState.SUPPORTED): Long = db.withTransaction {
        val dao = db.analysisSourceDao()
        requireNotNull(dao.ownedTask(actorId, taskId)) { "无权保存课堂转写" }
        val material = requireNotNull(db.captureDao().getMaterial(materialId)) { "录音已被删除" }
        require(material.taskId == taskId && material.type == MaterialType.AUDIO) { "转写录音不属于此课堂" }
        val duration = requireNotNull(material.durationMs) { "录音尚未结束" }
        val payload = validateAndMap(payloadJson, duration, dao.audioSpans(materialId).map { RecordedTimeSpan(it.mediaStartMs, it.mediaEndMs, it.classStartMs, it.classEndMs, it.mappingQuality) }, manual = false)
        dao.deactivateTranscripts(materialId)
        val id = dao.insertTranscript(TranscriptVersion(taskId = taskId, materialId = materialId, revision = dao.transcriptRevision(materialId) + 1, fileHash = fileHash, payloadJson = payload, provider = provider, model = model, methodVersion = methodVersion, state = state, contentHash = InputSourceRules.hash(payload), createdAt = System.currentTimeMillis()))
        dao.bumpInputRevision(taskId)
        id
    }

    suspend fun correctSegment(actorId: Long, taskId: Long, versionId: Long, segmentId: String, text: String, speakerRole: SpeakerRole): Long = db.withTransaction {
        val dao = db.analysisSourceDao()
        requireNotNull(dao.ownedTask(actorId, taskId)) { "无权校订课堂转写" }
        val old = requireNotNull(dao.transcript(versionId)) { "转写版本不存在" }
        require(old.taskId == taskId && old.isCurrent) { "转写已更新，请刷新后校订" }
        require(text.isNotBlank()) { "转写正文不能为空" }
        val json = JSONObject(old.payloadJson)
        val segments = json.getJSONArray("segments")
        var found = false
        for (i in 0 until segments.length()) {
            val segment = segments.getJSONObject(i)
            if (segment.getString("segmentId") == segmentId) {
                // 改写文本后原词级时间不再逐词对应；保留整句真实声学范围。
                if (segment.getString("text") != text.trim()) segment.remove("words")
                segment.put("text", text.trim()).put("speakerRole", speakerRole.name).put("roleSource", "MANUAL")
                found = true
            }
        }
        require(found) { "句段不存在" }
        val material = requireNotNull(db.captureDao().getMaterial(old.materialId)) { "原录音已被删除" }
        val payload = validateAndMap(json.toString(), requireNotNull(material.durationMs), dao.audioSpans(old.materialId).map { RecordedTimeSpan(it.mediaStartMs, it.mediaEndMs, it.classStartMs, it.classEndMs, it.mappingQuality) }, manual = true)
        dao.deactivateTranscripts(old.materialId)
        val id = dao.insertTranscript(old.copy(id = 0, revision = dao.transcriptRevision(old.materialId) + 1, payloadJson = payload, contentHash = InputSourceRules.hash(payload), methodVersion = "manual_transcript_revision_v1", createdAt = System.currentTimeMillis()))
        dao.bumpInputRevision(taskId)
        id
    }

    suspend fun saveTermExclusions(actorId: Long, taskId: Long, professionalTerms: List<String>, ignoredExpressions: List<String>): Long = db.withTransaction {
        val dao = db.analysisSourceDao()
        val task = requireNotNull(dao.ownedTask(actorId, taskId)) { "无权编辑术语排除" }
        val payload = JSONObject().put("schemaVersion", 1).put("professionalTerms", JSONArray(professionalTerms.map(String::trim).filter(String::isNotEmpty).distinct())).put("ignoredExpressions", JSONArray(ignoredExpressions.map(String::trim).filter(String::isNotEmpty).distinct())).toString()
        val id = dao.insertTermExclusions(TermExclusionVersion(courseId = task.courseId, revision = (dao.termExclusions(task.courseId)?.revision ?: 0) + 1, payloadJson = payload, contentHash = InputSourceRules.hash(payload), createdBy = actorId, createdAt = System.currentTimeMillis()))
        dao.bumpInputRevision(taskId)
        id
    }

    companion object {
        fun validateAndMap(text: String, durationMs: Long, spans: List<RecordedTimeSpan>, manual: Boolean): String {
            val json = JSONObject(text)
            require(json.optInt("schemaVersion") == 1) { "转写格式版本无效" }
            val segments = json.getJSONArray("segments")
            val ids = mutableSetOf<String>()
            for (i in 0 until segments.length()) {
                val s = segments.getJSONObject(i)
                require(s.get("mediaStartMs") is Int || s.get("mediaStartMs") is Long) { "转写时间必须是整数毫秒" }
                require(s.get("mediaEndMs") is Int || s.get("mediaEndMs") is Long) { "转写时间必须是整数毫秒" }
                val start = s.getLong("mediaStartMs")
                val end = s.getLong("mediaEndMs")
                require(start >= 0 && end > start && end <= durationMs) { "转写时间超出真实录音范围" }
                require(s.getString("segmentId").isNotBlank() && ids.add(s.getString("segmentId"))) { "转写句段编号重复" }
                require(s.getString("text").isNotBlank()) { "转写不能保存空句段" }
                s.optJSONArray("words")?.let { words ->
                    for (wordIndex in 0 until words.length()) {
                        val word = words.getJSONObject(wordIndex)
                        require(word.opt("startMs") is Int || word.opt("startMs") is Long) { "词级开始时间必须为整数毫秒" }
                        require(word.opt("endMs") is Int || word.opt("endMs") is Long) { "词级结束时间必须为整数毫秒" }
                        val wordStart = word.getLong("startMs"); val wordEnd = word.getLong("endMs")
                        require(word.getString("text").isNotBlank() && wordStart >= start && wordEnd > wordStart && wordEnd <= end) { "词级时间不属于原句段" }
                    }
                }
                val role = if (manual) SpeakerRole.valueOf(s.optString("speakerRole", "UNKNOWN")) else SpeakerRole.UNKNOWN
                s.put("speakerRole", role.name)
                val ranges = InputSourceRules.mapMediaRange(start, end, spans)
                s.put("classRanges", JSONArray().apply { ranges.forEach { put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs)) } })
                val coverage = spans.filter { it.quality != TimeMappingQuality.UNKNOWN }.sumOf { maxOf(0L, minOf(end, it.mediaEndMs) - maxOf(start, it.mediaStartMs)) }
                val quality = if (coverage < end - start) TimeMappingQuality.UNKNOWN else if (spans.any { it.quality == TimeMappingQuality.ESTIMATED && it.mediaStartMs < end && it.mediaEndMs > start }) TimeMappingQuality.ESTIMATED else TimeMappingQuality.EXACT
                s.put("mappingQuality", quality.name)
            }
            return json.toString()
        }
    }
}

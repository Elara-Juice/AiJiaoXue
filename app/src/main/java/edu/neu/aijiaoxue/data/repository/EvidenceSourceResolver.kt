package edu.neu.aijiaoxue.data.repository

import android.content.Context
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.AnalysisInputs
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

sealed interface ResolvedEvidenceSource {
    data class Image(val file: File, val description: String) : ResolvedEvidenceSource
    data class Audio(val file: File, val mediaStartMs: Long, val description: String, val mediaEndMs: Long? = null) : ResolvedEvidenceSource
    data class Detail(val title: String, val text: String) : ResolvedEvidenceSource
    data class SummaryOnly(val summary: String, val reason: String) : ResolvedEvidenceSource
}

/** 路由只传记录 ID。教师收到摘要 DTO，任何路径均不会越过解析器的二次授权。 */
class EvidenceSourceResolver(private val context: Context, private val db: AppDatabase) {
    suspend fun openCurrent(actorId: Long, taskId: Long, evidenceId: Long): ResolvedEvidenceSource {
        val evidence = requireNotNull(db.analysisSourceDao().currentEvidenceForActor(actorId, taskId, evidenceId)) { "证据不存在或无权访问" }
        val ref = evidence.sourceRefJson ?: evidence.materialId?.let { JSONObject().put("kind", "MATERIAL").put("taskId", taskId).put("rowId", it).toString() }
            ?: evidence.eventId?.let { JSONObject().put("kind", "INTERACTION_EVENT").put("taskId", taskId).put("rowId", it).toString() }
        return resolveOrSummary(taskId, ref, evidence.description)
    }

    suspend fun openPublished(actorId: Long, taskId: Long, snapshotEvidenceId: Long): ResolvedEvidenceSource {
        val evidence = requireNotNull(db.analysisSourceDao().publishedEvidenceForActor(actorId, taskId, snapshotEvidenceId)) { "正式证据不存在或无权访问" }
        val actor = requireNotNull(db.userDao().getById(actorId))
        if (actor.role == Role.TEACHER) return ResolvedEvidenceSource.SummaryOnly(evidence.excerpt, "教师反馈仅展示已发布摘要，不开放原始录音和图片")
        return resolveOrSummary(taskId, evidence.sourceRefJson, evidence.excerpt, published = true)
    }

    suspend fun openSource(actorId: Long, taskId: Long, sourceRefJson: String): ResolvedEvidenceSource {
        requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权访问本课堂原始来源" }
        return resolveOrSummary(taskId, sourceRefJson, JSONObject(sourceRefJson).optString("quote"))
    }

    private suspend fun resolveOrSummary(taskId: Long, json: String?, summary: String, published: Boolean = false): ResolvedEvidenceSource {
        if (json == null) return ResolvedEvidenceSource.SummaryOnly(summary, "旧记录没有可打开的原始来源")
        return try { resolve(taskId, JSONObject(json), 0, published) }
        catch (e: IllegalArgumentException) { ResolvedEvidenceSource.SummaryOnly(summary, e.message ?: "来源已失效") }
        catch (e: org.json.JSONException) { ResolvedEvidenceSource.SummaryOnly(summary, "来源格式无效，保留原摘要") }
        catch (e: java.io.IOException) { ResolvedEvidenceSource.SummaryOnly(summary, "原文件当前不可读，保留原摘要") }
    }

    private suspend fun resolve(taskId: Long, ref: JSONObject, depth: Int, published: Boolean): ResolvedEvidenceSource {
        require(depth < 8) { "来源引用过深，请查看原事实记录" }
        val dao = db.analysisSourceDao()
        val id = ref.getLong("rowId")
        val expectedHash = ref.optString("contentHash").takeIf { it.isNotBlank() && it != "null" }
        fun verify(hash: String) { require(expectedHash == null || expectedHash == hash) { "来源内容已变化，保留已发布摘要" } }
        if (ref.getString("kind") != "COURSE_REFERENCE") validateSourceOwnership(db, taskId, ref)
        return when (ref.getString("kind")) {
            "MATERIAL" -> {
                val material = requireNotNull(db.captureDao().getMaterial(id)) { "原文件记录已删除" }
                val file = resolveMaterialFile(context, taskId, material)
                if (expectedHash != null) verify(fileHash(file))
                if (material.type == MaterialType.AUDIO) {
                    val start = ref.optJSONObject("mediaRange")?.getLong("startMs") ?: 0
                    require(start >= 0 && (material.durationMs == null || start < material.durationMs)) { "原音频定位已失效" }
                    ResolvedEvidenceSource.Audio(file, start, material.note ?: "课堂音频 · 采集时间 ${material.capturedAt}")
                } else ResolvedEvidenceSource.Image(file, material.note ?: "课堂图片 · 采集时间 ${material.capturedAt}")
            }
            "TRANSCRIPT_SEGMENT" -> {
                val version = requireNotNull(dao.transcript(id)) { "转写版本已删除" }
                verify(version.contentHash)
                val array = JSONObject(version.payloadJson).getJSONArray("segments")
                val segment = (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.getString("segmentId") == ref.getString("itemKey") }
                requireNotNull(segment) { "转写句段不存在" }
                val material = requireNotNull(db.captureDao().getMaterial(version.materialId)) { "原音频已删除" }
                val file = resolveMaterialFile(context, taskId, material)
                require(fileHash(file) == version.fileHash) { "原音频文件已变化" }
                ResolvedEvidenceSource.Audio(file, segment.getLong("mediaStartMs"), segment.getString("text"), segment.getLong("mediaEndMs"))
            }
            "INTERACTION_EVENT" -> requireNotNull(dao.event(taskId, id)).let { verify(AnalysisJson.hashJson(AnalysisInputs.eventJson(it))); ResolvedEvidenceSource.Detail("互动事件", "${it.type.label} · 课堂 ${it.offsetMs / 1000} 秒\n${it.note.orEmpty()}") }
            "ENGAGEMENT_RECORD" -> requireNotNull(dao.engagement(taskId, id)).let { ResolvedEvidenceSource.Detail("参与记录", "${it.type.label} · 课堂 ${it.offsetMs / 1000} 秒\n到场 ${it.presentCount ?: "未填"}，参与 ${it.engagedCount ?: "未填"}，比例 ${it.engagedPercent ?: "未填"}%\n${it.description.orEmpty()}") }
            "MANUAL_PHASE" -> {
                val version = requireNotNull(dao.phases(id))
                verify(version.contentHash)
                val segment = TeachingPhaseRepository.decode(version.payloadJson).firstOrNull { it.id == ref.getString("itemKey") }
                requireNotNull(segment) { "教学环节不存在" }
                ResolvedEvidenceSource.Detail("人工教学环节 v${version.revision}", "${segment.label} · ${segment.startMs / 1000}–${segment.endMs / 1000} 秒\n${segment.note}\n确认人 ${version.confirmedBy ?: "未确认"}，确认时间 ${version.confirmedAt ?: "未确认"}")
            }
            "IMAGE_TEXT" -> {
                val version = requireNotNull(dao.imageText(id))
                verify(version.contentHash)
                val material = requireNotNull(db.captureDao().getMaterial(version.materialId)) { "原图片已删除" }
                val file = resolveMaterialFile(context, taskId, material)
                require(fileHash(file) == version.fileHash) { "原图片已变化" }
                ResolvedEvidenceSource.Image(file, JSONObject(version.payloadJson).toString(2))
            }
            "MANUAL_FACT" -> {
                val fact = requireNotNull(dao.fact(id))
                verify(AnalysisJson.hash(fact.description))
                val suffix = fact.sourceRefJson?.let { nested -> resolve(taskId, JSONObject(nested), depth + 1, published) }
                if (suffix is ResolvedEvidenceSource.Audio) suffix.copy(description = "${fact.description}\n${suffix.description}")
                else if (suffix is ResolvedEvidenceSource.Image) suffix.copy(description = "${fact.description}\n${suffix.description}")
                else ResolvedEvidenceSource.Detail("人工补充事实", "${fact.description}\n记录人 ${fact.recordedBy} · 补录时间 ${fact.recordedAt}\n发生时点 ${fact.classAtMs ?: fact.classStartMs ?: "见来源"}")
            }
            "REVIEWED_ANALYSIS" -> {
                val result = requireNotNull(db.aiAnalysisDao().getAnalysisById(id)) { "原分析已不存在" }
                val task = requireNotNull(db.taskDao().getById(taskId))
                val correction = requireNotNull(db.aiCorrectionDao().getByAnalysis(id).firstOrNull()) { "原分析尚未审核" }
                require(result.taskId == taskId && result.verified && correction.reviewerId == task.supervisorId && correction.originalContent == result.content) { "原分析审核无效" }
                val original = requireNotNull(result.payloadJson) { "历史分析没有结构化来源" }
                require(correction.originalPayloadHash == AnalysisJson.hashJson(JSONObject(original))) { "原分析版本已变化" }
                val effective = JSONObject(correction.correctedPayloadJson ?: original)
                verify(AnalysisJson.hashJson(effective))
                val originalEvidence = db.aiEvidenceDao().getByAnalysis(id).joinToString("\n") { it.description }
                ResolvedEvidenceSource.Detail("已审核分析依据", AnalysisJson.summary(effective) + "\n原证据摘要：\n" + originalEvidence)
            }
            "COURSE_REFERENCE" -> {
                val version = requireNotNull(dao.reference(id)) { "课程要求版本不存在" }
                val task = requireNotNull(db.taskDao().getById(taskId))
                require(version.courseId == task.courseId && (published || task.courseReferenceVersionId == id)) { "课程要求与当前课堂不匹配" }
                verify(version.contentHash)
                ResolvedEvidenceSource.Detail("课程要求 v${version.revision}", "${version.sourceDescription}\n${JSONObject(version.payloadJson).toString(2)}")
            }
            else -> ResolvedEvidenceSource.SummaryOnly(ref.optString("quote"), "不支持的来源类型")
        }
    }

    companion object {
        suspend fun fileHash(file: File): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream -> val buffer = ByteArray(64 * 1024); while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        suspend fun resolveMaterialFile(context: Context, taskId: Long, material: Material): File = withContext(Dispatchers.IO) {
            require(material.taskId == taskId) { "来源不属于当前课堂" }
            val root = context.filesDir.canonicalFile
            val expectedDir = File(root, "tasks/$taskId/${if (material.type == MaterialType.AUDIO) "audio" else "photo"}")
            val dir = expectedDir.canonicalFile
            val file = File(root, material.filePath).canonicalFile
            require(dir == expectedDir.absoluteFile && !File(material.filePath).isAbsolute && file.parentFile == dir && dir.path.startsWith(root.path + File.separator)) { "来源路径超出授权目录" }
            require(file.isFile && file.length() > 0) { "原始文件缺失，保留已发布摘要" }
            file
        }
    }
}

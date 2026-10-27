package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.ManifestEvidence
import edu.neu.aijiaoxue.data.analysis.PhaseSegment as LocatedPhaseSegment
import edu.neu.aijiaoxue.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/** US25: confirmed effective structure and real source time only; never turn legacy prose into a full-lesson stage. */
class EffectivePhaseProvider(private val db: AppDatabase) {
    suspend fun load(actorId: Long, taskId: Long): List<LocatedPhaseSegment> = db.withTransaction {
        requireNotNull(db.reviewSourceDao().followUpTaskForActor(actorId, taskId)) { "只有本堂督导或管理员可读取参与分析所需的课堂来源" }
        val mode = if (db.evaluationDao().getByTask(taskId)?.status == EvalStatus.SUBMITTED)
            PhaseReadMode.PUBLISHED_REPORT else PhaseReadMode.CURRENT_CANDIDATE
        load(actorId, taskId, PhaseLayer.BOPPPS, mode).ifEmpty {
            load(actorId, taskId, PhaseLayer.OBSERVED_ACTIVITY, mode)
        }
    }

    suspend fun load(actorId: Long, taskId: Long, layer: PhaseLayer, readMode: PhaseReadMode): List<LocatedPhaseSegment> = db.withTransaction {
        val task = requireNotNull(db.reviewSourceDao().followUpTaskForActor(actorId, taskId)) { "无权读取本课堂环节" }
        if (readMode == PhaseReadMode.PUBLISHED_REPORT) {
            val evaluation = db.evaluationDao().getByTask(taskId)?.takeIf { it.status == EvalStatus.SUBMITTED }
                ?: return@withTransaction emptyList()
            val publication = db.improvementSuggestionDao().publicationForEvaluation(evaluation.id)
                ?: return@withTransaction emptyList()
            val context = jsonOrNull(publication.contextSnapshotJson) ?: return@withTransaction emptyList()
            if (layer == PhaseLayer.OBSERVED_ACTIVITY) {
                val manual = context.optJSONObject("manualPhases") ?: return@withTransaction emptyList()
                return@withTransaction manualSegments(manual, "manual-phase-${context.optLong("manualPhaseVersionId")}")
            }
            val manifests = context.optJSONObject("inputManifests") ?: return@withTransaction emptyList()
            return@withTransaction db.improvementSuggestionDao().snapshots(publication.id)
                .filter { it.type == AiAnalysisType.BOPPPS.name && it.schemaVersion > 0 }
                .flatMap { snapshot ->
                    val payload = jsonOrNull(snapshot.effectivePayloadJson)
                    val manifest = manifests.optJSONObject(snapshot.analysisId.toString())
                    if (payload == null || manifest == null) emptyList() else
                        bopppsSegments(taskId, payload, manifest, snapshot.versionKey, snapshot.sourceMode)
                }
        }
        if (layer == PhaseLayer.OBSERVED_ACTIVITY) {
            val manual = db.analysisSourceDao().currentPhases(taskId) ?: return@withTransaction emptyList()
            if (manual.confirmedBy != task.supervisorId || manual.confirmedAt == null) return@withTransaction emptyList()
            return@withTransaction jsonOrNull(manual.payloadJson)?.let { manualSegments(it, "manual-phase-${manual.id}") }.orEmpty()
        }
        val phases = mutableListOf<LocatedPhaseSegment>()
        for (analysis in db.aiAnalysisDao().getByTask(taskId).filter {
            it.type == AiAnalysisType.BOPPPS && it.isCurrent && it.verified && it.schemaVersion > 0 &&
                it.resultState in setOf(ResultState.SUPPORTED, ResultState.PARTIAL)
        }) {
            val correction = db.aiCorrectionDao().getByAnalysis(analysis.id)
                .maxWithOrNull(compareBy({ it.createdAt }, { it.id })) ?: continue
            val original = jsonOrNull(analysis.payloadJson) ?: continue
            val payload = jsonOrNull(correction.correctedPayloadJson) ?: continue
            if (correction.reviewerId != task.supervisorId || correction.originalContent != analysis.content ||
                correction.originalPayloadHash != AnalysisJson.hashJson(original)) continue
            val jobId = analysis.runId?.toLongOrNull() ?: continue
            val job = db.analysisSourceDao().getJob(jobId)?.takeIf { it.taskId == taskId && it.inputFingerprint == analysis.inputFingerprint } ?: continue
            val manifest = jsonOrNull(job.inputManifestJson) ?: continue
            if (!ManifestEvidence.isCurrent(db, taskId, manifest)) continue
            phases += bopppsSegments(taskId, payload, manifest, "analysis-${analysis.id}/review-${correction.id}", analysis.sourceMode)
        }
        phases
    }

    companion object {
        private fun jsonOrNull(value: String?): JSONObject? = value?.let { runCatching { JSONObject(it) }.getOrNull() }

        /** Each disjoint source range stays disjoint: the gap between two utterances is not an inferred stage. */
        internal fun bopppsSegments(taskId: Long, payload: JSONObject, manifest: JSONObject,
                                    versionKey: String, sourceMode: SourceMode): List<LocatedPhaseSegment> {
            val inputs = AnalysisJson.objects(manifest.optJSONArray("inputs")).associateBy { it.optString("inputId") }
            return AnalysisJson.objects(payload.optJSONArray("segments")).flatMap segmentLoop@ { segment ->
                val stage = runCatching { BopppsStage.valueOf(segment.getString("stage")) }.getOrNull() ?: return@segmentLoop emptyList()
                val id = segment.optString("id").takeIf { it.isNotBlank() } ?: return@segmentLoop emptyList()
                val label = segment.optString("label").takeIf { it.isNotBlank() } ?: stage.label
                // Reviewed boundaries may narrow real evidence, never fill gaps or invent classroom time.
                val reviewedRanges = if (!segment.has("reviewedRanges")) null else {
                    val supplied = segment.optJSONArray("reviewedRanges") ?: return@segmentLoop emptyList()
                    val ranges = AnalysisJson.objects(supplied)
                    if (ranges.size != supplied.length() || ranges.any {
                        (it.opt("startMs") !is Int && it.opt("startMs") !is Long) ||
                            (it.opt("endMs") !is Int && it.opt("endMs") !is Long) ||
                            it.optLong("startMs") < 0 || it.optLong("endMs") <= it.optLong("startMs")
                    }) return@segmentLoop emptyList()
                    ranges.map { it.getLong("startMs") to it.getLong("endMs") }
                }
                val evidence = AnalysisJson.objects(segment.optJSONArray("evidenceRefs")).flatMap referenceLoop@ { reference ->
                    val input = inputs[reference.optString("inputId")] ?: return@referenceLoop emptyList()
                    val key = reference.optString("itemKey").takeIf { it.isNotBlank() && it != "null" }
                    val item = if (key == null) input.optJSONObject("payload") else ManifestEvidence.findItem(input.opt("payload"), key)
                    // Transcript mappings that are missing/estimated must not become exact classroom stage ranges.
                    if (input.optJSONObject("sourceRef")?.optString("kind") == EvidenceKind.TRANSCRIPT_SEGMENT.name &&
                        item?.optString("mappingQuality") != TimeMappingQuality.EXACT.name) return@referenceLoop emptyList()
                    val resolved = runCatching { ManifestEvidence.resolve(JSONObject().put("evidenceRefs", JSONArray().put(reference)), manifest, taskId) }
                        .getOrDefault(emptyList())
                    resolved.mapNotNull { jsonOrNull(it.sourceRefJson) }
                }
                evidence.flatMap { source ->
                    if (!source.isNull("taskId") && source.optLong("taskId") != taskId) emptyList() else
                        AnalysisJson.objects(source.optJSONArray("classRanges")).flatMap { range ->
                            val start = range.optLong("startMs", -1)
                            val end = range.optLong("endMs", -1)
                            if (start < 0 || end <= start) emptyList() else
                                (reviewedRanges ?: listOf(start to end)).mapNotNull { (reviewedStart, reviewedEnd) ->
                                    val effectiveStart = maxOf(start, reviewedStart)
                                    val effectiveEnd = minOf(end, reviewedEnd)
                                    if (effectiveEnd <= effectiveStart) null else LocatedPhaseSegment(
                                        "${stage.label} · $label", effectiveStart, effectiveEnd, id, sourceMode, versionKey,
                                        listOf(AnalysisJson.canonical(source)), PhaseLayer.BOPPPS)
                                }
                        }
                }.groupBy { Triple(it.startOffsetMs, it.endOffsetMs, it.label) }.values.map { matches ->
                    matches.first().copy(evidenceRefs = matches.flatMap { it.evidenceRefs }.distinct())
                }
            }
        }

        private fun manualSegments(payload: JSONObject, versionKey: String): List<LocatedPhaseSegment> = runCatching {
            TeachingPhaseRepository.decode(payload.toString()).filter { it.startMs >= 0 && it.endMs > it.startMs && it.label.isNotBlank() }
                .map { LocatedPhaseSegment(it.label, it.startMs, it.endMs, it.id, SourceMode.MANUAL, versionKey,
                    listOf("$versionKey/${it.id}"), PhaseLayer.OBSERVED_ACTIVITY) }
        }.getOrDefault(emptyList())
    }
}

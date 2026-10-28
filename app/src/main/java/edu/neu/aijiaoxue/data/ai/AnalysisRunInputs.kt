package edu.neu.aijiaoxue.data.ai

import android.content.Context
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Capture source identities separately from the changing outputs of the run itself. */
object AnalysisRunInputs {
    suspend fun capture(db: AppDatabase, context: Context, taskId: Long): JSONObject = withContext(Dispatchers.IO) {
        val dao = db.analysisSourceDao(); val task = requireNotNull(db.taskDao().getById(taskId))
        val materials = db.captureDao().getMaterials(taskId).sortedBy { it.id }
        val transcripts = JSONObject(); dao.transcripts(taskId).forEach { transcripts.put(it.materialId.toString(), transcript(it)) }
        val images = JSONObject(); dao.imageTexts(taskId).forEach { images.put(it.materialId.toString(), image(it)) }
        val reviewed = JSONArray()
        for (result in db.aiAnalysisDao().getByTask(taskId).filter { it.verified }) {
            val correction = db.aiCorrectionDao().getByAnalysis(result.id).firstOrNull()
            reviewed.put(JSONObject().put("id", result.id).put("hash", correction?.correctedPayloadJson?.let { AnalysisJson.hashJson(JSONObject(it)) } ?: result.payloadJson?.let(AnalysisJson::hash)))
        }
        JSONObject().put("materials", JSONArray(materials.map { material ->
            val file = AnalysisInputs.safeFile(context, material)
            JSONObject().put("id", material.id).put("type", material.type.name).put("durationMs", material.durationMs ?: JSONObject.NULL)
                .put("fileHash", if (file.isFile) AnalysisInputs.hashFile(file) else "MISSING")
        })).put("transcripts", transcripts).put("images", images)
            .put("classStartAt", task.classStartAt ?: JSONObject.NULL).put("classEndAt", task.classEndAt ?: JSONObject.NULL)
            .put("referenceId", task.courseReferenceVersionId ?: JSONObject.NULL)
            .put("referenceSelection", task.referenceSelectionJson?.let(::JSONObject) ?: JSONObject.NULL)
            .put("phase", dao.currentPhases(taskId)?.let { JSONObject().put("id", it.id).put("hash", it.contentHash) } ?: JSONObject.NULL)
            .put("facts", JSONArray(dao.facts(taskId).map { JSONObject().put("id", it.id).put("description", it.description).put("source", it.sourceRefJson).put("at", it.classAtMs).put("start", it.classStartMs).put("end", it.classEndMs) }))
            .put("events", JSONArray(db.captureDao().observeEvents(taskId).first().map(AnalysisInputs::eventJson)))
            .put("spans", JSONArray(materials.flatMap { dao.audioSpans(it.id) }.map { JSONObject().put("id", it.id).put("mediaStart", it.mediaStartMs).put("mediaEnd", it.mediaEndMs).put("classStart", it.classStartMs).put("classEnd", it.classEndMs).put("quality", it.mappingQuality.name) }))
            .put("terms", dao.termExclusions(task.courseId)?.contentHash ?: JSONObject.NULL).put("reviewed", reviewed)
    }
    fun transcript(value: TranscriptVersion) = JSONObject().put("id", value.id).put("revision", value.revision).put("hash", value.contentHash).put("fileHash", value.fileHash).put("state", value.state.name)
    fun image(value: ImageTextVersion) = JSONObject().put("id", value.id).put("revision", value.revision).put("hash", value.contentHash).put("fileHash", value.fileHash).put("state", value.state.name)
    fun same(a: String, b: JSONObject) = AnalysisJson.canonical(JSONObject(a)) == AnalysisJson.canonical(b)
    /** Called in the result transaction. Never accept unrelated source changes. */
    suspend fun recordOutput(db: AppDatabase, job: AnalysisJob, collection: String?, materialId: Long?, output: JSONObject?) {
        val runs = db.analysisRunDao()
        for (step in runs.stepsForJob(job.id)) {
            val run = runs.run(step.runId) ?: continue
            if (run.state in setOf(edu.neu.aijiaoxue.data.model.AnalysisRunState.CANCELLED, edu.neu.aijiaoxue.data.model.AnalysisRunState.CANCEL_REQUESTED)) continue
            val accepted = JSONObject(run.acceptedInputsJson)
            if (collection != null && materialId != null && output != null) accepted.getJSONObject(collection).put(materialId.toString(), output)
            runs.updateRun(run.copy(acceptedInputsJson = accepted.toString(), updatedAt = System.currentTimeMillis()))
            runs.updateStep(step.copy(state = if (job.state == edu.neu.aijiaoxue.data.model.JobState.PARTIAL) edu.neu.aijiaoxue.data.model.AnalysisStepState.PARTIAL else edu.neu.aijiaoxue.data.model.AnalysisStepState.SUCCEEDED,
                outputRefsJson = output?.toString(), updatedAt = System.currentTimeMillis()))
        }
    }
}

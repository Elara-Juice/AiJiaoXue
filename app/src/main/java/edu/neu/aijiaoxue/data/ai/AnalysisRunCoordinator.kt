package edu.neu.aijiaoxue.data.ai

import android.content.Context
import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.AnalysisRunStep
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.remote.AnalysisSettings
import edu.neu.aijiaoxue.data.repository.AnalysisRunRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** A durable foreground plan. Leaving the page pauses new submissions, not remote jobs. */
class AnalysisRunCoordinator(private val db: AppDatabase, private val context: Context) {
    private val repository = AnalysisRunRepository(db, context)
    private val pipeline = CloudAnalysisPipeline(db, context)
    private val dao get() = db.analysisRunDao()

    suspend fun pauseRun(actorId: Long, runId: Long) = db.withTransaction {
        val run = repository.snapshot(actorId, runId).run
        if (run.state == AnalysisRunState.RUNNING) dao.updateRun(run.copy(state = AnalysisRunState.PAUSED, updatedAt = now()))
    }

    suspend fun resumeRun(actorId: Long, runId: Long) = db.withTransaction {
        val snapshot = repository.snapshot(actorId, runId)
        require(snapshot.run.state in setOf(AnalysisRunState.PREPARED, AnalysisRunState.PAUSED, AnalysisRunState.WAITING_REVIEW)) { "请先处理输入变化或失败材料" }
        if (snapshot.run.proxyBaseUrl != AnalysisSettings(context).get().baseUrl ||
            !AnalysisRunInputs.same(snapshot.run.acceptedInputsJson, AnalysisRunInputs.capture(db, context, snapshot.run.taskId))) {
            dao.updateRun(snapshot.run.copy(state = AnalysisRunState.WAITING_DECISION, updatedAt = now()))
            return@withTransaction
        }
        if (snapshot.run.state == AnalysisRunState.WAITING_REVIEW) snapshot.steps.filter { it.state == AnalysisStepState.BLOCKED }.forEach {
            dao.updateStep(it.copy(state = AnalysisStepState.PLANNED, blockReason = null, updatedAt = now()))
        }
        dao.updateRun(snapshot.run.copy(state = AnalysisRunState.RUNNING, updatedAt = now()))
    }

    suspend fun tick(actorId: Long, runId: Long, allowNewSubmissions: Boolean = true) = locks.getOrPut(runId) { Mutex() }.withLock {
        var snapshot = repository.snapshot(actorId, runId)
        if (snapshot.run.state in AnalysisRunRepository.TERMINAL) return@withLock
        if (snapshot.run.proxyBaseUrl != AnalysisSettings(context).get().baseUrl) {
            if (snapshot.run.state != AnalysisRunState.CANCEL_REQUESTED)
                dao.updateRun(snapshot.run.copy(state = AnalysisRunState.WAITING_DECISION, updatedAt = now()))
            return@withLock
        }
        // Refresh only known remote jobs. A refresh cannot create a new paid request.
        val running = snapshot.steps.firstOrNull { it.jobId != null && it.state in setOf(AnalysisStepState.RUNNING, AnalysisStepState.CANCEL_REQUESTED) }
        if (running != null) {
            val state = pipeline.refreshExistingJob(actorId, requireNotNull(running.jobId))
            syncStep(running, state)
            snapshot = repository.snapshot(actorId, runId)
        }
        if (snapshot.run.state == AnalysisRunState.CANCEL_REQUESTED) {
            if (snapshot.steps.none { it.state == AnalysisStepState.CANCEL_REQUESTED }) dao.updateRun(snapshot.run.copy(state = AnalysisRunState.CANCELLED, updatedAt = now()))
            return@withLock
        }
        if (!allowNewSubmissions || snapshot.run.state != AnalysisRunState.RUNNING) return@withLock
        if (snapshot.run.proxyBaseUrl != AnalysisSettings(context).get().baseUrl ||
            !AnalysisRunInputs.same(snapshot.run.acceptedInputsJson, AnalysisRunInputs.capture(db, context, snapshot.run.taskId))) {
            dao.updateRun(snapshot.run.copy(state = AnalysisRunState.WAITING_DECISION, updatedAt = now()))
            return@withLock
        }
        if (snapshot.steps.any { it.state in FAILED }) {
            dao.updateRun(snapshot.run.copy(state = AnalysisRunState.WAITING_DECISION, updatedAt = now()))
            return@withLock
        }
        val materialSteps = snapshot.steps.filter { it.materialId != null }
        if (materialSteps.all { it.state in DONE } && snapshot.steps.any { it.state == AnalysisStepState.BLOCKED }) {
            dao.updateRun(snapshot.run.copy(state = AnalysisRunState.WAITING_REVIEW, updatedAt = now()))
            return@withLock
        }
        if (snapshot.steps.all { it.state in DONE }) {
            dao.updateRun(snapshot.run.copy(state = if (snapshot.steps.any { it.state in setOf(AnalysisStepState.SKIPPED, AnalysisStepState.PARTIAL) }) AnalysisRunState.PARTIAL else AnalysisRunState.COMPLETED, updatedAt = now()))
            return@withLock
        }
        val step = snapshot.steps.firstOrNull { it.state in setOf(AnalysisStepState.PLANNED, AnalysisStepState.RUNNING) } ?: return@withLock
        if (step.state == AnalysisStepState.RUNNING) {
            // After an explicit resume, the original idempotency key is used for an unconfirmed submission.
            step.jobId?.let { pipeline.executePrepared(actorId, it); syncStep(step, requireNotNull(db.analysisSourceDao().getJob(it)).state) }
            return@withLock
        }
        if (reuseMaterial(step)) return@withLock
        try {
            val id = pipeline.prepare(actorId, snapshot.run.taskId, step.operation, step.materialId, step.id)
            val latest = repository.snapshot(actorId, runId).run
            if (latest.state == AnalysisRunState.RUNNING) pipeline.executePrepared(actorId, id)
            syncStep(requireNotNull(dao.step(step.id)), requireNotNull(db.analysisSourceDao().getJob(id)).state)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            db.withTransaction {
                val latest = requireNotNull(dao.step(step.id))
                val run = requireNotNull(dao.run(runId))
                if (run.state == AnalysisRunState.RUNNING) {
                    dao.updateStep(latest.copy(state = AnalysisStepState.FAILED, blockReason = e.message ?: "材料不满足分析要求", updatedAt = now()))
                    dao.updateRun(run.copy(state = AnalysisRunState.WAITING_DECISION, updatedAt = now()))
                }
            }
        }
    }

    private suspend fun reuseMaterial(step: AnalysisRunStep): Boolean {
        val materialId = step.materialId ?: return false
        val material = db.captureDao().getMaterial(materialId) ?: return false
        val file = AnalysisInputs.safeFile(context, material)
        if (!file.isFile) return false
        val hash = withContext(Dispatchers.IO) { AnalysisInputs.hashFile(file) }
        val output = if (step.operation == AnalysisOperation.TRANSCRIBE)
            db.analysisSourceDao().transcripts(material.taskId).firstOrNull { it.materialId == materialId && it.fileHash == hash && it.state in AnalysisPlanBuilder.USABLE }?.let(AnalysisRunInputs::transcript)
        else db.analysisSourceDao().imageTexts(material.taskId).firstOrNull { it.materialId == materialId && it.fileHash == hash && it.state in AnalysisPlanBuilder.USABLE }?.let(AnalysisRunInputs::image)
        if (output == null) return false
        db.withTransaction {
            val latest = requireNotNull(dao.step(step.id))
            if (dao.run(step.runId)?.state == AnalysisRunState.RUNNING && latest.state == AnalysisStepState.PLANNED)
                dao.updateStep(latest.copy(state = AnalysisStepState.REUSED, outputRefsJson = output.toString(), updatedAt = now()))
        }
        return true
    }

    private suspend fun syncStep(step: AnalysisRunStep, state: JobState) = db.withTransaction {
        val latest = requireNotNull(dao.step(step.id))
        val run = requireNotNull(dao.run(step.runId))
        if (run.state == AnalysisRunState.CANCELLED) return@withTransaction
        val mapped = when (state) {
            JobState.SUCCEEDED -> AnalysisStepState.SUCCEEDED
            JobState.PARTIAL -> AnalysisStepState.PARTIAL
            JobState.FAILED, JobState.EXPIRED -> AnalysisStepState.FAILED
            JobState.STALE_INPUT -> AnalysisStepState.STALE_INPUT
            JobState.CANCELLED -> AnalysisStepState.CANCELLED
            JobState.CANCEL_REQUESTED -> AnalysisStepState.CANCEL_REQUESTED
            else -> AnalysisStepState.RUNNING
        }
        val job = latest.jobId?.let { db.analysisSourceDao().getJob(it) }
        dao.updateStep(latest.copy(state = mapped, blockReason = job?.errorCode, errorDetailsJson = job?.errorDetailsJson, updatedAt = now()))
        if (job?.serverJobId == null && job?.errorCode != null && run.state == AnalysisRunState.RUNNING) {
            // An uncertain submit is resumed only after the user explicitly continues the original request.
            dao.updateRun(run.copy(state = AnalysisRunState.PAUSED, updatedAt = now()))
        }
    }

    suspend fun cancelRun(actorId: Long, runId: Long) {
        val jobs = db.withTransaction {
            val snapshot = repository.snapshot(actorId, runId)
            if (snapshot.run.state in AnalysisRunRepository.TERMINAL) return@withTransaction emptyList<Long>()
            dao.updateRun(snapshot.run.copy(state = AnalysisRunState.CANCEL_REQUESTED, updatedAt = now()))
            snapshot.steps.filter { it.state !in DONE }.forEach {
                dao.updateStep(it.copy(state = if (it.ownsJob && it.jobId != null) AnalysisStepState.CANCEL_REQUESTED else AnalysisStepState.CANCELLED, updatedAt = now()))
            }
            snapshot.steps.filter { it.ownsJob && it.jobId != null && it.state !in DONE }.mapNotNull { it.jobId }
        }
        if (repository.snapshot(actorId, runId).run.proxyBaseUrl == AnalysisSettings(context).get().baseUrl)
            jobs.forEach { pipeline.cancel(actorId, it) }
        tick(actorId, runId, false)
    }

    suspend fun continueWithAvailable(actorId: Long, runId: Long) = db.withTransaction {
        val snapshot = repository.snapshot(actorId, runId)
        require(snapshot.run.state == AnalysisRunState.WAITING_DECISION)
        require(AnalysisRunInputs.same(snapshot.run.acceptedInputsJson, AnalysisRunInputs.capture(db, context, snapshot.run.taskId))) { "输入已改变，请使用重新确认最新输入" }
        require(snapshot.steps.any { it.materialId != null && it.state in setOf(AnalysisStepState.REUSED, AnalysisStepState.SUCCEEDED, AnalysisStepState.PARTIAL) }) { "还没有可用材料，请补充或重试" }
        snapshot.steps.forEach {
            if (it.state in FAILED) dao.updateStep(it.copy(state = AnalysisStepState.SKIPPED, updatedAt = now()))
            if (it.state == AnalysisStepState.BLOCKED) dao.updateStep(it.copy(state = AnalysisStepState.PLANNED, blockReason = null, updatedAt = now()))
        }
        dao.updateRun(snapshot.run.copy(state = AnalysisRunState.RUNNING, updatedAt = now()))
    }

    suspend fun reconfirmRun(actorId: Long, runId: Long) {
        val snapshot = repository.snapshot(actorId, runId)
        require(snapshot.run.state == AnalysisRunState.WAITING_DECISION)
        // Do not relink an in-flight job to a changed input or another proxy.
        require(snapshot.steps.none { it.state in setOf(AnalysisStepState.RUNNING, AnalysisStepState.CANCEL_REQUESTED) }) { "请先等待原任务完成或停止本轮分析" }
        db.withTransaction {
            val run = repository.snapshot(actorId, runId).run
            val revision = run.planRevision + 1
            dao.steps(runId).filter { it.state !in DONE }.forEach { dao.updateStep(it.copy(state = AnalysisStepState.SKIPPED, updatedAt = now())) }
            val selected = JSONArray(run.selectedOperationsJson)
            val operations = (0 until selected.length()).map { AnalysisOperation.valueOf(selected.getString(it)) }.toSet()
            val accepted = AnalysisRunInputs.capture(db, context, run.taskId)
            dao.insertSteps(AnalysisRunRepository.buildSteps(runId, db.captureDao().getMaterials(run.taskId), operations, now(), revision))
            dao.updateRun(run.copy(planRevision = revision, state = AnalysisRunState.PREPARED, acceptedInputsJson = accepted.toString(),
                materialSelectionJson = accepted.getJSONArray("materials").toString(), proxyBaseUrl = AnalysisSettings(context).get().baseUrl, updatedAt = now()))
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<Long, Mutex>()
        private val DONE = setOf(AnalysisStepState.SUCCEEDED, AnalysisStepState.PARTIAL, AnalysisStepState.REUSED, AnalysisStepState.SKIPPED)
        private val FAILED = setOf(AnalysisStepState.FAILED, AnalysisStepState.STALE_INPUT, AnalysisStepState.CANCELLED)
        private fun now() = System.currentTimeMillis()
    }
}

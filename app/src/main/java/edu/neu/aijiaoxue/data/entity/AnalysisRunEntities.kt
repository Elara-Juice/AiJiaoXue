package edu.neu.aijiaoxue.data.entity

import androidx.room.*
import edu.neu.aijiaoxue.data.model.*

@Entity(tableName = "analysis_runs", foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index("taskId"), Index("createdBy"), Index(value = ["requestKey"], unique = true)])
data class AnalysisRun(@PrimaryKey(autoGenerate = true) val id: Long = 0, val requestKey: String, val taskId: Long, val createdBy: Long,
    val state: AnalysisRunState = AnalysisRunState.PREPARED, val policyVersion: String = "two_stage_v1", val planRevision: Int = 1,
    val selectedOperationsJson: String, val materialSelectionJson: String, val acceptedInputsJson: String,
    val proxyBaseUrl: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "analysis_run_steps", foreignKeys = [
    ForeignKey(entity = AnalysisRun::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = AnalysisJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["runId", "stepKey"], unique = true), Index("jobId")])
data class AnalysisRunStep(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val stepKey: String,
    val operation: AnalysisOperation, val materialId: Long? = null, val dependsOnJson: String = "[]",
    val state: AnalysisStepState = AnalysisStepState.PLANNED, val jobId: Long? = null, val ownsJob: Boolean = false,
    val inputFingerprint: String? = null, val outputRefsJson: String? = null, val blockReason: String? = null,
    val errorDetailsJson: String? = null, val createdAt: Long, val updatedAt: Long)

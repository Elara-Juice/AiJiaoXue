package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import edu.neu.aijiaoxue.data.model.*

@Entity(tableName = "course_reference_versions", foreignKeys = [ForeignKey(entity = Course::class, parentColumns = ["id"], childColumns = ["courseId"])], indices = [Index(value = ["courseId", "revision"], unique = true)])
data class CourseReferenceVersion(@PrimaryKey(autoGenerate = true) val id: Long = 0, val courseId: Long, val revision: Int, val chapterScope: String, val sourceDescription: String, val payloadJson: String, val contentHash: String, val createdBy: Long, val createdAt: Long)

@Entity(tableName = "audio_time_spans", foreignKeys = [ForeignKey(entity = Material::class, parentColumns = ["id"], childColumns = ["materialId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["materialId", "ordinal"], unique = true)])
data class AudioTimeSpan(@PrimaryKey(autoGenerate = true) val id: Long = 0, val materialId: Long, val ordinal: Int, val mediaStartMs: Long, val mediaEndMs: Long, val classStartMs: Long, val classEndMs: Long, val mappingQuality: TimeMappingQuality, val mappingVersion: String = "pause_spans_v1")

@Entity(tableName = "transcript_versions", foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = Material::class, parentColumns = ["id"], childColumns = ["materialId"], onDelete = ForeignKey.CASCADE)], indices = [Index("taskId"), Index(value = ["materialId", "revision"], unique = true)])
data class TranscriptVersion(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val materialId: Long, val revision: Int, val fileHash: String, val payloadJson: String, val provider: String, val model: String, val methodVersion: String, val state: ResultState, val contentHash: String, val isCurrent: Boolean = true, val createdAt: Long)

@Entity(tableName = "teaching_phase_versions", foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["taskId", "revision"], unique = true)])
data class TeachingPhaseVersion(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val revision: Int, val payloadJson: String, val contentHash: String, val status: PhaseStatus, val confirmedBy: Long? = null, val confirmedAt: Long? = null, val isCurrent: Boolean = false)

@Entity(tableName = "image_text_versions", foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = Material::class, parentColumns = ["id"], childColumns = ["materialId"], onDelete = ForeignKey.CASCADE)], indices = [Index("taskId"), Index(value = ["materialId", "revision"], unique = true)])
data class ImageTextVersion(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val materialId: Long, val revision: Int, val fileHash: String, val payloadJson: String, val contentHash: String, val provider: String, val model: String, val state: ResultState, val isCurrent: Boolean = true, val createdAt: Long)

@Entity(tableName = "manual_classroom_facts", foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["taskId", "requestKey"], unique = true), Index("supersedesFactId")])
data class ManualClassroomFact(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val description: String, val sourceRefJson: String? = null, val classAtMs: Long? = null, val classStartMs: Long? = null, val classEndMs: Long? = null, val recordedBy: Long, val recordedAt: Long, val requestKey: String, val supersedesFactId: Long? = null, val isCurrent: Boolean = true)

@Entity(tableName = "term_exclusion_versions", foreignKeys = [ForeignKey(entity = Course::class, parentColumns = ["id"], childColumns = ["courseId"])], indices = [Index(value = ["courseId", "revision"], unique = true)])
data class TermExclusionVersion(@PrimaryKey(autoGenerate = true) val id: Long = 0, val courseId: Long, val revision: Int, val payloadJson: String, val contentHash: String, val createdBy: Long, val createdAt: Long)

@Entity(tableName = "analysis_jobs", foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index("taskId"), Index("state"), Index(value = ["clientRequestId"], unique = true)])
data class AnalysisJob(@PrimaryKey(autoGenerate = true) val id: Long = 0, val clientRequestId: String, val taskId: Long, val operation: AnalysisOperation, val serverJobId: String? = null, val inputFingerprint: String, val inputManifestJson: String, val state: JobState = JobState.QUEUED, val stage: String? = null, val errorCode: String? = null, val attempt: Int = 0, val createdAt: Long, val updatedAt: Long, val resultJson: String? = null, val errorDetailsJson: String? = null)

@Entity(tableName = "published_fact_snapshots", foreignKeys = [ForeignKey(entity = ReviewPublication::class, parentColumns = ["id"], childColumns = ["publicationId"], onDelete = ForeignKey.CASCADE)], indices = [Index("publicationId")])
data class PublishedFactSnapshot(@PrimaryKey(autoGenerate = true) val id: Long = 0, val publicationId: Long, val sourceFactId: Long, val description: String, val sourceRefJson: String? = null, val classAtMs: Long? = null, val classStartMs: Long? = null, val classEndMs: Long? = null, val recordedBy: Long, val recordedAt: Long)

@Entity(tableName = "published_evidence_snapshots", foreignKeys = [ForeignKey(entity = ReviewPublication::class, parentColumns = ["id"], childColumns = ["publicationId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ReviewedAnalysisSnapshot::class, parentColumns = ["id"], childColumns = ["analysisSnapshotId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PublishedFactSnapshot::class, parentColumns = ["id"], childColumns = ["factSnapshotId"], onDelete = ForeignKey.CASCADE)], indices = [Index("publicationId"), Index("analysisSnapshotId"), Index("factSnapshotId")])
data class PublishedEvidenceSnapshot(@PrimaryKey(autoGenerate = true) val id: Long = 0, val publicationId: Long, val analysisSnapshotId: Long? = null, val factSnapshotId: Long? = null, val ordinal: Int, val taskId: Long, val sourceRefJson: String?, val excerpt: String, val sourceHash: String?, val availabilityAtPublish: String, val capturedAt: Long)

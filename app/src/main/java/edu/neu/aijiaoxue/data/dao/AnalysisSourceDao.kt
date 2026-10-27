package edu.neu.aijiaoxue.data.dao

import androidx.room.*
import edu.neu.aijiaoxue.data.entity.*

/** 内部数据接口；对外访问统一经过输入仓储或来源解析器的 actor/task 授权。 */
@Dao
interface AnalysisSourceDao {
    @Query("SELECT t.* FROM supervision_tasks t JOIN users u ON u.id=:actorId WHERE t.id=:taskId AND t.supervisorId=u.id AND u.role='SUPERVISOR'")
    suspend fun ownedTask(actorId: Long, taskId: Long): SupervisionTask?
    @Query("UPDATE supervision_tasks SET analysisCaseId=:caseId WHERE id=:taskId AND analysisCaseId IS NULL") suspend fun setCaseId(taskId: Long, caseId: String)
    @Query("UPDATE supervision_tasks SET inputRevision=inputRevision+1 WHERE id=:taskId") suspend fun bumpInputRevision(taskId: Long)
    @Query("UPDATE supervision_tasks SET courseReferenceVersionId=:versionId, referenceSelectionJson=:selectionJson, inputRevision=inputRevision+1 WHERE id=:taskId")
    suspend fun bindReference(taskId: Long, versionId: Long, selectionJson: String)
    @Query("SELECT * FROM course_reference_versions WHERE courseId=:courseId ORDER BY revision DESC") suspend fun references(courseId: Long): List<CourseReferenceVersion>
    @Query("SELECT * FROM course_reference_versions WHERE id=:id") suspend fun reference(id: Long): CourseReferenceVersion?
    @Insert suspend fun insertReference(value: CourseReferenceVersion): Long
    @Query("SELECT * FROM audio_time_spans WHERE materialId=:materialId ORDER BY ordinal") suspend fun audioSpans(materialId: Long): List<AudioTimeSpan>
    @Insert suspend fun insertAudioSpans(values: List<AudioTimeSpan>)
    @Query("SELECT * FROM transcript_versions WHERE taskId=:taskId AND isCurrent=1 ORDER BY materialId") suspend fun transcripts(taskId: Long): List<TranscriptVersion>
    @Query("SELECT * FROM transcript_versions WHERE id=:id") suspend fun transcript(id: Long): TranscriptVersion?
    @Query("SELECT COALESCE(MAX(revision),0) FROM transcript_versions WHERE materialId=:materialId") suspend fun transcriptRevision(materialId: Long): Int
    @Query("UPDATE transcript_versions SET isCurrent=0 WHERE materialId=:materialId") suspend fun deactivateTranscripts(materialId: Long)
    @Insert suspend fun insertTranscript(value: TranscriptVersion): Long
    @Query("SELECT * FROM teaching_phase_versions WHERE taskId=:taskId AND isCurrent=1 AND status='CONFIRMED' LIMIT 1") suspend fun currentPhases(taskId: Long): TeachingPhaseVersion?
    @Query("SELECT * FROM teaching_phase_versions WHERE taskId=:taskId ORDER BY revision DESC LIMIT 1") suspend fun latestPhases(taskId: Long): TeachingPhaseVersion?
    @Query("SELECT * FROM teaching_phase_versions WHERE id=:id") suspend fun phases(id: Long): TeachingPhaseVersion?
    @Query("UPDATE teaching_phase_versions SET isCurrent=0 WHERE taskId=:taskId") suspend fun deactivatePhases(taskId: Long)
    @Insert suspend fun insertPhases(value: TeachingPhaseVersion): Long
    @Query("SELECT * FROM image_text_versions WHERE taskId=:taskId AND isCurrent=1 ORDER BY materialId") suspend fun imageTexts(taskId: Long): List<ImageTextVersion>
    @Query("SELECT * FROM image_text_versions WHERE id=:id") suspend fun imageText(id: Long): ImageTextVersion?
    @Query("SELECT COALESCE(MAX(revision),0) FROM image_text_versions WHERE materialId=:materialId") suspend fun imageTextRevision(materialId: Long): Int
    @Query("UPDATE image_text_versions SET isCurrent=0 WHERE materialId=:materialId") suspend fun deactivateImageTexts(materialId: Long)
    @Insert suspend fun insertImageText(value: ImageTextVersion): Long
    @Query("SELECT * FROM manual_classroom_facts WHERE taskId=:taskId AND isCurrent=1 ORDER BY recordedAt,id") suspend fun facts(taskId: Long): List<ManualClassroomFact>
    @Query("SELECT * FROM manual_classroom_facts WHERE id=:id") suspend fun fact(id: Long): ManualClassroomFact?
    @Query("SELECT * FROM manual_classroom_facts WHERE taskId=:taskId AND requestKey=:key") suspend fun factByRequest(taskId: Long, key: String): ManualClassroomFact?
    @Query("UPDATE manual_classroom_facts SET isCurrent=0 WHERE id=:id AND isCurrent=1") suspend fun deactivateFact(id: Long): Int
    @Insert suspend fun insertFact(value: ManualClassroomFact): Long
    @Query("SELECT * FROM term_exclusion_versions WHERE courseId=:courseId ORDER BY revision DESC LIMIT 1") suspend fun termExclusions(courseId: Long): TermExclusionVersion?
    @Insert suspend fun insertTermExclusions(value: TermExclusionVersion): Long
    @Insert suspend fun insertJob(value: AnalysisJob): Long
    @Update suspend fun updateJob(value: AnalysisJob)
    @Query("SELECT * FROM analysis_jobs WHERE id=:id") suspend fun getJob(id: Long): AnalysisJob?
    @Query("SELECT * FROM analysis_jobs WHERE taskId=:taskId ORDER BY createdAt DESC,id DESC") suspend fun jobsForTask(taskId: Long): List<AnalysisJob>
    @Insert suspend fun insertPublishedFact(value: PublishedFactSnapshot): Long
    @Insert suspend fun insertPublishedEvidence(value: PublishedEvidenceSnapshot): Long
    @Query("SELECT * FROM published_fact_snapshots WHERE publicationId=:publicationId ORDER BY recordedAt,id") suspend fun publishedFacts(publicationId: Long): List<PublishedFactSnapshot>
    @Query("SELECT * FROM published_evidence_snapshots WHERE publicationId=:publicationId ORDER BY ordinal,id") suspend fun publishedEvidence(publicationId: Long): List<PublishedEvidenceSnapshot>
    @Query("SELECT e.* FROM published_evidence_snapshots e JOIN review_publications p ON p.id=e.publicationId JOIN supervision_tasks t ON t.id=p.taskId JOIN courses c ON c.id=t.courseId JOIN evaluations ev ON ev.id=p.evaluationId JOIN users u ON u.id=:actorId WHERE e.id=:id AND e.taskId=:taskId AND p.taskId=:taskId AND ev.status='SUBMITTED' AND t.status='COMPLETED' AND (u.role='ADMIN' OR (u.role='SUPERVISOR' AND t.supervisorId=u.id) OR (u.role='TEACHER' AND c.teacherId=u.id))")
    suspend fun publishedEvidenceForActor(actorId: Long, taskId: Long, id: Long): PublishedEvidenceSnapshot?
    @Query("SELECT e.* FROM ai_evidences e JOIN ai_analysis_results a ON a.id=e.analysisId JOIN supervision_tasks t ON t.id=a.taskId JOIN users u ON u.id=:actorId WHERE e.id=:id AND e.taskId=:taskId AND a.taskId=:taskId AND u.role='SUPERVISOR' AND t.supervisorId=u.id")
    suspend fun currentEvidenceForActor(actorId: Long, taskId: Long, id: Long): AiEvidence?
    @Query("SELECT * FROM interaction_events WHERE id=:id AND taskId=:taskId") suspend fun event(taskId: Long, id: Long): InteractionEvent?
    @Query("SELECT * FROM engagement_records WHERE id=:id AND taskId=:taskId") suspend fun engagement(taskId: Long, id: Long): EngagementRecord?
}

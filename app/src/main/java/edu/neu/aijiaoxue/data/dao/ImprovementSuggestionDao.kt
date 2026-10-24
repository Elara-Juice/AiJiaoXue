package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.*

@Dao
interface ImprovementSuggestionDao {
    @Insert
    suspend fun insertPublication(publication: ReviewPublication): Long

    @Insert
    suspend fun insertSnapshot(snapshot: ReviewedAnalysisSnapshot): Long

    @Query("SELECT * FROM review_publications WHERE evaluationId = :evaluationId")
    suspend fun publicationForEvaluation(evaluationId: Long): ReviewPublication?

    @Query("SELECT * FROM reviewed_analysis_snapshots WHERE publicationId = :publicationId ORDER BY id")
    suspend fun snapshots(publicationId: Long): List<ReviewedAnalysisSnapshot>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSuggestion(suggestion: ImprovementSuggestion): Long

    @Query("SELECT * FROM improvement_suggestions WHERE planId = :planId ORDER BY itemId, id")
    suspend fun suggestions(planId: Long): List<ImprovementSuggestion>

    @Query("UPDATE improvement_suggestions SET dismissedAt = :at WHERE id = :suggestionId")
    suspend fun setDismissed(suggestionId: Long, at: Long?)
}

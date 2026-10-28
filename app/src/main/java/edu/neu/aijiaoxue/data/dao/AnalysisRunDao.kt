package edu.neu.aijiaoxue.data.dao

import androidx.room.*
import edu.neu.aijiaoxue.data.entity.*

@Dao
interface AnalysisRunDao {
    @Insert suspend fun insertRun(run: AnalysisRun): Long
    @Update suspend fun updateRun(run: AnalysisRun)
    @Insert suspend fun insertSteps(steps: List<AnalysisRunStep>): List<Long>
    @Update suspend fun updateStep(step: AnalysisRunStep)
    @Query("SELECT * FROM analysis_runs WHERE id=:id") suspend fun run(id: Long): AnalysisRun?
    @Query("SELECT * FROM analysis_runs WHERE requestKey=:key") suspend fun byRequest(key: String): AnalysisRun?
    @Query("SELECT * FROM analysis_runs WHERE taskId=:taskId ORDER BY createdAt DESC,id DESC") suspend fun runs(taskId: Long): List<AnalysisRun>
    @Query("SELECT * FROM analysis_run_steps WHERE runId=:runId ORDER BY id") suspend fun steps(runId: Long): List<AnalysisRunStep>
    @Query("SELECT * FROM analysis_run_steps WHERE id=:id") suspend fun step(id: Long): AnalysisRunStep?
    @Query("SELECT * FROM analysis_run_steps WHERE jobId=:jobId") suspend fun stepsForJob(jobId: Long): List<AnalysisRunStep>
}

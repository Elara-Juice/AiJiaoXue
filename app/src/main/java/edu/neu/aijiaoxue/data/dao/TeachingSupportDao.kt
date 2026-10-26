package edu.neu.aijiaoxue.data.dao

import androidx.room.*
import edu.neu.aijiaoxue.data.entity.*

data class TeachingFeedbackRow(val taskId: Long, val courseName: String, val supervisionDate: String, val strengths: String?)

@Dao
interface TeachingSupportDao {
    @Query("""SELECT t.id AS taskId, c.name AS courseName, t.supervisionDate, e.strengths
        FROM supervision_tasks t JOIN courses c ON c.id=t.courseId
        JOIN evaluations e ON e.taskId=t.id JOIN users u ON u.id=:actorId
        WHERE u.role='TEACHER' AND c.teacherId=u.id AND e.status='SUBMITTED' AND t.status='COMPLETED'
        ORDER BY t.supervisionDate DESC, t.id DESC""")
    suspend fun feedbacks(actorId: Long): List<TeachingFeedbackRow>

    @Query("""SELECT h.* FROM teaching_highlights h JOIN supervision_tasks t ON t.id=h.taskId
        JOIN courses c ON c.id=t.courseId JOIN evaluations e ON e.taskId=t.id JOIN users u ON u.id=:actorId
        WHERE u.role='TEACHER' AND c.teacherId=u.id AND h.teacherId=u.id AND e.status='SUBMITTED'
        ORDER BY h.updatedAt DESC, h.id DESC""")
    suspend fun highlights(actorId: Long): List<TeachingHighlight>

    @Upsert suspend fun saveHighlight(highlight: TeachingHighlight): Long

    @Query("""SELECT a.* FROM teaching_adoptions a JOIN improvement_measures m ON m.id=a.measureId
        JOIN improvement_items i ON i.id=m.itemId JOIN improvement_plans p ON p.id=i.planId
        JOIN supervision_tasks t ON t.id=p.taskId JOIN courses c ON c.id=t.courseId
        JOIN evaluations e ON e.taskId=t.id JOIN users u ON u.id=:actorId
        WHERE u.role='TEACHER' AND p.teacherId=u.id AND c.teacherId=u.id
        AND e.status='SUBMITTED' AND t.id=:taskId ORDER BY a.id""")
    suspend fun adoptions(actorId: Long, taskId: Long): List<TeachingAdoption>

    @Insert suspend fun insertAdoption(adoption: TeachingAdoption): Long
}

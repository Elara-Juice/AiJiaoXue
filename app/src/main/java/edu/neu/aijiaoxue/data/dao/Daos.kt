package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import edu.neu.aijiaoxue.data.entity.Arrangement
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.ImprovementItem
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.entity.ImprovementPlan
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/*
 * DAO 约定：列表查询返回 Flow（界面自动刷新），单次读写用 suspend。
 * 方法名：observeXxx 返回 Flow，getXxx 单次读取，insert/update/delete 写入。
 * 带角色归属的查询必须把当前用户 ID 作为参数传入，不要查全部再在界面过滤（NF-06）。
 */

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE account = :account LIMIT 1")
    suspend fun getByAccount(account: String): User?

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: Long): User?

    @Insert
    suspend fun insertAll(users: List<User>): List<Long>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int
}

@Dao
interface CourseDao {
    /** US01：按课程名称、教师姓名、课程编号模糊匹配；keyword 为空串时返回全部。 */
    @Query(
        """
        SELECT c.* FROM courses c JOIN users u ON u.id = c.teacherId
        WHERE :keyword = '' OR c.name LIKE '%' || :keyword || '%'
           OR u.name LIKE '%' || :keyword || '%' OR c.code LIKE '%' || :keyword || '%'
        ORDER BY c.code
        """
    )
    fun observeSearch(keyword: String): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): Course?

    @Query("SELECT * FROM courses WHERE teacherId = :teacherId")
    fun observeByTeacher(teacherId: Long): Flow<List<Course>>

    @Insert
    suspend fun insertAll(courses: List<Course>): List<Long>
}

@Dao
interface ArrangementDao {
    @Query("SELECT * FROM arrangements ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Arrangement>>

    /** US05：同一课程只能有一条未完成的安排。 */
    @Query("SELECT * FROM arrangements WHERE courseId = :courseId AND status != 'DONE' LIMIT 1")
    suspend fun getOpenByCourse(courseId: Long): Arrangement?

    @Insert
    suspend fun insert(arrangement: Arrangement): Long

    @Update
    suspend fun update(arrangement: Arrangement)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM supervision_tasks WHERE id = :taskId")
    suspend fun getById(taskId: Long): SupervisionTask?

    @Query("SELECT * FROM supervision_tasks WHERE id = :taskId")
    fun observeById(taskId: Long): Flow<SupervisionTask?>

    /** 我的督导任务。 */
    @Query("SELECT * FROM supervision_tasks WHERE supervisorId = :supervisorId ORDER BY supervisionDate DESC, period")
    fun observeBySupervisor(supervisorId: Long): Flow<List<SupervisionTask>>

    /** 管理人员任务进度（US06）。 */
    @Query("SELECT * FROM supervision_tasks ORDER BY supervisionDate DESC, period")
    fun observeAll(): Flow<List<SupervisionTask>>

    /** 已完成任务（US02 历史督导次数、US05 覆盖情况）。 */
    @Query("SELECT * FROM supervision_tasks WHERE courseId = :courseId AND status = 'COMPLETED' ORDER BY supervisionDate DESC")
    fun observeCompletedByCourse(courseId: Long): Flow<List<SupervisionTask>>

    /** 用于生成当日任务编号序号。 */
    @Query("SELECT COUNT(*) FROM supervision_tasks WHERE code LIKE :codePrefix || '%'")
    suspend fun countByCodePrefix(codePrefix: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM supervision_tasks WHERE supervisorId = :supervisorId
        AND courseId = :courseId AND supervisionDate = :date AND period = :period
        """
    )
    suspend fun countDuplicate(supervisorId: Long, courseId: Long, date: String, period: String): Int

    @Insert
    suspend fun insert(task: SupervisionTask): Long

    /** 状态只能由业务操作驱动（US06），不要在界面直接改。 */
    @Query("UPDATE supervision_tasks SET status = :status WHERE id = :taskId")
    suspend fun updateStatus(taskId: Long, status: TaskStatus)

    @Query("UPDATE supervision_tasks SET classStartAt = :at WHERE id = :taskId AND classStartAt IS NULL")
    suspend fun markClassStart(taskId: Long, at: Long)

    @Query("UPDATE supervision_tasks SET classEndAt = :at WHERE id = :taskId")
    suspend fun markClassEnd(taskId: Long, at: Long)
}

/** 课堂采集：材料、互动事件、参与状态（US08–US13）。 */
@Dao
interface CaptureDao {
    @Query("SELECT * FROM materials WHERE taskId = :taskId ORDER BY capturedAt")
    fun observeMaterials(taskId: Long): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun getMaterial(id: Long): Material?

    @Insert
    suspend fun insertMaterial(material: Material): Long

    @Update
    suspend fun updateMaterial(material: Material)

    /** 删除记录前必须先用 FileStore 删除文件（US11）。 */
    @Delete
    suspend fun deleteMaterial(material: Material)

    @Query("SELECT * FROM interaction_events WHERE taskId = :taskId ORDER BY occurredAt")
    fun observeEvents(taskId: Long): Flow<List<InteractionEvent>>

    @Insert
    suspend fun insertEvent(event: InteractionEvent): Long

    @Update
    suspend fun updateEvent(event: InteractionEvent)

    @Delete
    suspend fun deleteEvent(event: InteractionEvent)

    @Query("SELECT * FROM engagement_records WHERE taskId = :taskId ORDER BY recordedAt")
    fun observeEngagements(taskId: Long): Flow<List<EngagementRecord>>

    @Insert
    suspend fun insertEngagement(record: EngagementRecord): Long

    @Delete
    suspend fun deleteEngagement(record: EngagementRecord)
}

@Dao
interface EvaluationDao {
    @Query("SELECT * FROM evaluations WHERE taskId = :taskId")
    fun observeByTask(taskId: Long): Flow<Evaluation?>

    @Query("SELECT * FROM evaluations WHERE taskId = :taskId")
    suspend fun getByTask(taskId: Long): Evaluation?

    /** 草稿自动保存用（US07、US29）。 */
    @Upsert
    suspend fun upsert(evaluation: Evaluation): Long

    @Query("SELECT * FROM evaluation_issues WHERE evaluationId = :evaluationId ORDER BY sortOrder")
    fun observeIssues(evaluationId: Long): Flow<List<EvaluationIssue>>

    @Query("SELECT * FROM evaluation_issues WHERE id = :id")
    suspend fun getIssue(id: Long): EvaluationIssue?

    @Upsert
    suspend fun upsertIssue(issue: EvaluationIssue): Long

    @Delete
    suspend fun deleteIssue(issue: EvaluationIssue)

    /** 教师端只看本人课程、已提交的评价（US33）。 */
    @Query(
        """
        SELECT e.* FROM evaluations e
        JOIN supervision_tasks t ON t.id = e.taskId
        JOIN courses c ON c.id = t.courseId
        WHERE c.teacherId = :teacherId AND e.status = 'SUBMITTED'
        ORDER BY e.submittedAt DESC
        """
    )
    fun observeSubmittedForTeacher(teacherId: Long): Flow<List<Evaluation>>

    @Query("UPDATE evaluations SET teacherReadAt = :at WHERE id = :evaluationId AND teacherReadAt IS NULL")
    suspend fun markRead(evaluationId: Long, at: Long)
}

@Dao
interface ImprovementDao {
    @Query("SELECT * FROM improvement_plans WHERE taskId = :taskId")
    suspend fun getPlanByTask(taskId: Long): ImprovementPlan?

    @Query("SELECT * FROM improvement_plans WHERE id = :planId")
    fun observePlan(planId: Long): Flow<ImprovementPlan?>

    @Upsert
    suspend fun upsertPlan(plan: ImprovementPlan): Long

    @Query("SELECT * FROM improvement_items WHERE planId = :planId")
    fun observeItems(planId: Long): Flow<List<ImprovementItem>>

    @Upsert
    suspend fun upsertItem(item: ImprovementItem): Long

    @Delete
    suspend fun deleteItem(item: ImprovementItem)

    @Query("SELECT * FROM improvement_measures WHERE planId = :planId")
    fun observeMeasuresByPlan(planId: Long): Flow<List<ImprovementMeasure>>

    @Upsert
    suspend fun upsertMeasure(measure: ImprovementMeasure): Long

    @Delete
    suspend fun deleteMeasure(measure: ImprovementMeasure)

    /** 改进待办（US42）：本人已确认计划下的措施，按计划完成日期升序。 */
    @Query(
        """
        SELECT m.* FROM improvement_measures m
        JOIN improvement_plans p ON p.id = m.planId
        WHERE p.teacherId = :teacherId AND p.status = 'CONFIRMED'
        ORDER BY m.dueDate
        """
    )
    fun observeTodosForTeacher(teacherId: Long): Flow<List<ImprovementMeasure>>

    @Query("UPDATE improvement_measures SET status = :status, updatedAt = :at WHERE id = :measureId")
    suspend fun updateMeasureStatus(measureId: Long, status: MeasureStatus, at: Long)
}

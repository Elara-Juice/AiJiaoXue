package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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
import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.TaskCode
import edu.neu.aijiaoxue.data.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/*
 * DAO 约定：列表查询返回 Flow（界面自动刷新），单次读写用 suspend。
 * 方法名：observeXxx 返回 Flow，getXxx 单次读取，insert/update/delete 写入。
 * 带角色归属的查询必须把当前用户 ID 作为参数传入，不要查全部再在界面过滤（NF-06）。
 * xxxRow 方法返回 Rows.kt 中的联表结果，供列表页直接显示。
 */

private const val COURSE_ROW_SELECT = """
    SELECT c.id, c.code, c.name, c.teacherId, u.name AS teacherName,
           c.className, c.schedule, c.location,
           (SELECT COUNT(*) FROM supervision_tasks t
             WHERE t.courseId = c.id AND t.status = 'COMPLETED') AS completedCount,
           (SELECT MAX(t.supervisionDate) FROM supervision_tasks t
             WHERE t.courseId = c.id AND t.status = 'COMPLETED') AS lastSupervisionDate,
           EXISTS (SELECT 1 FROM arrangements a
             WHERE a.courseId = c.id AND a.status != 'DONE') AS hasOpenArrangement,
           EXISTS (SELECT 1 FROM arrangements a
             WHERE a.courseId = c.id AND a.status != 'DONE' AND a.isKeyFocus = 1) AS isKeyFocus
    FROM courses c JOIN users u ON u.id = c.teacherId
    """

private const val TASK_ROW_SELECT = """
    SELECT t.id, t.code, t.courseId, c.name AS courseName, tu.name AS teacherName,
           t.supervisorId, su.name AS supervisorName,
           t.supervisionDate, t.period, t.type, t.status
    FROM supervision_tasks t
    JOIN courses c ON c.id = t.courseId
    JOIN users tu ON tu.id = c.teacherId
    JOIN users su ON su.id = t.supervisorId
    """

private const val HISTORY_ROW_SELECT = """
    SELECT t.id AS taskId, t.code, c.name AS courseName, tu.name AS teacherName,
           su.name AS supervisorName, t.supervisionDate, t.period, e.totalScore, e.grade
    FROM supervision_tasks t
    JOIN courses c ON c.id = t.courseId
    JOIN users tu ON tu.id = c.teacherId
    JOIN users su ON su.id = t.supervisorId
    LEFT JOIN evaluations e ON e.taskId = t.id
    WHERE t.status = 'COMPLETED'
      AND (:keyword = '' OR c.name LIKE '%' || :keyword || '%' OR tu.name LIKE '%' || :keyword || '%')
      AND (:fromDate = '' OR t.supervisionDate >= :fromDate)
      AND (:toDate = '' OR t.supervisionDate <= :toDate)
    """

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

    /** US06 按督导人员筛选时提供候选列表。 */
    @Query("SELECT * FROM users WHERE role = :role ORDER BY name")
    fun observeByRole(role: Role): Flow<List<User>>
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

    /** US01 检索结果，带教师名、历史督导和“待督导”标识。keyword 由调用方去掉首尾空格。 */
    @Query(
        COURSE_ROW_SELECT + """
        WHERE :keyword = '' OR c.name LIKE '%' || :keyword || '%'
           OR u.name LIKE '%' || :keyword || '%' OR c.code LIKE '%' || :keyword || '%'
        ORDER BY c.code
        """
    )
    fun observeRows(keyword: String): Flow<List<CourseRow>>

    /** US02 课程详情。 */
    @Query(COURSE_ROW_SELECT + "WHERE c.id = :courseId")
    fun observeRow(courseId: Long): Flow<CourseRow?>

    /** US05 “本学期未督导”：semesterStart 之后没有已完成任务的课程。传 Semester.START_DATE。 */
    @Query(
        COURSE_ROW_SELECT + """
        WHERE NOT EXISTS (
            SELECT 1 FROM supervision_tasks t
            WHERE t.courseId = c.id AND t.status = 'COMPLETED' AND t.supervisionDate >= :semesterStart
        )
        ORDER BY c.code
        """
    )
    fun observeUncoveredRows(semesterStart: String): Flow<List<CourseRow>>
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

    /** 待督导安排列表（US05）。 */
    @Query("SELECT * FROM arrangements WHERE status != 'DONE' ORDER BY isKeyFocus DESC, createdAt DESC")
    fun observeOpen(): Flow<List<Arrangement>>
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

    /** 我的督导任务列表（带课程名、教师名）。 */
    @Query(TASK_ROW_SELECT + "WHERE t.supervisorId = :supervisorId ORDER BY t.supervisionDate DESC, t.period")
    fun observeRowsBySupervisor(supervisorId: Long): Flow<List<TaskRow>>

    /**
     * US06 任务进度，三个条件均可为空（不筛选）。
     * 状态多选在 ViewModel 里对 [TaskStatus] 过滤即可，这里只做单状态。
     */
    @Query(
        TASK_ROW_SELECT + """
        WHERE (:courseId IS NULL OR t.courseId = :courseId)
          AND (:supervisorId IS NULL OR t.supervisorId = :supervisorId)
          AND (:status IS NULL OR t.status = :status)
        ORDER BY t.supervisionDate DESC, t.period
        """
    )
    fun observeRowsFiltered(courseId: Long?, supervisorId: Long?, status: TaskStatus?): Flow<List<TaskRow>>

    /** US06 各状态数量。 */
    @Query("SELECT status, COUNT(*) AS count FROM supervision_tasks GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query(TASK_ROW_SELECT + "WHERE t.id = :taskId")
    fun observeRow(taskId: Long): Flow<TaskRow?>

    /** US32 督导本人的历史记录；keyword 匹配课程名或教师名，日期为空串表示不限。 */
    @Query(HISTORY_ROW_SELECT + " AND t.supervisorId = :supervisorId ORDER BY t.supervisionDate DESC")
    fun observeHistoryForSupervisor(
        supervisorId: Long, keyword: String, fromDate: String, toDate: String,
    ): Flow<List<HistoryRow>>

    /** US32 管理人员可查看全部历史记录。 */
    @Query(HISTORY_ROW_SELECT + " ORDER BY t.supervisionDate DESC")
    fun observeHistoryAll(keyword: String, fromDate: String, toDate: String): Flow<List<HistoryRow>>

    /** 权限校验用：任务所属课程的任课教师。 */
    @Query("SELECT c.teacherId FROM supervision_tasks t JOIN courses c ON c.id = t.courseId WHERE t.id = :taskId")
    suspend fun getTeacherIdOf(taskId: Long): Long?

    @Query("SELECT * FROM arrangements WHERE courseId = :courseId AND status = 'PENDING' ORDER BY createdAt LIMIT 1")
    suspend fun getPendingArrangement(courseId: Long): Arrangement?

    @Query("UPDATE arrangements SET status = 'CLAIMED' WHERE id = :arrangementId")
    suspend fun claimArrangement(arrangementId: Long)

    /**
     * US04 创建任务：查重、生成编号、关联待督导安排（业务规则 4）放在同一事务里，
     * 避免两次点击生成重复编号。task.code 与 arrangementId 由本方法填写。
     * 返回新任务 id；同一督导、课程、日期、节次已存在时返回 null（提示“任务已存在”）。
     */
    @Transaction
    suspend fun create(task: SupervisionTask): Long? {
        if (countDuplicate(task.supervisorId, task.courseId, task.supervisionDate, task.period) > 0) return null
        val seq = countByCodePrefix(TaskCode.prefixOf(task.supervisionDate)) + 1
        val arrangement = getPendingArrangement(task.courseId)
        val id = insert(
            task.copy(
                code = TaskCode.of(task.supervisionDate, seq),
                arrangementId = arrangement?.id,
                status = TaskStatus.NOT_STARTED,
            )
        )
        arrangement?.let { claimArrangement(it.id) }
        return id
    }

    /** 规范第 4 节：首次进入采集页 NOT_STARTED → IN_PROGRESS，写 classStartAt。再次进入返回 0。 */
    /** 规范第 4 节：首次进入采集页 NOT_STARTED → IN_PROGRESS，写 classStartAt。再次进入返回 0。 */
    @Query("UPDATE supervision_tasks SET status = 'IN_PROGRESS', classStartAt = :at WHERE id = :taskId AND status = 'NOT_STARTED'")
    suspend fun startClass(taskId: Long, at: Long): Int

    /** 规范第 4 节：结束课堂采集 IN_PROGRESS → PENDING_EVALUATION。返回受影响行数。 */
    @Query("UPDATE supervision_tasks SET status = 'PENDING_EVALUATION', classEndAt = :at WHERE id = :taskId AND status = 'IN_PROGRESS'")
    suspend fun endClass(taskId: Long, at: Long): Int
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

    /** US12：采集页实时显示各类型事件计数。 */
    @Query("SELECT type, COUNT(*) AS count FROM interaction_events WHERE taskId = :taskId GROUP BY type")
    fun observeEventCounts(taskId: Long): Flow<List<EventTypeCount>>

    /** US12：5 秒内撤销误标，按 insertEvent 返回的 id 删除。 */
    @Query("DELETE FROM interaction_events WHERE id = :eventId")
    suspend fun deleteEventById(eventId: Long): Int

    /**
     * US07 业务规则 3：录音中异常退出时 endedAt 仍为空。
     * 重新进入采集页时查出这些片段，按实际文件补写 endedAt、durationMs。
     */
    @Query("SELECT * FROM materials WHERE taskId = :taskId AND type = 'AUDIO' AND endedAt IS NULL")
    suspend fun getUnfinishedAudios(taskId: Long): List<Material>

    /** US07：恢复时逐个检查文件是否存在，缺失的显示“文件缺失”。 */
    @Query("SELECT * FROM materials WHERE taskId = :taskId ORDER BY capturedAt")
    suspend fun getMaterials(taskId: Long): List<Material>

    /** US11 业务规则 2：任务已完成后材料不可删除。删除前先调用本方法判断。 */
    @Query("SELECT status != 'COMPLETED' FROM supervision_tasks WHERE id = :taskId")
    suspend fun isMaterialEditable(taskId: Long): Boolean?
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

    /** US33 督导反馈列表（带课程名、督导名）。 */
    @Query(
        """
        SELECT e.id AS evaluationId, t.id AS taskId, c.name AS courseName, su.name AS supervisorName,
               t.supervisionDate, e.totalScore, e.grade, e.submittedAt, e.teacherReadAt
        FROM evaluations e
        JOIN supervision_tasks t ON t.id = e.taskId
        JOIN courses c ON c.id = t.courseId
        JOIN users su ON su.id = t.supervisorId
        WHERE c.teacherId = :teacherId AND e.status = 'SUBMITTED'
        ORDER BY e.submittedAt DESC
        """
    )
    fun observeFeedbackRows(teacherId: Long): Flow<List<FeedbackRow>>

    /** US33 未读数量，首页红点。 */
    @Query(
        """
        SELECT COUNT(*) FROM evaluations e
        JOIN supervision_tasks t ON t.id = e.taskId
        JOIN courses c ON c.id = t.courseId
        WHERE c.teacherId = :teacherId AND e.status = 'SUBMITTED' AND e.teacherReadAt IS NULL
        """
    )
    fun observeUnreadCount(teacherId: Long): Flow<Int>

    /** US33 教师查看单条反馈：不是本人课程或未提交时返回 null，界面提示无权限。 */
    @Query(
        """
        SELECT e.* FROM evaluations e
        JOIN supervision_tasks t ON t.id = e.taskId
        JOIN courses c ON c.id = t.courseId
        WHERE e.taskId = :taskId AND c.teacherId = :teacherId AND e.status = 'SUBMITTED'
        """
    )
    suspend fun getSubmittedForTeacher(taskId: Long, teacherId: Long): Evaluation?

    /** US30 提交前校验主要问题至少 1 条。 */
    @Query("SELECT COUNT(*) FROM evaluation_issues WHERE evaluationId = :evaluationId")
    suspend fun countIssues(evaluationId: Long): Int

    @Query("SELECT * FROM evaluation_issues WHERE evaluationId = :evaluationId ORDER BY sortOrder")
    suspend fun getIssues(evaluationId: Long): List<EvaluationIssue>

    /** US32：督导只能查看本人任务的评价，不是本人返回 null。管理人员用 [getByTask]。 */
    @Query(
        """
        SELECT e.* FROM evaluations e JOIN supervision_tasks t ON t.id = e.taskId
        WHERE e.taskId = :taskId AND t.supervisorId = :supervisorId
        """
    )
    suspend fun getForSupervisor(taskId: Long, supervisorId: Long): Evaluation?

    /**
     * US29、US07：自动保存草稿。已提交的评价锁定（US30 业务规则 3），返回 null 不写入。
     * 自动保存请用本方法，不要直接调 [upsert]。
     */
    @Transaction
    suspend fun saveDraft(evaluation: Evaluation): Long? {
        val existing = getByTask(evaluation.taskId)
        if (existing?.status == EvalStatus.SUBMITTED) return null
        return upsert(
            evaluation.copy(
                id = existing?.id ?: evaluation.id,
                status = EvalStatus.DRAFT,
                updatedAt = System.currentTimeMillis(),
            )
        ).takeIf { it != -1L } ?: existing?.id ?: evaluation.id
    }

    /**
     * US30 提交评价：四维评分齐全、总体意见非空、主要问题至少 1 条时才把草稿置为已提交。
     * totalScore、grade 由调用方用 EvalRules 计算后传入。返回受影响行数，0 表示未满足条件或已提交。
     */
    @Query(
        """
        UPDATE evaluations SET status = 'SUBMITTED', totalScore = :totalScore, grade = :grade,
               submittedAt = :at, updatedAt = :at
        WHERE id = :evaluationId AND status = 'DRAFT'
          AND contentScore IS NOT NULL AND designScore IS NOT NULL
          AND interactionScore IS NOT NULL AND engagementScore IS NOT NULL
          AND overallOpinion IS NOT NULL AND TRIM(overallOpinion) != ''
          AND EXISTS (SELECT 1 FROM evaluation_issues i WHERE i.evaluationId = :evaluationId)
        """
    )
    suspend fun markSubmitted(evaluationId: Long, totalScore: Int, grade: EvalGrade, at: Long): Int

    @Query("UPDATE supervision_tasks SET status = 'COMPLETED' WHERE id = :taskId AND status = 'PENDING_EVALUATION'")
    suspend fun completeTaskOf(taskId: Long): Int

    /** 关联的督导安排随任务完成一并完成，课程不再显示“待督导”（US05）。 */
    @Query(
        """
        UPDATE arrangements SET status = 'DONE'
        WHERE id = (SELECT arrangementId FROM supervision_tasks WHERE id = :taskId)
        """
    )
    suspend fun finishArrangementOf(taskId: Long)

    /**
     * US30 主流程 3：提交评价，任务 PENDING_EVALUATION → COMPLETED（规范第 4 节）。
     * 三步在同一事务里，任一步不满足则整体不生效。返回是否提交成功。
     */
    @Transaction
    suspend fun submit(evaluationId: Long, taskId: Long, totalScore: Int, grade: EvalGrade, at: Long): Boolean {
        if (markSubmitted(evaluationId, totalScore, grade, at) == 0) return false
        if (completeTaskOf(taskId) == 0) throw IllegalStateException("task $taskId is not PENDING_EVALUATION")
        finishArrangementOf(taskId)
        return true
    }
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

    /** 教师本人某次任务的计划；不是本人返回 null。 */
    @Query("SELECT * FROM improvement_plans WHERE taskId = :taskId AND teacherId = :teacherId")
    fun observePlanForTeacher(taskId: Long, teacherId: Long): Flow<ImprovementPlan?>

    @Query("SELECT * FROM improvement_items WHERE planId = :planId")
    suspend fun getItems(planId: Long): List<ImprovementItem>

    @Query("SELECT * FROM improvement_measures WHERE itemId = :itemId")
    suspend fun getMeasuresByItem(itemId: Long): List<ImprovementMeasure>

    /** US41 确认计划。只更新草稿状态的计划，返回受影响行数，0 表示已确认过。 */
    @Query("UPDATE improvement_plans SET status = 'CONFIRMED', confirmedAt = :at WHERE id = :planId AND status = 'DRAFT'")
    suspend fun confirmPlan(planId: Long, at: Long): Int

    /** US42 改进待办列表，带原问题、课程和督导日期；status 为 null 表示全部。 */
    @Query(
        """
        SELECT m.id AS measureId, m.planId, p.taskId, m.content, m.source, m.dueDate, m.status,
               i.description AS itemDescription, i.priority,
               c.name AS courseName, t.supervisionDate
        FROM improvement_measures m
        JOIN improvement_plans p ON p.id = m.planId
        JOIN improvement_items i ON i.id = m.itemId
        JOIN supervision_tasks t ON t.id = p.taskId
        JOIN courses c ON c.id = t.courseId
        WHERE p.teacherId = :teacherId AND p.status = 'CONFIRMED'
          AND (:status IS NULL OR m.status = :status)
        ORDER BY m.dueDate
        """
    )
    fun observeTodoRows(teacherId: Long, status: MeasureStatus?): Flow<List<TodoRow>>

    /** US42 默认展示未完成事项。 */
    @Query(
        """
        SELECT m.id AS measureId, m.planId, p.taskId, m.content, m.source, m.dueDate, m.status,
               i.description AS itemDescription, i.priority,
               c.name AS courseName, t.supervisionDate
        FROM improvement_measures m
        JOIN improvement_plans p ON p.id = m.planId
        JOIN improvement_items i ON i.id = m.itemId
        JOIN supervision_tasks t ON t.id = p.taskId
        JOIN courses c ON c.id = t.courseId
        WHERE p.teacherId = :teacherId AND p.status = 'CONFIRMED' AND m.status != 'DONE'
        ORDER BY m.dueDate
        """
    )
    fun observeOpenTodoRows(teacherId: Long): Flow<List<TodoRow>>

    /** US33 业务规则 1：只有本人课程且已提交评价的任务才能制定改进计划。 */
    @Query(
        """
        SELECT COUNT(*) > 0 FROM evaluations e
        JOIN supervision_tasks t ON t.id = e.taskId
        JOIN courses c ON c.id = t.courseId
        WHERE e.taskId = :taskId AND c.teacherId = :teacherId AND e.status = 'SUBMITTED'
        """
    )
    suspend fun canPlan(taskId: Long, teacherId: Long): Boolean

    @Query("SELECT * FROM improvement_plans WHERE taskId = :taskId AND teacherId = :teacherId")
    suspend fun getPlanForTeacher(taskId: Long, teacherId: Long): ImprovementPlan?

    @Query("DELETE FROM improvement_items WHERE planId = :planId AND id NOT IN (:keepIds)")
    suspend fun deleteItemsExcept(planId: Long, keepIds: List<Long>)

    /**
     * US36 保存改进重点：首次保存创建草稿计划（规范 2.3 节），再次保存覆盖选择。
     * items 的 planId 由本方法填写；未出现在 items 里的旧事项连同其措施一并删除（业务规则 3）。
     * 返回 planId；无权限、未选任何事项或计划已确认时返回 null。
     */
    @Transaction
    suspend fun saveFocus(teacherId: Long, taskId: Long, items: List<ImprovementItem>): Long? {
        if (items.isEmpty() || !canPlan(taskId, teacherId)) return null
        val plan = getPlanForTeacher(taskId, teacherId)
        if (plan?.status == PlanStatus.CONFIRMED) return null
        val planId = plan?.id ?: upsertPlan(ImprovementPlan(teacherId = teacherId, taskId = taskId))
        val keepIds = items.map { item ->
            val id = upsertItem(item.copy(planId = planId))
            if (id == -1L) item.id else id
        }
        deleteItemsExcept(planId, keepIds)
        return planId
    }

    @Query("SELECT status = 'DRAFT' FROM improvement_plans WHERE id = :planId")
    suspend fun isPlanEditable(planId: Long): Boolean?

    /** US41 业务规则 2：没有任何措施的事项数量。 */
    @Query(
        """
        SELECT COUNT(*) FROM improvement_items i
        WHERE i.planId = :planId
          AND NOT EXISTS (SELECT 1 FROM improvement_measures m WHERE m.itemId = i.id)
        """
    )
    suspend fun countItemsWithoutMeasure(planId: Long): Int

    /** US41 异常处理：内容为空或缺少完成日期的措施数量。 */
    @Query(
        """
        SELECT COUNT(*) FROM improvement_measures
        WHERE planId = :planId AND (TRIM(content) = '' OR TRIM(dueDate) = '')
        """
    )
    suspend fun countInvalidMeasures(planId: Long): Int

    /**
     * US41 确认计划：每个事项至少 1 条措施、措施内容和完成日期都已填写时才确认。
     * 确认后计划锁定，生成的措施即出现在改进待办（US42）。界面先用上面两个计数提示具体问题。
     */
    @Transaction
    suspend fun confirmPlanChecked(planId: Long, at: Long): Boolean {
        if (getItems(planId).isEmpty()) return false
        if (countItemsWithoutMeasure(planId) > 0 || countInvalidMeasures(planId) > 0) return false
        return confirmPlan(planId, at) > 0
    }

    /** US42：教师只能切换本人已确认计划下的措施状态。返回受影响行数，0 表示无权限。 */
    @Query(
        """
        UPDATE improvement_measures SET status = :status, updatedAt = :at
        WHERE id = :measureId AND planId IN (
            SELECT id FROM improvement_plans WHERE teacherId = :teacherId AND status = 'CONFIRMED'
        )
        """
    )
    suspend fun updateMeasureStatusForTeacher(measureId: Long, teacherId: Long, status: MeasureStatus, at: Long): Int
}

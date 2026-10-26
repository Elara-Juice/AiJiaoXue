package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.ImprovementExecution
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask

/*
 * Sprint 3（李奕萱）：复评关联所需的数据读取。
 *
 * 跨督导读取范围（本轮约定，写在查询层而不是界面里）：
 * - 督导可以读取“本人也督导该课程”的其他督导课堂的【正式结果】（已提交评价与问题条目），
 *   目的是能就同一门课的历史课堂建立后续复评关联；
 * - 督导不能跨督导读取课堂原始采集数据（音频、照片、互动事件、参与状态记录）；
 * - 课堂原始采集数据只有该课堂督导本人和教学管理人员可以读取；
 * - 教师只能读取本人课程的已提交正式评价，不能读取他人课程数据。
 */

/** 复评任务：只有该课堂的督导本人或教学管理人员可以建立复评关联。 */
@Dao
interface ReviewSourceDao {
    @Query("""SELECT t.* FROM supervision_tasks t JOIN users u ON u.id = :actorId
        WHERE t.id = :taskId AND (u.role = 'ADMIN' OR (u.role = 'SUPERVISOR' AND t.supervisorId = u.id))""")
    suspend fun followUpTaskForActor(actorId: Long, taskId: Long): SupervisionTask?

    /**
     * 原课堂：督导可以读取同课程（本人也督导该课程）的其他督导课堂，用于建立复评关联。
     * 这里只返回任务基本信息，不含任何课堂原始采集数据。
     */
    @Query("""SELECT t.* FROM supervision_tasks t JOIN courses c ON c.id = t.courseId
        JOIN users u ON u.id = :actorId
        WHERE t.id = :taskId AND (u.role = 'ADMIN'
            OR (u.role = 'SUPERVISOR' AND (t.supervisorId = u.id OR EXISTS (
                SELECT 1 FROM supervision_tasks own WHERE own.courseId = t.courseId AND own.supervisorId = u.id)))
            OR (u.role = 'TEACHER' AND c.teacherId = u.id))""")
    suspend fun originTaskForActor(actorId: Long, taskId: Long): SupervisionTask?

    /**
     * 已提交的正式评价。
     * 督导除了本人督导的课堂，还可以读“本人也督导该课程”的其他督导课堂的正式结果，
     * 这是建立复评关联所必需的跨督导读取范围。
     */
    @Query("""SELECT e.* FROM evaluations e JOIN supervision_tasks t ON t.id = e.taskId
        JOIN courses c ON c.id = t.courseId JOIN users u ON u.id = :actorId
        WHERE e.taskId = :taskId AND e.status = 'SUBMITTED'
        AND (u.role = 'ADMIN'
            OR (u.role = 'SUPERVISOR' AND (t.supervisorId = u.id OR EXISTS (
                SELECT 1 FROM supervision_tasks own WHERE own.courseId = t.courseId AND own.supervisorId = u.id)))
            OR (u.role = 'TEACHER' AND c.teacherId = u.id))""")
    suspend fun submittedEvaluationForActor(actorId: Long, taskId: Long): Evaluation?

    /** 正式评价的问题条目，权限与正式评价一致。 */
    @Query("""SELECT i.* FROM evaluation_issues i JOIN evaluations e ON e.id = i.evaluationId
        JOIN supervision_tasks t ON t.id = e.taskId JOIN courses c ON c.id = t.courseId
        JOIN users u ON u.id = :actorId
        WHERE e.taskId = :taskId AND e.status = 'SUBMITTED'
        AND (u.role = 'ADMIN'
            OR (u.role = 'SUPERVISOR' AND (t.supervisorId = u.id OR EXISTS (
                SELECT 1 FROM supervision_tasks own WHERE own.courseId = t.courseId AND own.supervisorId = u.id)))
            OR (u.role = 'TEACHER' AND c.teacherId = u.id))
        ORDER BY i.sortOrder, i.id""")
    suspend fun submittedIssuesForActor(actorId: Long, taskId: Long): List<EvaluationIssue>

    /** 课堂原始采集数据：仅该课堂督导本人和教学管理人员。 */
    @Query("""SELECT r.* FROM engagement_records r JOIN supervision_tasks t ON t.id = r.taskId
        JOIN users u ON u.id = :actorId
        WHERE r.taskId = :taskId AND (u.role = 'ADMIN' OR (u.role = 'SUPERVISOR' AND t.supervisorId = u.id))
        ORDER BY r.offsetMs, r.id""")
    suspend fun engagementsForActor(actorId: Long, taskId: Long): List<EngagementRecord>

    @Query("""SELECT e.* FROM interaction_events e JOIN supervision_tasks t ON t.id = e.taskId
        JOIN users u ON u.id = :actorId
        WHERE e.taskId = :taskId AND (u.role = 'ADMIN' OR (u.role = 'SUPERVISOR' AND t.supervisorId = u.id))
        ORDER BY e.offsetMs, e.id""")
    suspend fun eventsForActor(actorId: Long, taskId: Long): List<InteractionEvent>

    @Query("""SELECT m.* FROM materials m JOIN supervision_tasks t ON t.id = m.taskId
        JOIN users u ON u.id = :actorId
        WHERE m.taskId = :taskId AND (u.role = 'ADMIN' OR (u.role = 'SUPERVISOR' AND t.supervisorId = u.id))
        ORDER BY m.offsetMs, m.id""")
    suspend fun materialsForActor(actorId: Long, taskId: Long): List<Material>

    /** 课程的任课教师，用于复评关联的教师侧授权。 */
    @Query("SELECT teacherId FROM courses WHERE id = :courseId")
    suspend fun teacherOfCourse(courseId: Long): Long?

    /**
     * 经复评关联授权后读取原课堂的改进措施（不含证据文件内容）。
     * 复评督导需要看到“实际做了什么”才能核验问题是否改善。
     */
    @Query("""SELECT m.* FROM improvement_measures m
        JOIN improvement_plans p ON p.id = m.planId
        JOIN review_links l ON l.originTaskId = p.taskId
        JOIN supervision_tasks ot ON ot.id = l.originTaskId
        JOIN supervision_tasks ft ON ft.id = l.followUpTaskId
        JOIN courses c ON c.id = ot.courseId
        JOIN users u ON u.id = :actorId
        WHERE l.id = :linkId AND (u.role = 'ADMIN'
            OR (u.role = 'TEACHER' AND c.teacherId = u.id)
            OR (u.role = 'SUPERVISOR' AND (ot.supervisorId = u.id OR ft.supervisorId = u.id)))
        ORDER BY m.id""")
    suspend fun originMeasuresForLink(actorId: Long, linkId: Long): List<ImprovementMeasure>

    /** 经复评关联授权后读取原课堂的实施记录（措施实际做法与实施时间）。 */
    @Query("""SELECT x.* FROM improvement_executions x
        JOIN improvement_measures m ON m.id = x.measureId
        JOIN improvement_plans p ON p.id = m.planId
        JOIN review_links l ON l.originTaskId = p.taskId
        JOIN supervision_tasks ot ON ot.id = l.originTaskId
        JOIN supervision_tasks ft ON ft.id = l.followUpTaskId
        JOIN courses c ON c.id = ot.courseId
        JOIN users u ON u.id = :actorId
        WHERE l.id = :linkId AND (u.role = 'ADMIN'
            OR (u.role = 'TEACHER' AND c.teacherId = u.id)
            OR (u.role = 'SUPERVISOR' AND (ot.supervisorId = u.id OR ft.supervisorId = u.id)))
        ORDER BY x.implementedAt, x.id""")
    suspend fun originExecutionsForLink(actorId: Long, linkId: Long): List<ImprovementExecution>

    /** 复评关联列表：教师限本人课程，督导限本人督导的原课堂或复评课堂。 */
    @Query("""SELECT l.id AS id, l.originTaskId AS originTaskId, l.followUpTaskId AS followUpTaskId,
        l.originIssueSnapshot AS originIssueSnapshot, l.originDimension AS originDimension,
        c.name AS courseName, tu.name AS teacherName,
        ot.supervisionDate AS originDate, ot.period AS originPeriod,
        ft.supervisionDate AS followUpDate, ft.period AS followUpPeriod,
        l.createdAt AS createdAt
        FROM review_links l
        JOIN supervision_tasks ot ON ot.id = l.originTaskId
        JOIN supervision_tasks ft ON ft.id = l.followUpTaskId
        JOIN courses c ON c.id = ot.courseId
        JOIN users tu ON tu.id = l.teacherId
        JOIN users u ON u.id = :actorId
        WHERE (u.role = 'ADMIN'
            OR (u.role = 'TEACHER' AND c.teacherId = u.id)
            OR (u.role = 'SUPERVISOR' AND (ot.supervisorId = u.id OR ft.supervisorId = u.id)))
        ORDER BY l.createdAt DESC""")
    fun observeLinkRows(actorId: Long): kotlinx.coroutines.flow.Flow<List<ReviewLinkRow>>

    /** 可建立复评关联的“跟踪复评”课堂：本人督导的课堂（管理人员可见全部）。 */
    @Query("""SELECT t.id AS id, t.code AS code, t.courseId AS courseId, c.name AS courseName,
        t.supervisionDate AS supervisionDate, t.period AS period, t.chapter AS chapter
        FROM supervision_tasks t JOIN courses c ON c.id = t.courseId JOIN users u ON u.id = :actorId
        WHERE t.type = 'FOLLOW_UP'
        AND (u.role = 'ADMIN' OR (u.role = 'SUPERVISOR' AND t.supervisorId = u.id))
        ORDER BY t.supervisionDate DESC, t.period""")
    fun observeFollowUpTasks(actorId: Long): kotlinx.coroutines.flow.Flow<List<ReviewTaskCandidateRow>>

    /**
     * 原课堂候选：同一门课程、已完成、已有正式评价。
     * 允许跨督导读取同课程的正式结果（用于建立复评关联），但不涉及课堂原始采集数据。
     */
    @Query("""SELECT t.id AS id, t.code AS code, t.courseId AS courseId, c.name AS courseName,
        t.supervisionDate AS supervisionDate, t.period AS period, t.chapter AS chapter
        FROM supervision_tasks t JOIN courses c ON c.id = t.courseId
        JOIN evaluations e ON e.taskId = t.id AND e.status = 'SUBMITTED'
        JOIN users u ON u.id = :actorId
        WHERE t.courseId = :courseId AND t.id != :followUpTaskId AND t.status = 'COMPLETED'
        AND (u.role = 'ADMIN' OR (u.role = 'SUPERVISOR' AND (t.supervisorId = u.id OR EXISTS (
            SELECT 1 FROM supervision_tasks own WHERE own.courseId = t.courseId AND own.supervisorId = u.id))))
        ORDER BY t.supervisionDate DESC, t.period""")
    suspend fun originCandidates(actorId: Long, courseId: Long, followUpTaskId: Long): List<ReviewTaskCandidateRow>
}

package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.IssueVerification
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.entity.ReviewLink

/*
 * Sprint 3（李奕萱）：复评关联、指标快照与核验记录的数据访问。
 *
 * 权限校验放在查询层（AGENTS.md 硬性规则 6）：
 * - 教学管理人员（ADMIN）可读全部记录；
 * - 任课教师（TEACHER）只读本人课程的关联与快照；
 * - 教学督导（SUPERVISOR）读自己督导的课堂；跨督导只通过 review_links 读到
 *   原课堂的正式结论与指标，不因此获得原课堂的原始采集数据。
 */

/**
 * 快照读取范围：管理人员全量，教师限本人课程，督导限本人督导的课堂；
 * 另外，复评督导可以通过已建立的复评关联读取原课堂的正式指标快照（跨督导读取的唯一入口）。
 */
private const val SNAPSHOT_READ_SCOPE = """
    JOIN supervision_tasks t ON t.id = s.taskId
    JOIN courses c ON c.id = t.courseId
    JOIN users u ON u.id = :actorId
    WHERE (u.role = 'ADMIN'
        OR (u.role = 'TEACHER' AND c.teacherId = u.id)
        OR (u.role = 'SUPERVISOR' AND (t.supervisorId = u.id OR EXISTS (
            SELECT 1 FROM review_links l JOIN supervision_tasks ft ON ft.id = l.followUpTaskId
            WHERE l.originTaskId = t.id AND ft.supervisorId = u.id))))
"""

/** 关联读取范围：教师限本人课程；督导限原课堂或复评课堂的督导人。 */
private const val LINK_READ_SCOPE = """
    JOIN supervision_tasks ot ON ot.id = l.originTaskId
    JOIN supervision_tasks ft ON ft.id = l.followUpTaskId
    JOIN courses c ON c.id = ot.courseId
    JOIN users u ON u.id = :actorId
    WHERE (u.role = 'ADMIN'
        OR (u.role = 'TEACHER' AND c.teacherId = u.id)
        OR (u.role = 'SUPERVISOR' AND (ot.supervisorId = u.id OR ft.supervisorId = u.id)))
"""

@Dao
interface MetricSnapshotDao {
    /**
     * 只追加不覆盖：同一 (taskId, metricKey, methodVersion) 已存在时忽略新值，
     * 保证历史正式快照不被后续重分析原地改写（AGENTS.md Sprint 3 业务规则）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(snapshot: MetricSnapshot): Long

    @Query("SELECT * FROM metric_snapshots WHERE id = :id")
    suspend fun getById(id: Long): MetricSnapshot?

    @Query("SELECT s.* FROM metric_snapshots s " + SNAPSHOT_READ_SCOPE + " AND s.taskId = :taskId ORDER BY s.metricKey, s.methodVersion")
    suspend fun getForTask(actorId: Long, taskId: Long): List<MetricSnapshot>

    @Query("SELECT s.* FROM metric_snapshots s " + SNAPSHOT_READ_SCOPE + " AND s.taskId = :taskId ORDER BY s.metricKey, s.methodVersion")
    fun observeForTask(actorId: Long, taskId: Long): kotlinx.coroutines.flow.Flow<List<MetricSnapshot>>

    @Query("SELECT s.* FROM metric_snapshots s " + SNAPSHOT_READ_SCOPE + " AND s.taskId IN (:taskIds) ORDER BY s.taskId, s.metricKey, s.methodVersion")
    suspend fun getForTasks(actorId: Long, taskIds: List<Long>): List<MetricSnapshot>
}

@Dao
interface ReviewLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: ReviewLink): Long

    @Query("SELECT l.* FROM review_links l " + LINK_READ_SCOPE + " AND l.id = :linkId")
    suspend fun getForActor(actorId: Long, linkId: Long): ReviewLink?

    @Query("SELECT l.* FROM review_links l " + LINK_READ_SCOPE + " AND l.originTaskId = :taskId ORDER BY l.createdAt DESC")
    suspend fun getByOriginTask(actorId: Long, taskId: Long): List<ReviewLink>

    @Query("SELECT l.* FROM review_links l " + LINK_READ_SCOPE + " AND l.followUpTaskId = :taskId ORDER BY l.createdAt DESC")
    suspend fun getByFollowUpTask(actorId: Long, taskId: Long): List<ReviewLink>

    @Query("SELECT l.* FROM review_links l " + LINK_READ_SCOPE + " ORDER BY l.createdAt DESC")
    fun observeForActor(actorId: Long): kotlinx.coroutines.flow.Flow<List<ReviewLink>>
}

@Dao
interface IssueVerificationDao {
    @Insert
    suspend fun insert(verification: IssueVerification): Long

    @Query("SELECT v.* FROM issue_verifications v JOIN review_links l ON l.id = v.linkId " + LINK_READ_SCOPE + " AND v.linkId = :linkId ORDER BY v.verifiedAt DESC")
    suspend fun getForLink(actorId: Long, linkId: Long): List<IssueVerification>

    @Query("SELECT v.* FROM issue_verifications v JOIN review_links l ON l.id = v.linkId " + LINK_READ_SCOPE + " ORDER BY v.verifiedAt DESC")
    fun observeForActor(actorId: Long): kotlinx.coroutines.flow.Flow<List<IssueVerification>>

    @Query("SELECT COUNT(*) FROM issue_verifications WHERE linkId = :linkId")
    suspend fun countForLink(linkId: Long): Int
}

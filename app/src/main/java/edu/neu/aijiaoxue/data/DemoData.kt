package edu.neu.aijiaoxue.data

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.entity.Arrangement
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.model.ArrangementStatus
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.EvalRules
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskCode
import edu.neu.aijiaoxue.data.model.TaskStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 演示数据，内容以 docs/演示数据.md 为准，改数据先改文档再改这里。
 * 数据库为空（没有任何用户）时写入一次；清除应用数据或破坏性迁移后会重新写入。
 */
object DemoData {
    const val DEFAULT_PASSWORD = "123456"

    suspend fun seedIfEmpty(db: AppDatabase) {
        db.withTransaction {
            // 放在事务里判断，避免两处同时调用时重复写入
            if (db.userDao().count() > 0) return@withTransaction
            seed(db)
        }
    }

    private suspend fun seed(db: AppDatabase) {
        val hash = PasswordHasher.hash(DEFAULT_PASSWORD)
        val userIds = db.userDao().insertAll(
            listOf(
                User(account = "admin", passwordHash = hash, name = "刘建华", role = Role.ADMIN),
                User(account = "sup01", passwordHash = hash, name = "王志强", role = Role.SUPERVISOR),
                User(account = "sup02", passwordHash = hash, name = "赵敏", role = Role.SUPERVISOR),
                User(account = "tea01", passwordHash = hash, name = "张晓明", role = Role.TEACHER),
                User(account = "tea02", passwordHash = hash, name = "李静", role = Role.TEACHER),
                User(account = "tea03", passwordHash = hash, name = "孙伟", role = Role.TEACHER),
            )
        )
        val (admin, sup01, sup02, tea01, tea02, tea03) = userIds

        val courseIds = db.courseDao().insertAll(
            listOf(
                Course(code = "FT2026-01", name = "数据结构", teacherId = tea01, className = "软件2401班", schedule = "周一 1-2 节", location = "一号楼 A201"),
                Course(code = "FT2026-02", name = "操作系统", teacherId = tea01, className = "软件2402班", schedule = "周三 3-4 节", location = "一号楼 A305"),
                Course(code = "FT2026-03", name = "软件工程", teacherId = tea02, className = "软件2404班", schedule = "周二 5-6 节", location = "一号楼 B102"),
                Course(code = "FT2026-04", name = "计算机网络", teacherId = tea02, className = "软件2403班", schedule = "周四 1-2 节", location = "一号楼 B210"),
                // 地点留空，演示 US02“未填写”
                Course(code = "FT2026-05", name = "高等数学", teacherId = tea03, className = "软件2402班", schedule = "周五 3-4 节", location = null),
            )
        )
        val os = courseIds[1]
        val se = courseIds[2]
        val network = courseIds[3]
        val math = courseIds[4]
        // FT2026-01 数据结构不预置任何记录，留作端到端演示的课程 A

        db.arrangementDao().insert(
            Arrangement(
                courseId = math, isKeyFocus = true, note = "新开课程，首轮督导",
                createdBy = admin, status = ArrangementStatus.PENDING,
            )
        )

        val osTask = db.taskDao().insert(
            completedTask(os, sup01, "2026-09-16", "3-4", "第3章 进程调度", LocalTime.of(10, 0), LocalTime.of(11, 40))
        )
        val seTask = db.taskDao().insert(
            completedTask(se, sup02, "2026-06-10", "5-6", "第8章 软件测试", LocalTime.of(14, 0), LocalTime.of(15, 40))
        )
        // 督导日期已过仍待开始，US06 显示“已逾期”
        db.taskDao().insert(
            SupervisionTask(
                code = TaskCode.of("2026-09-28", 1), courseId = network, supervisorId = sup01,
                supervisionDate = "2026-09-28", period = "1-2", chapter = null,
                type = SupervisionType.SPECIAL, status = TaskStatus.NOT_STARTED,
                createdAt = at("2026-09-20", LocalTime.of(9, 0)),
            )
        )

        val osEval = db.evaluationDao().upsert(
            submittedEvaluation(
                taskId = osTask, submittedAt = at("2026-09-16", LocalTime.of(16, 0)),
                scores = listOf(5, 4, 3, 4),
                comments = listOf(
                    "知识点讲解准确，案例贴近实际",
                    "环节完整，时间分配略显前紧后松",
                    "提问较多但多为是非题",
                    "前排参与积极，后排关注度不足",
                ),
                strengths = "概念讲解清晰，调度算法动画演示直观",
                suggestions = "增加开放式提问；结合小组讨论调动后排学生",
                overallOpinion = "整体教学效果良好，建议在课堂互动方面重点改进",
            )
        )
        val seEval = db.evaluationDao().upsert(
            submittedEvaluation(
                taskId = seTask, submittedAt = at("2026-06-10", LocalTime.of(17, 0)),
                scores = listOf(5, 5, 4, 5),
                comments = listOf(
                    "内容充实，测试用例设计讲解到位",
                    "以项目驱动组织教学",
                    "互动充分",
                    "学生实践投入度高",
                ),
                strengths = "项目驱动教学，学生动手充分",
                suggestions = "可适当压缩讲授时间",
                overallOpinion = "教学效果优秀",
            )
        )

        listOf(
            EvaluationIssue(evaluationId = osEval, description = "提问以封闭式问题为主，缺少启发性提问", dimension = EvalDimension.INTERACTION, sortOrder = 1),
            EvaluationIssue(evaluationId = osEval, description = "后排学生低头较多，参与度不足", dimension = EvalDimension.ENGAGEMENT, sortOrder = 2),
            EvaluationIssue(evaluationId = seEval, description = "讲授时间偏长，留给答疑的时间不足", dimension = EvalDimension.DESIGN, sortOrder = 1),
        ).forEach { db.evaluationDao().upsertIssue(it) }
    }

    private fun completedTask(
        courseId: Long, supervisorId: Long, date: String, period: String, chapter: String,
        start: LocalTime, end: LocalTime,
    ) = SupervisionTask(
        code = TaskCode.of(date, 1), courseId = courseId, supervisorId = supervisorId,
        supervisionDate = date, period = period, chapter = chapter,
        type = SupervisionType.REGULAR, status = TaskStatus.COMPLETED,
        classStartAt = at(date, start), classEndAt = at(date, end),
        createdAt = at(date, LocalTime.of(8, 0)),
    )

    /** scores、comments 按 EvalDimension 顺序：教学内容、教学设计、课堂互动、学生参与。 */
    private fun submittedEvaluation(
        taskId: Long, submittedAt: Long, scores: List<Int>, comments: List<String>,
        strengths: String, suggestions: String, overallOpinion: String,
    ): Evaluation {
        // 总分和等级用 EvalRules 计算，不手写，保证和评价页一致
        val total = EvalRules.totalScore(EvalDimension.entries.zip(scores).toMap())!!
        return Evaluation(
            taskId = taskId,
            contentScore = scores[0], contentComment = comments[0],
            designScore = scores[1], designComment = comments[1],
            interactionScore = scores[2], interactionComment = comments[2],
            engagementScore = scores[3], engagementComment = comments[3],
            totalScore = total, grade = EvalRules.gradeOf(total),
            strengths = strengths, suggestions = suggestions, overallOpinion = overallOpinion,
            status = EvalStatus.SUBMITTED, submittedAt = submittedAt,
            teacherReadAt = null, updatedAt = submittedAt,
        )
    }

    private fun at(date: String, time: LocalTime): Long =
        LocalDate.parse(date).atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private operator fun <T> List<T>.component6(): T = this[5]
}

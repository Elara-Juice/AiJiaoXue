package edu.neu.aijiaoxue.sprint2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.MainActivity
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.PasswordHasher
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.EvaluationDraft
import edu.neu.aijiaoxue.ui.evaluation.EvaluationRepository
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import edu.neu.aijiaoxue.ui.improve.ImprovementExecutionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity/AppNavHost and login screens; only the initial classroom is a fixture. */
@RunWith(AndroidJUnit4::class)
class Sprint2NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private val suffix = System.nanoTime().toString()
    private val supervisorAccount = "s2nav-sup-$suffix"
    private val teacherAccount = "s2nav-tea-$suffix"
    private val courseName = "S2导航测试课程-$suffix"
    private var supervisor = 0L
    private var teacher = 0L
    private var course = 0L
    private var task = 0L

    @Before fun seedClassroom() {
        rule.waitUntil(20_000) { Session.ready.value }
        Session.signOut(context)
        waitText("登录")
        db = AppDatabase.get(context)
        runBlocking {
            val hash = PasswordHasher.hash("123456")
            val ids = db.userDao().insertAll(listOf(
                User(account = supervisorAccount, passwordHash = hash, name = "导航测试督导", role = Role.SUPERVISOR),
                User(account = teacherAccount, passwordHash = hash, name = "导航测试教师", role = Role.TEACHER),
            ))
            supervisor = ids[0]; teacher = ids[1]
            course = db.courseDao().insertAll(listOf(Course(code = "S2NAV-$suffix", name = courseName,
                teacherId = teacher, className = "测试班", schedule = null, location = null))).single()
            task = db.taskDao().insert(SupervisionTask(code = "S2NAV-$suffix", courseId = course,
                supervisorId = supervisor, supervisionDate = DateFormats.today(), period = "1", type = SupervisionType.REGULAR,
                status = TaskStatus.PENDING_EVALUATION, classStartAt = 1_000, classEndAt = 601_000))
            db.captureDao().insertEvent(InteractionEvent(taskId = task, type = InteractionType.TEACHER_QUESTION,
                occurredAt = 61_000, offsetMs = 60_000, note = "测试课堂提问"))
            db.captureDao().insertEngagement(EngagementRecord(taskId = task, type = EngagementType.LISTENING,
                recordedAt = 121_000, offsetMs = 120_000))
            EvaluationRepository(db).save(supervisor, task, EvaluationDraft(
                Evaluation(taskId = task, contentScore = 4, designScore = 4, interactionScore = 4,
                    engagementScore = 4, overallOpinion = "测试初始人工草稿"),
                listOf(EvaluationIssue(evaluationId = 0, description = "互动较少", dimension = EvalDimension.INTERACTION)),
            ))
        }
    }

    @After fun removeOnlyThisFixture() {
        Session.signOut(context)
        waitText("登录")
        rule.waitForIdle()
        if (!::db.isInitialized) return
        runBlocking { db.withTransaction {
            val sql = db.openHelper.writableDatabase
            // Do not reset shared demo data: remove only this test's rows, child first.
            sql.execSQL("""DELETE FROM improvement_evidences WHERE executionId IN (
                SELECT x.id FROM improvement_executions x JOIN improvement_measures m ON m.id=x.measureId
                JOIN improvement_plans p ON p.id=m.planId WHERE p.taskId=?)""", arrayOf(task))
            sql.execSQL("""DELETE FROM improvement_executions WHERE measureId IN (
                SELECT m.id FROM improvement_measures m JOIN improvement_plans p ON p.id=m.planId
                WHERE p.taskId=?)""", arrayOf(task))
            sql.execSQL("DELETE FROM improvement_plans WHERE taskId = ?", arrayOf(task))
            sql.execSQL("DELETE FROM review_publications WHERE taskId = ?", arrayOf(task))
            sql.execSQL("DELETE FROM evaluations WHERE taskId = ?", arrayOf(task))
            sql.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(task))
            sql.execSQL("DELETE FROM courses WHERE id = ?", arrayOf(course))
            sql.execSQL("DELETE FROM users WHERE id IN (?, ?)", arrayOf(supervisor, teacher))
        } }
    }

    private fun waitText(text: String) = rule.waitUntil(20_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitTag(tag: String) = rule.waitUntil(20_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitDb(condition: suspend () -> Boolean) = rule.waitUntil(20_000) { runBlocking { condition() } }
    private fun clickIn(list: String, matcher: SemanticsMatcher) {
        rule.onNodeWithTag(list).performScrollToNode(matcher)
        rule.onNode(matcher).performClick()
    }
    private fun back() = rule.onNodeWithText("返回").performClick()
    private fun login(account: String) {
        waitText("登录")
        rule.onNode(hasSetTextAction() and hasText("账号")).performTextReplacement(account)
        rule.onNode(hasSetTextAction() and hasText("密码")).performTextReplacement("123456")
        closeSoftKeyboard()
        rule.onNode(hasText("登录") and !hasSetTextAction()).performScrollTo().performClick()
    }

    @Test fun supervisorReviewReturnsToSameDraftAndTeacherConfirmsTraceableAdvice() {
        login(supervisorAccount)
        waitText("我的督导任务")
        rule.onNodeWithText("我的督导任务").performClick()
        waitText("查看材料")
        rule.onNodeWithText("查看材料").performClick()
        waitText("AI 课堂分析")
        rule.onAllNodesWithText("AI 课堂分析").assertCountEquals(1)
        rule.onNodeWithText("AI 课堂分析").performClick()
        waitTag("analysis_page")
        back(); waitText("课堂录音")
        back(); waitText("继续评价")
        rule.onNodeWithText("继续评价").performClick()
        waitTag("evaluation-list")

        // Make a real human draft change before traversing the analysis/review back stack.
        clickIn("evaluation-list", hasTestTag("score-CONTENT-5"))
        val opinion = "导航往返必须保留我的人工意见"
        val opinionField = hasSetTextAction() and hasText("总体意见（必填）")
        rule.onNodeWithTag("evaluation-list").performScrollToNode(opinionField)
        rule.onNode(opinionField).performTextReplacement(opinion)
        closeSoftKeyboard()
        clickIn("evaluation-list", hasTestTag("evaluation-open-analysis"))
        waitTag("analysis_page")
        clickIn("analysis_page", hasTestTag("analysis-generate"))
        awaitDb { db.aiAnalysisDao().getByTask(task).size == AiAnalysisType.entries.size }
        waitText("重新生成模拟分析")
        clickIn("analysis_page", hasTestTag("analysis-review-INTERACTION"))
        waitTag("review-corrected-content")
        val reviewed = "人工核对：互动应先独立思考，再组织同伴讨论。"
        rule.onNodeWithTag("review-corrected-content").performScrollTo().performTextReplacement(reviewed)
        closeSoftKeyboard()
        rule.onNodeWithTag("review-save").performScrollTo().performClick()
        waitText("审核结果已保存")
        back(); waitTag("analysis_page")
        clickIn("analysis_page", hasTestTag("analysis-enter-evaluation"))
        waitTag("evaluation-list")
        // The route can resume before the database-backed reference refresh completes.
        rule.onNodeWithTag("evaluation-list").performScrollToIndex(1)
        waitText("审核后有效内容：$reviewed")
        rule.onNodeWithTag("evaluation-list").performScrollToNode(hasText("审核后有效内容：$reviewed"))
        rule.onNodeWithText("审核后有效内容：$reviewed").assertIsDisplayed()
        rule.onNodeWithTag("evaluation-list").performScrollToNode(hasTestTag("score-CONTENT-5"))
        rule.onNodeWithTag("score-CONTENT-5").assertIsSelected()
        rule.onNodeWithTag("evaluation-list").performScrollToNode(opinionField)
        rule.onNode(opinionField).assertTextContains(opinion)
        clickIn("evaluation-list", hasText("提交评价"))
        rule.onNodeWithText("确认提交").performClick()
        awaitDb { EvaluationRepository(db).load(supervisor, task).draft.evaluation.status == EvalStatus.SUBMITTED }
        rule.onNodeWithTag("evaluation-list").performScrollToIndex(0)
        waitText("评价已提交，内容已锁定")
        val published = runBlocking { EvaluationRepository(db).load(supervisor, task) }
        assertEquals(opinion, published.draft.evaluation.overallOpinion)
        assertEquals(5, published.draft.evaluation.contentScore)
        assertEquals(reviewed, published.reviewedAnalyses.single().effectiveContent)

        // One back reaches tasks, not an extra evaluation instance. Keep main's completed-task entry.
        back(); waitText("AI分析")
        rule.onAllNodesWithTag("evaluation-list").assertCountEquals(0)
        rule.onNodeWithText("AI分析").performClick()
        waitTag("analysis_page")
        back(); waitText("AI分析")
        back(); waitText("我的")
        rule.onNodeWithText("我的").performClick()
        waitText("退出登录")
        rule.onNodeWithText("退出登录").performClick()
        rule.onNodeWithText("退出").performClick()

        login(teacherAccount)
        waitText("督导反馈")
        rule.onNodeWithText("督导反馈").performClick()
        waitText(courseName)
        rule.onNodeWithText(courseName).performClick()
        waitText("确定改进重点")
        rule.onNodeWithText("确定改进重点").performScrollTo().performClick()
        waitTag("focus-list")
        val issue = runBlocking { ImprovementRepository(db).load(teacher, task).issues.single() }
        clickIn("focus-list", hasTestTag("focus-select-${issue.id}"))
        clickIn("focus-list", hasText("保存重点并制定计划"))
        waitTag("plan-list")
        awaitDb { ImprovementRepository(db).load(teacher, task).suggestions.size == 1 }
        rule.onNodeWithTag("plan-list").performScrollToIndex(0)
        waitText("计划草稿已保存")
        val candidate = runBlocking { ImprovementRepository(db).load(teacher, task).suggestions.single() }
        clickIn("plan-list", hasTestTag("suggestion-adopt-${candidate.id}"))
        awaitDb { ImprovementRepository(db).load(teacher, task).measures.singleOrNull()?.suggestionId == candidate.id }
        waitText("已关联下方措施；如需放弃，请先撤销采纳，再放弃此候选建议。")
        clickIn("plan-list", hasText("计划完成日期：请选择"))
        rule.onNode(hasText("15日", substring = true) and hasClickAction(), useUnmergedTree = true).performClick()
        rule.onNodeWithText("确定日期").performClick()
        awaitDb { ImprovementRepository(db).load(teacher, task).measures.single().dueDate.isNotBlank() }
        clickIn("plan-list", hasText("确认计划"))
        rule.onNodeWithText("确认并生成待办").performClick()
        awaitDb { ImprovementRepository(db).load(teacher, task).plan?.status == PlanStatus.CONFIRMED }
        rule.onNodeWithTag("plan-list").performScrollToIndex(0)
        waitText("计划已确认，内容和日期已锁定")
        clickIn("plan-list", hasText("查看改进待办"))
        waitTag("todo-list")
        waitText(courseName)
        rule.onNodeWithTag("todo-list").performScrollToNode(hasText("原建议快照：${candidate.content}"))
        rule.onNodeWithText("原建议快照：${candidate.content}").assertIsDisplayed()
        val measure = runBlocking { ImprovementRepository(db).load(teacher, task).measures.single() }
        clickIn("todo-list", hasTestTag("todo-${measure.id}-IN_PROGRESS"))
        awaitDb { ImprovementRepository(db).load(teacher, task).measures.single().status == MeasureStatus.IN_PROGRESS }
        assertEquals(candidate.reviewVersionKey,
            runBlocking { ImprovementRepository(db).load(teacher, task).suggestions.single().reviewVersionKey })

        // US43/44 extends the real login -> feedback -> confirmed plan -> todo journey.
        clickIn("todo-list", hasTestTag("todo-execution-${measure.id}"))
        waitTag("execution-list")
        for ((tag, text) in listOf("execution-note" to "在本轮课堂完成讨论后的随机汇报",
            "execution-adjustment" to "将教师直接讲解改为学生先解释再由教师补充")) {
            rule.onNodeWithTag("execution-list").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performTextReplacement(text)
            closeSoftKeyboard()
        }
        clickIn("execution-list", hasTestTag("execution-save"))
        awaitDb { ImprovementExecutionRepository(db).load(teacher, task).executions.size == 1 }
        val execution = runBlocking { ImprovementExecutionRepository(db).load(teacher, task).executions.single() }
        rule.onNodeWithTag("execution-list").performScrollToNode(hasTestTag("execution-history-${execution.execution.id}"))
        rule.onNodeWithTag("execution-history-${execution.execution.id}").assertIsDisplayed()
        assertEquals(measure.content, runBlocking { ImprovementRepository(db).load(teacher, task).measures.single().content })
        back(); waitTag("todo-list")
        back(); waitTag("plan-list")
        rule.onNodeWithTag("plan-list").performScrollToNode(hasText("查看改进待办"))
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithText("查看改进待办").assertIsEnabled(); true }.getOrDefault(false)
        }
        back(); waitText("反馈详情")
    }
}

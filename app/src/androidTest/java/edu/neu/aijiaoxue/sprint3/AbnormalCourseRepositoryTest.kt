package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AbnormalCourseRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
    }
    @After fun close() = db.close()
    private suspend fun lesson(score: Int?, date: String, submitted: Boolean = true): Long {
        val task = fixture.task(date = date)
        val id = db.evaluationDao().upsert(Evaluation(taskId = task, totalScore = score,
            status = if (submitted) EvalStatus.SUBMITTED else EvalStatus.DRAFT,
            overallOpinion = "测试正式记录", submittedAt = if (submitted) 1000 else null))
        if (submitted) {
            db.taskDao().updateStatus(task, TaskStatus.COMPLETED)
            db.evaluationDao().upsertIssue(EvaluationIssue(evaluationId = id, description = "本次需要复核的问题"))
        }
        return task
    }
    @Test fun retainsEveryTriggeredClassroomAndSeparatesMissingAndDraftScores() = runBlocking {
        val first = lesson(59, "2026-10-20")
        val second = lesson(40, "2026-10-21")
        lesson(60, "2026-10-22")
        val missing = lesson(null, "2026-10-23")
        val draft = lesson(20, "2026-10-24", false)
        val repository = AbnormalCourseRepository(db)
        val result = repository.load(fixture.admin)
        assertEquals(setOf(first, second), result.groups.single().classrooms.map { it.classroom.taskId }.toSet())
        assertEquals(1, result.normalCount)
        assertEquals(missing, result.undetermined.single().classroom.taskId)
        assertEquals(draft, result.unpublished.single().classroom.taskId)
        assertNull(result.unpublished.single().classroom.totalScore)
        assertTrue(result.groups.single().classrooms.all { it.issues.single().description == "本次需要复核的问题" })
        val filtered = repository.load(fixture.admin, GovernanceFilter(fromDate = "2026-10-21", toDate = "2026-10-22"))
        assertEquals(second, filtered.groups.single().classrooms.single().classroom.taskId)
        assertEquals(1, filtered.normalCount)
    }
    @Test fun teacherAndSupervisorAreRejectedAtRepositoryAndSql() = runBlocking {
        lesson(20, "2026-10-20")
        for (actor in listOf(fixture.teacher, fixture.otherTeacher, fixture.supervisor)) {
            assertTrue(runCatching { AbnormalCourseRepository(db).load(actor) }.isFailure)
            assertTrue(db.governanceDao().abnormalCandidates(actor, null, null, "", "").isEmpty())
        }
    }
}

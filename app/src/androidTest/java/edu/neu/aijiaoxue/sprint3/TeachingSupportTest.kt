package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import edu.neu.aijiaoxue.ui.growth.TeachingSupportRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeachingSupportTest {
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private lateinit var repo: TeachingSupportRepository
    private var taskId = 0L
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        f = Sprint1Fixture(db).also { it.seed() }
        taskId = f.submitted()
        f.focus(taskId)
        repo = TeachingSupportRepository(db)
    }
    @After fun close() { db.close() }
    private suspend fun denied(block: suspend () -> Unit) {
        assertTrue(runCatching { block() }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun highlightPreservesFormalTextAndCanBeReusedInAnotherClass() = runBlocking {
        repo.saveHighlight(f.teacher, taskId, "先给概念再用实例核对理解")
        val next = f.submitted(date = "2026-10-08")
        f.focus(next)
        val loaded = TeachingSupportRepository(db).load(f.teacher, next)
        val highlight = loaded.highlights.single()
        assertEquals("讲解清楚", highlight.sourceText)
        val choice = repo.choices(loaded, loaded.selected!!.items.first()).single { it.key.startsWith("highlight:") }
        val id = repo.adopt(f.teacher, next, loaded.selected.items.first().id, choice.key, choice.content)
        assertEquals(choice.content, f.improvement.load(f.teacher, next).measures.single { it.id == id }.content)
        assertEquals("讲解清楚", db.evaluationDao().getByTask(taskId)!!.strengths)
    }

    @Test fun strategyAdoptionKeepsOriginalEditedAndProblemSourcesAndIsIdempotent() = runBlocking {
        val loaded = repo.load(f.teacher, taskId)
        val item = loaded.selected!!.items.first()
        val choice = repo.choices(loaded, item).first { it.key.startsWith("strategy:") }
        val ids = List(3) { async { repo.adopt(f.teacher, taskId, item.id, choice.key, "增加同伴讨论并记录两组反馈") } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        val reloaded = repo.load(f.teacher, taskId)
        val adoption = reloaded.adoptions.single()
        assertEquals(choice.content, adoption.originalContent)
        assertEquals(item.description, adoption.problemSnapshot)
        assertEquals("增加同伴讨论并记录两组反馈", reloaded.selected!!.measures.first { it.id == ids.first() }.content)
        repo.adopt(f.teacher, taskId, item.id, choice.key, "不应覆盖既有内容")
        assertEquals("增加同伴讨论并记录两组反馈", repo.load(f.teacher, taskId).selected!!.measures.first { it.id == ids.first() }.content)
    }

    @Test fun resourceCanBeSelectedWithVerifiableSource() = runBlocking {
        val loaded = repo.load(f.teacher, taskId)
        val item = loaded.selected!!.items.first()
        val choice = repo.choices(loaded, item).first { it.key.startsWith("resource:") }
        repo.adopt(f.teacher, taskId, item.id, choice.key, choice.content)
        assertTrue(repo.load(f.teacher, taskId).adoptions.single().sourceReference.contains("https://"))
    }

    @Test fun otherTeacherSupervisorAndDraftCannotReadOrWrite() = runBlocking {
        denied { repo.load(f.otherTeacher, taskId) }
        denied { repo.load(f.supervisor) }
        denied { repo.saveHighlight(f.otherTeacher, taskId, "越权") }
        val draft = f.task()
        f.evaluations.save(f.supervisor, draft, f.draft(draft), false)
        denied { repo.load(f.teacher, draft) }
        denied { repo.saveHighlight(f.teacher, draft, "未发布") }
        assertTrue(repo.load(f.otherTeacher).highlights.isEmpty())
    }

    @Test fun confirmedPlanRejectsAdoptionWithoutChangingMeasures() = runBlocking {
        val before = repo.load(f.teacher, taskId)
        val item = before.selected!!.items.first()
        val choice = repo.choices(before, item).first()
        val record = f.improvement.load(f.teacher, taskId)
        f.improvement.saveMeasures(f.teacher, taskId, record.measures.map { it.copy(dueDate = "2026-10-30") }, true)
        val frozen = f.improvement.load(f.teacher, taskId)
        denied { repo.adopt(f.teacher, taskId, item.id, choice.key, choice.content) }
        assertEquals(frozen, f.improvement.load(f.teacher, taskId))
    }

    @Test fun fakeSourceForeignItemAndEmptyEditsRejected() = runBlocking {
        val loaded = repo.load(f.teacher, taskId)
        val item = loaded.selected!!.items.first()
        val choice = repo.choices(loaded, item).first()
        denied { repo.adopt(f.teacher, taskId, item.id, "strategy:fake", "内容") }
        denied { repo.adopt(f.teacher, taskId, Long.MAX_VALUE, choice.key, choice.content) }
        denied { repo.adopt(f.teacher, taskId, item.id, choice.key, " ") }
        denied { repo.saveHighlight(f.teacher, taskId, " ") }
        assertTrue(repo.load(f.teacher, taskId).adoptions.isEmpty())
    }

    @Test fun draftSnapshotCannotOccupyFormalPublicationKey() = runBlocking {
        val draft = f.task(date = "2026-10-09")
        f.evaluations.save(f.supervisor, draft, f.draft(draft), false)
        assertEquals(0, ReviewRepository(db).captureSnapshots(f.supervisor, draft))
        assertTrue(db.metricSnapshotDao().getForTask(f.supervisor, draft).isEmpty())
        f.evaluations.save(f.supervisor, draft, f.draft(draft), true)
        val total = db.metricSnapshotDao().getForTask(f.teacher, draft).single { it.metricKey == MetricKey.EVALUATION_TOTAL }
        assertNotNull(total.value)
    }
}

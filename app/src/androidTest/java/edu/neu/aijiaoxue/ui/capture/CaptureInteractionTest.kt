package edu.neu.aijiaoxue.ui.capture

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US12：一键标注立即入库并记录时间；计数实时更新；5 秒内可撤销最近一条；事件按时间排序，可改备注、可删除。
 */
@RunWith(AndroidJUnit4::class)
class CaptureInteractionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application
    private val db = AppDatabase.get(context)
    private var taskId = 0L
    private lateinit var vm: CaptureViewModel

    @Before
    fun setUp() = runBlocking<Unit> {
        DemoData.seedIfEmpty(db)
        val sup = db.userDao().getByAccount("sup01")!!
        Session.signIn(context, sup)
        val course = db.courseDao().observeRows("").first().first()
        taskId = db.taskDao().create(
            SupervisionTask(
                code = "",
                courseId = course.id,
                supervisorId = sup.id,
                supervisionDate = "2099-01-03",
                period = "1-2",
                type = SupervisionType.REGULAR,
            ),
        )!!
        vm = withContext(Dispatchers.Main) {
            CaptureViewModel(app, SavedStateHandle(mapOf(Routes.ARG_TASK_ID to taskId)))
        }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading && it.canCapture } }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(taskId))
        Session.signOut(context)
    }

    private suspend fun awaitState(predicate: (CaptureUiState) -> Boolean): CaptureUiState =
        withTimeout(5_000) { vm.uiState.first(predicate) }

    @Test
    fun mark_recordsTimeAndCounts() = runBlocking<Unit> {
        val before = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            vm.markEvent(InteractionType.TEACHER_QUESTION)
            vm.markEvent(InteractionType.TEACHER_QUESTION)
            vm.markEvent(InteractionType.STUDENT_ANSWER)
        }
        val state = awaitState { it.events.size == 3 }
        assertEquals(2, state.eventCounts[InteractionType.TEACHER_QUESTION])
        assertEquals(1, state.eventCounts[InteractionType.STUDENT_ANSWER])
        assertNull(state.eventCounts[InteractionType.GROUP_DISCUSSION])

        val start = db.taskDao().getById(taskId)!!.classStartAt!!
        state.events.forEach {
            assertTrue(it.occurredAt >= before)
            assertEquals(it.occurredAt - start, it.offsetMs)
            assertNull(it.note)
        }
        // 验收③：按时间顺序
        assertEquals(state.events.sortedBy { it.occurredAt }, state.events)
        // 只能撤销最近一条
        assertEquals(InteractionType.STUDENT_ANSWER, awaitState { it.undoableEvent != null }.undoableEvent!!.type)
    }

    @Test
    fun undoWithinWindow_removesOnlyLastEvent() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.GROUP_DISCUSSION) }
        awaitState { it.events.size == 1 }
        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.STUDENT_QUESTION) }
        awaitState { it.undoableEvent?.type == InteractionType.STUDENT_QUESTION }

        withContext(Dispatchers.Main) { vm.undoLastEvent() }
        val state = awaitState { it.events.size == 1 }
        assertEquals(InteractionType.GROUP_DISCUSSION, state.events.single().type)
        assertNull(state.eventCounts[InteractionType.STUDENT_QUESTION])
        assertNull(state.undoableEvent)
    }

    @Test
    fun undoEntryExpiresAfterFiveSeconds() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.OTHER) }
        awaitState { it.undoableEvent != null }
        delay(5_500)
        assertNull(vm.uiState.value.undoableEvent)
        // 超时后撤销无效，记录保留
        withContext(Dispatchers.Main) { vm.undoLastEvent() }
        delay(300)
        assertEquals(1, db.captureDao().observeEvents(taskId).first().size)
    }

    /** 点“其他”：先入库，再弹出补充具体内容的对话框；其他类型不弹。 */
    @Test
    fun markOther_savesFirstThenOpensNoteDialog() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.TEACHER_QUESTION) }
        awaitState { it.events.size == 1 }
        assertEquals(0L, vm.editingEventId.value)

        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.OTHER) }
        val other = awaitState { it.events.size == 2 }.events.single { it.type == InteractionType.OTHER }
        withTimeout(5_000) { vm.editingEventId.first { it == other.id } }
        assertTrue(vm.editingEventIsNew.value)

        // 稍后再填：事件保留，备注为空
        withContext(Dispatchers.Main) { vm.dismissEventDialog() }
        assertEquals(0L, vm.editingEventId.value)
        assertNull(db.captureDao().observeEvents(taskId).first().single { it.id == other.id }.note)

        // 撤销条上的“加备注”打开同一对话框，保存后关闭
        withContext(Dispatchers.Main) { vm.editEvent(other.id) }
        assertEquals(other.id, vm.editingEventId.value)
        withContext(Dispatchers.Main) { vm.saveEventNote(other, "随堂测验") }
        assertEquals(0L, vm.editingEventId.value)
        awaitState { s -> s.events.any { it.note == "随堂测验" } }

        // 撤销时关掉这条的对话框
        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.OTHER) }
        val second = withTimeout(5_000) { vm.editingEventId.first { it != 0L && it != other.id } }
        withContext(Dispatchers.Main) { vm.undoLastEvent() }
        assertEquals(0L, vm.editingEventId.value)
        awaitState { s -> s.events.none { it.id == second } }
    }

    /** US07 验收①②：退出后重新进入，已标注的事件、计数、备注都能恢复，课堂计时起点不变。 */
    @Test
    fun reenter_restoresEventsAndKeepsClassStart() = runBlocking<Unit> {
        val start = db.taskDao().getById(taskId)!!.classStartAt!!
        withContext(Dispatchers.Main) {
            vm.markEvent(InteractionType.TEACHER_QUESTION)
            vm.markEvent(InteractionType.STUDENT_ANSWER)
        }
        val event = awaitState { it.events.size == 2 }.events.first()
        withContext(Dispatchers.Main) { vm.saveEventNote(event, "追问") }
        awaitState { it.events.any { e -> e.note == "追问" } }

        // 新建 ViewModel 相当于离开页面或进程被杀后重新进入
        val again = withContext(Dispatchers.Main) {
            CaptureViewModel(app, SavedStateHandle(mapOf(Routes.ARG_TASK_ID to taskId)))
        }
        val restored = withTimeout(5_000) { again.uiState.first { !it.isLoading && it.events.size == 2 } }
        assertEquals(1, restored.eventCounts[InteractionType.TEACHER_QUESTION])
        assertEquals(1, restored.eventCounts[InteractionType.STUDENT_ANSWER])
        assertEquals("追问", restored.events.first().note)
        assertEquals(start, restored.classStartAt)
        // 撤销入口只在本次标注后 5 秒内有效，重新进入不恢复
        assertNull(restored.undoableEvent)
        assertTrue(restored.canCapture)
    }

    @Test
    fun editNoteAndDelete_inRecordList() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { vm.markEvent(InteractionType.TEACHER_QUESTION) }
        val event: InteractionEvent = awaitState { it.events.size == 1 }.events.single()

        withContext(Dispatchers.Main) { vm.saveEventNote(event, "  提问第三节内容  ") }
        val noted = awaitState { it.events.singleOrNull()?.note != null }.events.single()
        assertEquals("提问第三节内容", noted.note)

        // 清空备注存 null（规范 2.2）
        withContext(Dispatchers.Main) { vm.saveEventNote(noted, "   ") }
        awaitState { it.events.singleOrNull()?.note == null }

        withContext(Dispatchers.Main) { vm.deleteEvent(noted) }
        val state = awaitState { it.events.isEmpty() }
        assertTrue(state.eventCounts.isEmpty())
        assertNull(state.undoableEvent)
    }
}

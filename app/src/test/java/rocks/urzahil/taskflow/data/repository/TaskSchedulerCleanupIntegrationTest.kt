package rocks.urzahil.taskflow.data.repository

import androidx.room.Room
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.db.TaskDao
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.SubtaskExclusionEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskSchedulerCleanupIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completedOneOffTasksAreDeletedRegardlessOfScheduledDate() = runBlocking {
        val today = AppDate(2026, 10, 13)
        val pastId = dao.insertTask(task("Completed in past", "2026-10-10"))
        val todayId = dao.insertTask(task("Completed today", today.toIsoString()))
        val futureId = dao.insertTask(task("Completed future task", "2026-10-17"))

        // A historical completion must still identify the one-off task as completed.
        dao.insertCompletion(TaskCompletionEntity(pastId, "2026-10-10"))
        dao.insertCompletion(TaskCompletionEntity(todayId, today.toIsoString()))
        dao.insertCompletion(TaskCompletionEntity(futureId, "2026-10-17"))

        val result = TaskScheduler.cleanupAndRolloverTasks(dao, today)

        assertEquals(3, result.cleanedCount)
        assertNull(dao.getTaskById(pastId))
        assertNull(dao.getTaskById(todayId))
        assertNull(dao.getTaskById(futureId))
        assertTrue(dao.getAllCompletionsList().isEmpty())
    }

    @Test
    fun unfinishedOneOffTaskMovesToTodayNotSameWeekdayNextWeek() = runBlocking {
        val today = AppDate(2026, 10, 13) // Tuesday
        val taskId = dao.insertTask(task("Unfinished one-off", "2026-10-05")) // Previous Monday

        val result = TaskScheduler.cleanupAndRolloverTasks(dao, today)
        val updated = dao.getTaskById(taskId)

        assertEquals(1, result.movedCount)
        assertEquals(today.toIsoString(), updated?.startDate)
        assertEquals(false, updated?.isRecurring)
    }

    @Test
    fun batchRechecksCompletionsBeforeApplyingStaleRolloverCandidate() = runBlocking {
        val today = AppDate(2026, 10, 13)
        val original = task("Completed during cleanup", "2026-10-05")
        val taskId = dao.insertTask(original)
        val staleRolloverCandidate = original.copy(id = taskId, startDate = today.toIsoString())

        // Simulates a completion committed after the scheduler read tasks but before its
        // mutation transaction begins.
        dao.insertCompletion(TaskCompletionEntity(taskId, today.toIsoString()))
        dao.performCleanupAndRolloverBatch(
            tasksToDelete = emptyList(),
            tasksToUpdate = listOf(staleRolloverCandidate),
            todayIso = today.toIsoString()
        )

        assertNull(dao.getTaskById(taskId))
        assertTrue(dao.getAllCompletionsList().isEmpty())
    }

    @Test
    fun cleanupIsIdempotentAfterOneOffTaskHasRolledOver() = runBlocking {
        val today = AppDate(2026, 10, 13)
        val taskId = dao.insertTask(task("Unfinished one-off", "2026-10-05"))

        val first = TaskScheduler.cleanupAndRolloverTasks(dao, today)
        val second = TaskScheduler.cleanupAndRolloverTasks(dao, today)

        assertEquals(1, first.movedCount)
        assertEquals(0, second.movedCount)
        assertEquals(today.toIsoString(), dao.getTaskById(taskId)?.startDate)
    }

    @Test
    fun recurringWeeklyTaskStillAdvancesToItsNextSelectedWeekday() = runBlocking {
        val today = AppDate(2026, 10, 13) // Tuesday
        val weeklyId = dao.insertTask(
            task(
                title = "Weekly Monday",
                startDate = "2026-10-05",
                isRecurring = true,
                recurrenceDaysOfWeek = "1"
            )
        )

        TaskScheduler.cleanupAndRolloverTasks(dao, today)

        assertEquals("2026-10-19", dao.getTaskById(weeklyId)?.startDate)
    }

    @Test
    fun monthlyTaskAdvancesToItsNextDay_clampedToMonthEnd() = runBlocking {
        val today = AppDate(2026, 11, 5)
        val taskId = dao.insertTask(
            TaskEntity(
                title = "Pay rent",
                startDate = "2026-10-31",
                isRecurring = true,
                recurrenceMonthDays = "31"
            )
        )

        val result = TaskScheduler.cleanupAndRolloverTasks(dao, today)

        // 31 October was the only past occurrence; November has no 31st.
        assertEquals(1, result.cleanedRecurringOccurrences)
        assertEquals("2026-11-30", dao.getTaskById(taskId)?.startDate)
    }

    @Test
    fun everyThreeMonthsOnFirstMonday_skipsInactiveMonths() = runBlocking {
        val taskId = dao.insertTask(
            TaskEntity(
                title = "Quarterly review",
                startDate = "2026-11-02",
                isRecurring = true,
                recurrenceMonthWeekdays = "1:1",
                recurrenceMonths = 3
            )
        )

        TaskScheduler.cleanupAndRolloverTasks(dao, AppDate(2026, 11, 3))

        assertEquals("2027-02-01", dao.getTaskById(taskId)?.startDate)
    }

    @Test
    fun monthlyTaskEndingBeforeItsNextOccurrence_isDeleted() = runBlocking {
        val taskId = dao.insertTask(
            TaskEntity(
                title = "Ends soon",
                startDate = "2026-10-15",
                endDate = "2026-11-10",
                isRecurring = true,
                recurrenceMonthDays = "15"
            )
        )

        val result = TaskScheduler.cleanupAndRolloverTasks(dao, AppDate(2026, 11, 1))

        assertEquals(1, result.cleanedCount)
        assertNull(dao.getTaskById(taskId))
    }

    @Test
    fun rolledOverOneOffTaskKeepsItsCompletedSubtasks() = runBlocking {
        val today = AppDate(2026, 10, 13)
        val oldDate = "2026-10-10"
        val taskId = dao.insertTask(task("Partly done", oldDate))
        val doneId = dao.insertSubtask(SubtaskEntity(taskId = taskId, title = "Done", position = 0))
        val openId = dao.insertSubtask(SubtaskEntity(taskId = taskId, title = "Open", position = 1))
        val removedId = dao.insertSubtask(SubtaskEntity(taskId = taskId, title = "Removed", position = 2))
        dao.toggleSubtask(taskId, doneId, oldDate)
        dao.removeSubtaskOn(taskId, removedId, oldDate, shared = true)

        val result = TaskScheduler.cleanupAndRolloverTasks(dao, today)

        val todayIso = today.toIsoString()
        assertEquals(1, result.movedCount)
        assertEquals(todayIso, dao.getTaskById(taskId)?.startDate)
        assertEquals(1, dao.countSubtaskCompletion(doneId, todayIso))
        assertEquals(0, dao.countSubtaskCompletion(openId, todayIso))
        assertTrue(dao.getAllSubtaskCompletions().first().all { it.date == todayIso })
        assertEquals(listOf(doneId, openId), dao.getShownSubtaskIds(taskId, todayIso).sorted())
        assertEquals(
            listOf(SubtaskExclusionEntity(removedId, todayIso)),
            dao.getAllSubtaskExclusions().first()
        )
    }

    @Test
    fun recurringTaskSubtaskChecksStayOnTheirOwnDates() = runBlocking {
        val today = AppDate(2026, 10, 13)
        val taskId = dao.insertTask(task("Daily", "2026-10-10", isRecurring = true))
        val subtaskId = dao.insertSubtask(SubtaskEntity(taskId = taskId, title = "Step"))
        dao.insertSubtaskCompletion(SubtaskCompletionEntity(subtaskId, "2026-10-12"))

        TaskScheduler.cleanupAndRolloverTasks(dao, today)

        assertEquals(1, dao.countSubtaskCompletion(subtaskId, "2026-10-12"))
        assertEquals(0, dao.countSubtaskCompletion(subtaskId, today.toIsoString()))
    }

    private fun task(
        title: String,
        startDate: String,
        isRecurring: Boolean = false,
        recurrenceDaysOfWeek: String? = null
    ) = TaskEntity(
        title = title,
        startDate = startDate,
        isRecurring = isRecurring,
        recurrenceDaysOfWeek = recurrenceDaysOfWeek
    )
}

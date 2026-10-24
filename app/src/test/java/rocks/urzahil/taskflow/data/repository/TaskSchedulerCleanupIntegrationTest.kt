package rocks.urzahil.taskflow.data.repository

import androidx.room.Room
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.db.TaskDao
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
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
            taskIdsForCompletionDeletion = emptyList(),
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

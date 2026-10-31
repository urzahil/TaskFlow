package rocks.urzahil.taskflow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubtaskTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var backupManager: GoogleDriveBackupManager

    private val day1 = "2026-10-01"
    private val day2 = "2026-10-02"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
        backupManager = GoogleDriveBackupManager(context, repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun task(id: Long, recurring: Boolean = false, shared: Boolean = true) = TaskEntity(
        id = id,
        title = "Task $id",
        isRecurring = recurring,
        sharedSubtasks = shared,
        startDate = day1
    )

    private suspend fun completedDates(taskId: Long) =
        repository.allCompletions.first().filter { it.taskId == taskId }.map { it.date }

    private suspend fun addSharedSubtasks(taskId: Long, vararg titles: String): List<SubtaskEntity> {
        repository.replaceSharedSubtasks(
            taskId,
            titles.map { SubtaskEntity(taskId = taskId, title = it) }
        )
        return repository.allSubtasks.first()
    }

    @Test
    fun checkingAllSubtasksCompletesTaskAndUncheckingOneReopensIt() = runBlocking {
        repository.insertTask(task(1))
        val (a, b) = addSharedSubtasks(1, "A", "B")

        repository.toggleSubtask(1, a.id, day1)
        assertTrue(completedDates(1).isEmpty())

        repository.toggleSubtask(1, b.id, day1)
        assertEquals(listOf(day1), completedDates(1))

        repository.toggleSubtask(1, a.id, day1)
        assertTrue(completedDates(1).isEmpty())
    }

    @Test
    fun sharedSubtaskChecksStayOnTheirOwnDate() = runBlocking {
        repository.insertTask(task(1, recurring = true))
        val (a) = addSharedSubtasks(1, "A")

        repository.toggleSubtask(1, a.id, day1)

        assertEquals(listOf(day1), repository.allSubtaskCompletions.first().map { it.date })
        assertEquals(listOf(day1), completedDates(1))
        assertTrue(day2 !in completedDates(1))
    }

    @Test
    fun independentSubtasksBelongToOneDateOnly() = runBlocking {
        repository.insertTask(task(1, recurring = true, shared = false))
        repository.addOccurrenceSubtask(1, day1, "Only today", 0)

        val items = repository.allSubtasks.first()
        assertEquals(listOf(day1), items.map { it.date })
        assertTrue(items.none { it.date == day2 || it.date == null })

        // Checking the only item of that date completes just that occurrence.
        repository.toggleSubtask(1, items.single().id, day1)
        assertEquals(listOf(day1), completedDates(1))
    }

    @Test
    fun deletingAnOccurrenceRemovesThatDatesSubtasks() = runBlocking {
        repository.insertTask(task(1, recurring = true, shared = false))
        repository.addOccurrenceSubtask(1, day1, "Day one", 0)
        repository.addOccurrenceSubtask(1, day2, "Day two", 0)

        repository.deleteRecurringOccurrence(1, day1)

        assertEquals(listOf("Day two"), repository.allSubtasks.first().map { it.title })
    }

    @Test
    fun deletingATaskRemovesItsSubtasksAndChecks() = runBlocking {
        repository.insertTask(task(1))
        val (a) = addSharedSubtasks(1, "A")
        repository.toggleSubtask(1, a.id, day1)

        repository.deleteTask(task(1))

        assertTrue(repository.allSubtasks.first().isEmpty())
        assertTrue(repository.allSubtaskCompletions.first().isEmpty())
    }

    @Test
    fun replacingSharedSubtasksKeepsChecksOfKeptItems() = runBlocking {
        repository.insertTask(task(1))
        val (a, b) = addSharedSubtasks(1, "A", "B")
        repository.toggleSubtask(1, a.id, day1)

        repository.replaceSharedSubtasks(1, listOf(a.copy(title = "A2"), SubtaskEntity(taskId = 1, title = "C")))

        assertEquals(listOf("A2", "C"), repository.allSubtasks.first().map { it.title })
        assertFalse(repository.allSubtasks.first().any { it.id == b.id })
        assertEquals(listOf(a.id), repository.allSubtaskCompletions.first().map { it.subtaskId })
    }

    @Test
    fun addingAnOccurrenceSubtaskReopensThatDayOnly() = runBlocking {
        repository.insertTask(task(1, recurring = true, shared = false))
        repository.insertCompletions(
            listOf(TaskCompletionEntity(taskId = 1, date = day1), TaskCompletionEntity(taskId = 1, date = day2))
        )

        repository.addOccurrenceSubtask(1, day1, "New", 0)

        assertEquals(listOf(day2), completedDates(1))
    }

    @Test
    fun addingASharedSubtaskReopensTodayAndFutureButKeepsThePast() = runBlocking {
        val today = AppDate.today()
        val past = today.minusDays(1).toIsoString()
        val now = today.toIsoString()
        val future = today.plusDays(1).toIsoString()
        repository.insertTask(task(1, recurring = true))
        val (a) = addSharedSubtasks(1, "A")
        repository.insertCompletions(listOf(past, now, future).map { TaskCompletionEntity(taskId = 1, date = it) })

        repository.replaceSharedSubtasks(1, listOf(a, SubtaskEntity(taskId = 1, title = "B")))

        assertEquals(listOf(past), completedDates(1))
    }

    @Test
    fun editingOrRemovingSharedSubtasksDoesNotReopenTheTask() = runBlocking {
        val now = AppDate.today().toIsoString()
        repository.insertTask(task(1, recurring = true))
        val (a, b) = addSharedSubtasks(1, "A", "B")
        repository.insertCompletions(listOf(TaskCompletionEntity(taskId = 1, date = now)))

        repository.replaceSharedSubtasks(1, listOf(a.copy(title = "A2"), b))
        repository.replaceSharedSubtasks(1, listOf(a))

        assertEquals(listOf(now), completedDates(1))
    }

    @Test
    fun backupRoundTripsSubtasksAndSharedFlag() = runBlocking {
        val tasks = listOf(task(1, recurring = true, shared = false), task(2))
        val subtasks = listOf(
            SubtaskEntity(id = 10, taskId = 1, date = day1, title = "Own", position = 0),
            SubtaskEntity(id = 11, taskId = 2, date = null, title = "Shared", position = 1)
        )
        val completions = listOf(SubtaskCompletionEntity(11, day1, 5L))

        val json = backupManager.exportBackupJson(
            tasks, emptyList(), emptyList(), emptyList(), subtasks, completions
        )
        val parsed = backupManager.parseBackupJson(json)

        assertEquals(listOf(false, true), parsed.tasks.map { it.sharedSubtasks })
        assertEquals(subtasks, parsed.subtasks)
        assertEquals(completions, parsed.subtaskCompletions)

        repository.clearAndRestoreAll(
            parsed.tasks, parsed.completions, parsed.categories,
            subtasks = parsed.subtasks, subtaskCompletions = parsed.subtaskCompletions
        )
        assertEquals(subtasks, repository.allSubtasks.first().sortedBy { it.id })
        assertEquals(completions, repository.allSubtaskCompletions.first())
    }

    @Test
    fun versionThreeBackupWithoutSubtasksStillParses() {
        val json = """
            {"version":3,"app":"TaskFlow","tasks":[
              {"id":1,"title":"Old","startDate":"2026-10-01"}
            ],"completions":[],"categories":[]}
        """.trimIndent()

        val parsed = backupManager.parseBackupJson(json)

        assertEquals(1, parsed.tasks.size)
        assertTrue(parsed.tasks.single().sharedSubtasks)
        assertTrue(parsed.subtasks.isEmpty())
        assertTrue(parsed.subtaskCompletions.isEmpty())
    }
}

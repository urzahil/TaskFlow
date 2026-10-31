package rocks.urzahil.taskflow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.data.repository.TaskScheduler
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
@Config(sdk = [34])
class RecurringOccurrenceExclusionTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var backupManager: GoogleDriveBackupManager

    @Before
    fun setUp() {
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

    @Test
    fun deletingOneOccurrenceLeavesOtherOccurrencesAndTheSeriesIntact() = runBlocking {
        val task = TaskEntity(
            id = 900L,
            title = "Daily habit",
            isRecurring = true,
            recurrenceDays = 1,
            startDate = "2026-10-08"
        )
        repository.insertTask(task)
        repository.insertCompletions(
            listOf(TaskCompletionEntity(taskId = 900L, date = "2026-10-09"))
        )

        repository.deleteRecurringOccurrence(900L, "2026-10-09")

        assertTrue(repository.allTasks.first().any { it.id == 900L })
        assertEquals(
            listOf(TaskOccurrenceExclusionEntity(900L, "2026-10-09")),
            repository.allOccurrenceExclusions.first()
        )
        assertFalse(
            repository.allCompletions.first().any {
                it.taskId == 900L && it.date == "2026-10-09"
            }
        )
        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2026, 10, 8)))
        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2026, 10, 10)))
    }

    @Test
    fun occurrenceExclusionSurvivesBackupAndRestore() = runBlocking {
        val task = TaskEntity(
            id = 901L,
            title = "Weekly habit",
            isRecurring = true,
            recurrenceDays = 7,
            startDate = "2026-10-08"
        )
        val exclusion = TaskOccurrenceExclusionEntity(901L, "2026-10-15")
        val category = CategoryEntity("General", 0xFF3B82F6, "general", true)

        repository.clearAndRestoreAll(
            tasks = listOf(task),
            completions = emptyList(),
            categories = listOf(category),
            occurrenceExclusions = listOf(exclusion)
        )

        val json = backupManager.exportBackupJson(
            tasks = repository.allTasks.first(),
            completions = repository.allCompletions.first(),
            categories = repository.allCategories.first(),
            exclusions = repository.allOccurrenceExclusions.first()
        )

        repository.clearAndRestoreAll(emptyList(), emptyList(), emptyList())
        assertTrue(repository.allOccurrenceExclusions.first().isEmpty())

        assertTrue(backupManager.restoreFromJson(json).isSuccess)
        assertEquals(listOf(exclusion), repository.allOccurrenceExclusions.first())
        assertEquals(1, repository.allTasks.first().size)
    }

    @Test
    fun migration4To5CreatesOccurrenceExclusionTable() {
        val db = database.openHelper.writableDatabase
        AppDatabase.MIGRATION_4_5.migrate(db)
        db.execSQL(
            "INSERT INTO tasks (id, title, description, category, priority, colorHex, isRecurring, " +
                "recurrenceDays, startDate, sharedSubtasks, createdAt) " +
                "VALUES (902, 'Recurring', '', 'General', 'Medium', 1, 1, 1, '2026-10-01', 1, 1)"
        )

        db.execSQL(
            "INSERT INTO task_occurrence_exclusions (taskId, date) VALUES (902, '2026-10-20')"
        )
        val cursor = db.query(
            "SELECT taskId, date FROM task_occurrence_exclusions WHERE taskId = 902"
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(902L, it.getLong(0))
            assertEquals("2026-10-20", it.getString(1))
        }
    }
}

package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.CategoryNameConflictException
import com.example.data.db.LastDefaultCategoryException
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.model.TaskOccurrenceExclusionEntity
import com.example.data.repository.TaskRepository
import com.example.data.repository.TaskSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseIntegrityTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun task(id: Long, category: String = "General", color: Long = 1L) = TaskEntity(
        id = id,
        title = "Task $id",
        category = category,
        colorHex = color,
        startDate = "2026-10-01"
    )

    @Test
    fun deletingTaskCascadesToCompletionsAndExclusions() = runBlocking {
        val dao = database.taskDao()
        dao.insertTask(task(1).copy(isRecurring = true))
        dao.insertCompletion(TaskCompletionEntity(1, "2026-10-02"))
        dao.insertOccurrenceExclusion(TaskOccurrenceExclusionEntity(1, "2026-10-03"))

        dao.deleteTaskById(1)

        assertTrue(dao.getAllCompletions().first().isEmpty())
        assertTrue(dao.getAllOccurrenceExclusionsList().isEmpty())
    }

    @Test
    fun togglingCompletionOfMissingTaskDoesNotCrash() = runBlocking {
        repository.toggleCompletion(taskId = 404, dateIso = "2026-10-02", currentlyCompleted = false)
        assertTrue(repository.allCompletions.first().isEmpty())
    }

    @Test
    fun migration5To6DropsOrphansAndKeepsValidRows() {
        val dbName = "migration_5_6_test_db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, description TEXT NOT NULL, category TEXT NOT NULL, " +
                        "priority TEXT NOT NULL, colorHex INTEGER NOT NULL, isRecurring INTEGER NOT NULL, " +
                        "recurrenceDays INTEGER NOT NULL, recurrenceDaysOfWeek TEXT, " +
                        "startDate TEXT NOT NULL, endDate TEXT, createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE task_completions (taskId INTEGER NOT NULL, date TEXT NOT NULL, " +
                        "completedAt INTEGER NOT NULL, PRIMARY KEY (taskId, date))"
                )
                db.execSQL("CREATE INDEX index_task_completions_date ON task_completions (date)")
                db.execSQL(
                    "CREATE TABLE task_occurrence_exclusions (taskId INTEGER NOT NULL, " +
                        "date TEXT NOT NULL, PRIMARY KEY (taskId, date))"
                )
                db.execSQL(
                    "CREATE INDEX index_task_occurrence_exclusions_date ON task_occurrence_exclusions (date)"
                )
                db.execSQL(
                    "CREATE TABLE categories (name TEXT NOT NULL PRIMARY KEY, colorHex INTEGER NOT NULL, " +
                        "iconName TEXT NOT NULL, isDefault INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO tasks VALUES (1, 'Kept', '', 'General', 'Medium', 1, 1, 1, NULL, '2026-10-01', NULL, 1)"
                )
                db.execSQL("INSERT INTO task_completions VALUES (1, '2026-10-02', 5)")
                db.execSQL("INSERT INTO task_completions VALUES (99, '2026-10-02', 5)")
                db.execSQL("INSERT INTO task_occurrence_exclusions VALUES (1, '2026-10-03')")
                db.execSQL("INSERT INTO task_occurrence_exclusions VALUES (99, '2026-10-03')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val completions = migrated.taskDao().getAllCompletions().first()
                assertEquals(listOf(1L), completions.map { it.taskId })
                val exclusions = migrated.taskDao().getAllOccurrenceExclusionsList()
                assertEquals(listOf(1L), exclusions.map { it.taskId })
                // The foreign key is now enforced with cascade.
                migrated.taskDao().deleteTaskById(1)
                assertTrue(migrated.taskDao().getAllCompletions().first().isEmpty())
            }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun addingCategoryWithExistingNameIgnoringCaseIsRejected() = runBlocking {
        repository.insertCategory(CategoryEntity("Work", 1, "work", false))
        assertThrows(CategoryNameConflictException::class.java) {
            runBlocking { repository.addCategory(CategoryEntity("work", 2, "work", false)) }
        }
        assertEquals(1, repository.allCategories.first().size)
    }

    @Test
    fun renamingCategoryOntoAnotherIsRejectedButCaseChangeIsAllowed() = runBlocking {
        repository.insertCategory(CategoryEntity("Work", 1, "work", false))
        repository.insertCategory(CategoryEntity("Home", 2, "home", false))

        assertThrows(CategoryNameConflictException::class.java) {
            runBlocking { repository.updateCategory("Home", "WORK", 2, "home", false) }
        }
        repository.updateCategory("Home", "HOME", 3, "home", false)
        assertTrue(repository.allCategories.first().any { it.name == "HOME" })
        assertTrue(repository.allCategories.first().none { it.name == "Home" })
    }

    @Test
    fun deletingCategoryMovesTasksToTheDefaultCategory() = runBlocking {
        repository.insertCategory(CategoryEntity("Personal", 0xFF8B5CF6, "personal", true))
        repository.insertCategory(CategoryEntity("Work", 0xFF2563EB, "work", false))
        repository.insertTask(task(1, category = "Work", color = 0xFF2563EB))

        repository.deleteCategory("Work")

        val moved = repository.allTasks.first().first()
        assertEquals("Personal", moved.category)
        assertEquals(0xFF8B5CF6, moved.colorHex)
    }

    @Test
    fun deletingLastDefaultGeneralCategoryIsRejected() {
        runBlocking { repository.insertCategory(CategoryEntity("General", 1, "general", true)) }
        assertThrows(LastDefaultCategoryException::class.java) {
            runBlocking { repository.deleteCategory("General") }
        }
    }

    @Test
    fun taskScheduleMatchesIntervalWeekdayAndEndRules() {
        val interval = TaskSchedule.from(
            task(1).copy(isRecurring = true, recurrenceDays = 3, endDate = "2026-10-10")
        )!!
        assertTrue(interval.isScheduledOn(AppDate(2026, 10, 4)))
        assertFalse(interval.isScheduledOn(AppDate(2026, 10, 5)))
        assertFalse(interval.isScheduledOn(AppDate(2026, 10, 13)))

        // 2026-10-05 is a Monday.
        val weekdays = TaskSchedule.from(
            task(2).copy(isRecurring = true, recurrenceDaysOfWeek = "1,3")
        )!!
        assertTrue(weekdays.isScheduledOn(AppDate(2026, 10, 5)))
        assertFalse(weekdays.isScheduledOn(AppDate(2026, 10, 6)))

        assertNull(TaskSchedule.from(task(3).copy(startDate = "garbage")))
        assertNotNull(TaskSchedule.from(task(4)))
    }
}

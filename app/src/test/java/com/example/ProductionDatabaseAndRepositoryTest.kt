package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionDatabaseAndRepositoryTest {

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

    @Test
    fun testRoomMigrationFromV3ToV4PreservesTasksAndCompletions() {
        val dbName = "migration_test_db"
        context.deleteDatabase(dbName)

        val dbV3 = database.openHelper.writableDatabase
        val sqlCreateTasksV3 = """
            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                category TEXT NOT NULL,
                priority TEXT NOT NULL,
                colorHex INTEGER NOT NULL,
                isRecurring INTEGER NOT NULL,
                recurrenceDays INTEGER NOT NULL,
                startDate TEXT NOT NULL,
                endDate TEXT,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent()
        val sqlCreateCompletionsV3 = """
            CREATE TABLE IF NOT EXISTS task_completions (
                taskId INTEGER NOT NULL,
                date TEXT NOT NULL,
                completedAt INTEGER NOT NULL,
                PRIMARY KEY (taskId, date)
            )
        """.trimIndent()
        val sqlCreateCategoriesV3 = """
            CREATE TABLE IF NOT EXISTS categories (
                name TEXT NOT NULL PRIMARY KEY,
                colorHex INTEGER NOT NULL,
                iconName TEXT NOT NULL,
                isDefault INTEGER NOT NULL
            )
        """.trimIndent()

        val helper = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(sqlCreateTasksV3)
                db.execSQL(sqlCreateCompletionsV3)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_completions_date ON task_completions (date)")
                db.execSQL(sqlCreateCategoriesV3)

                db.execSQL(
                    "INSERT INTO tasks (id, title, description, category, priority, colorHex, isRecurring, recurrenceDays, startDate, endDate, createdAt) " +
                            "VALUES (101, 'Existing V3 Task', 'Description', 'General', 'High', 4282098422, 0, 1, '2026-09-25', NULL, 1000)"
                )
                db.execSQL(
                    "INSERT INTO task_completions (taskId, date, completedAt) VALUES (101, '2026-09-25', 1005)"
                )
                db.execSQL(
                    "INSERT INTO categories (name, colorHex, iconName, isDefault) VALUES ('Work', 4282098422, 'work', 0)"
                )
            }

            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
            }
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(helper)
            .build()
        val openHelper =
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
        val preDb = openHelper.writableDatabase
        assertEquals(3, preDb.version)
        preDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            val tasks = migratedDb.taskDao().getAllTasks().first()
            assertEquals(1, tasks.size)
            val task = tasks[0]
            assertEquals(101L, task.id)
            assertEquals("Existing V3 Task", task.title)
            assertEquals(null, task.recurrenceDaysOfWeek)

            val completions = migratedDb.taskDao().getAllCompletions().first()
            assertEquals(1, completions.size)
            assertEquals(101L, completions[0].taskId)
            assertEquals("2026-09-25", completions[0].date)

            val categories = migratedDb.taskDao().getAllCategories().first()
            assertEquals(1, categories.size)
            assertEquals("Work", categories[0].name)
        }

        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testProductionRolloverWithInvalidRecurrenceDaysOfWeekDoesNotHang() = runBlocking {
        val invalidTask = TaskEntity(
            id = 201,
            title = "Invalid Weekday Task",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "8",
            startDate = "2026-09-10"
        )
        repository.insertTask(invalidTask)

        val today = AppDate(2026, 9, 25)
        val result = repository.cleanupAndRolloverTasks(today)
        assertTrue(result.cleanedRecurringOccurrences >= 1)

        val updated = repository.allTasks.first().first { it.id == 201L }
        val updatedStart = AppDate.parseIso(updated.startDate)
        assertTrue("Start date should be moved on or after today", updatedStart >= today)
        assertEquals(3, result.cleanedRecurringOccurrences)
        assertEquals(0, repository.cleanupAndRolloverTasks(today).cleanedRecurringOccurrences)
    }

    @Test
    fun testProductionRolloverCountsRemovedWeekdayOccurrencesCorrectly() = runBlocking {
        val mwfTask = TaskEntity(
            id = 301,
            title = "Gym MWF",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "1,3,5",
            startDate = "2026-09-21"
        )
        repository.insertTask(mwfTask)

        val today = AppDate(2026, 9, 25)
        val result = repository.cleanupAndRolloverTasks(today)

        assertEquals(
            "Should clean exactly 2 occurrences (Monday and Wednesday)",
            2,
            result.cleanedRecurringOccurrences
        )

        val updated = repository.allTasks.first().first { it.id == 301L }
        assertEquals("Next occurrence should be Friday Sep 25", "2026-09-25", updated.startDate)
    }

    @Test
    fun testProductionRolloverWithOptionalEndDate() = runBlocking {
        val expiredTask = TaskEntity(
            id = 401,
            title = "Past Course",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "2",
            startDate = "2026-09-01",
            endDate = "2026-09-15"
        )
        repository.insertTask(expiredTask)

        val today = AppDate(2026, 9, 25)
        val result = repository.cleanupAndRolloverTasks(today)

        assertEquals(1, result.cleanedCount)
        assertEquals(
            "Only September 1, 8 and 15 were scheduled",
            3,
            result.cleanedRecurringOccurrences
        )
        assertTrue(repository.allTasks.first().none { it.id == 401L })
    }

    @Test
    fun testFutureNonRecurringTaskSurvivesRollover() = runBlocking {
        val futureTask = TaskEntity(
            id = 601,
            title = "Future task",
            isRecurring = false,
            startDate = "2026-09-28"
        )
        repository.insertTask(futureTask)

        repository.cleanupAndRolloverTasks(AppDate(2026, 9, 25))

        val remaining = repository.allTasks.first().firstOrNull { it.id == 601L }
        assertNotNull(remaining)
        assertEquals("2026-09-28", remaining!!.startDate)
    }

    @Test
    fun testFutureRecurringTaskSurvivesRolloverUnchanged() = runBlocking {
        val futureTask = TaskEntity(
            id = 602,
            title = "Future recurring task",
            isRecurring = true,
            recurrenceDays = 7,
            startDate = "2026-09-28",
            endDate = "2026-10-30"
        )
        repository.insertTask(futureTask)

        repository.cleanupAndRolloverTasks(AppDate(2026, 9, 25))

        val remaining = repository.allTasks.first().firstOrNull { it.id == 602L }
        assertNotNull(remaining)
        assertEquals("2026-09-28", remaining!!.startDate)
        assertEquals("2026-10-30", remaining.endDate)
    }

    @Test
    fun testCompletedTodayNonRecurringTaskIsDeleted() = runBlocking {
        val task = TaskEntity(
            id = 603,
            title = "Today's completed task",
            isRecurring = false,
            startDate = "2026-09-25"
        )
        repository.insertTask(task)
        repository.insertCompletions(
            listOf(TaskCompletionEntity(taskId = 603, date = "2026-09-25"))
        )

        val result = repository.cleanupAndRolloverTasks(AppDate(2026, 9, 25))

        assertEquals(1, result.cleanedCount)
        assertTrue(repository.allTasks.first().none { it.id == 603L })
        assertTrue(repository.allCompletions.first().none { it.taskId == 603L })
    }

    @Test
    fun testFutureCompletionMakesPastNonRecurringTaskCompletedAndDeleted() = runBlocking {
        val pastTask = TaskEntity(
            id = 604,
            title = "Past task with future completion",
            isRecurring = false,
            startDate = "2026-09-24"
        )
        repository.insertTask(pastTask)
        repository.insertCompletions(
            listOf(TaskCompletionEntity(taskId = 604, date = "2026-09-28"))
        )

        val result = repository.cleanupAndRolloverTasks(AppDate(2026, 9, 25))

        assertEquals(1, result.cleanedCount)
        assertTrue(repository.allTasks.first().none { it.id == 604L })
        assertTrue(repository.allCompletions.first().none { it.taskId == 604L })
    }

    @Test
    fun testFutureCompletedNonRecurringTaskIsDeleted() = runBlocking {
        val futureTask = TaskEntity(
            id = 605,
            title = "Future completed task",
            isRecurring = false,
            startDate = "2026-09-28"
        )
        repository.insertTask(futureTask)
        repository.insertCompletions(
            listOf(TaskCompletionEntity(taskId = 605, date = "2026-09-30"))
        )

        val result = repository.cleanupAndRolloverTasks(AppDate(2026, 9, 25))

        assertEquals(1, result.cleanedCount)
        assertTrue(repository.allTasks.first().none { it.id == 605L })
        assertTrue(repository.allCompletions.first().none { it.taskId == 605L })
    }

    @Test
    fun testCompletedRecurringTaskIsRetained() = runBlocking {
        val recurringTask = TaskEntity(
            id = 606,
            title = "Completed recurring task",
            isRecurring = true,
            recurrenceDays = 1,
            startDate = "2026-09-20"
        )
        repository.insertTask(recurringTask)
        repository.insertCompletions(
            listOf(TaskCompletionEntity(taskId = 606, date = "2026-09-24"))
        )

        repository.cleanupAndRolloverTasks(AppDate(2026, 9, 25))

        val remaining = repository.allTasks.first().firstOrNull { it.id == 606L }
        assertNotNull(remaining)
        assertEquals("2026-09-25", remaining!!.startDate)
    }

    @Test
    fun testDeletingCategoryReassignsTasksToGeneralColor() = runBlocking {
        repository.insertCategory(
            CategoryEntity(
                name = "Temporary",
                colorHex = 0xFFEF4444,
                iconName = "work",
                isDefault = false
            )
        )
        repository.insertTask(
            TaskEntity(
                id = 607,
                title = "Reassigned task",
                category = "Temporary",
                colorHex = 0xFFEF4444,
                startDate = "2026-09-25"
            )
        )

        repository.deleteCategory("Temporary")

        val task = repository.allTasks.first().first { it.id == 607L }
        assertEquals("General", task.category)
        assertEquals(0xFF3B82F6, task.colorHex)
        assertTrue(repository.allCategories.first().none { it.name == "Temporary" })
    }

    @Test
    fun testRepositoryIsTaskScheduledOnDateProduction() {
        val mwfTask = TaskEntity(
            id = 501,
            title = "Study MWF",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "1,3,5",
            startDate = "2026-09-21"
        )

        val mon = AppDate(2026, 9, 21)
        val tue = AppDate(2026, 9, 22)
        val wed = AppDate(2026, 9, 23)
        val thu = AppDate(2026, 9, 24)
        val fri = AppDate(2026, 9, 25)
        val sat = AppDate(2026, 9, 26)
        val sun = AppDate(2026, 9, 27)

        assertTrue(repository.isTaskScheduledOnDate(mwfTask, mon))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, tue))
        assertTrue(repository.isTaskScheduledOnDate(mwfTask, wed))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, thu))
        assertTrue(repository.isTaskScheduledOnDate(mwfTask, fri))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, sat))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, sun))
    }
}

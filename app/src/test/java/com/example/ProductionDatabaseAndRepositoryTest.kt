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

        // 1. Create database directly at version 3 schema
        val dbV3 = database.openHelper.writableDatabase
        // Create tables as they existed in version 3 (without recurrenceDaysOfWeek)
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
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                taskId INTEGER NOT NULL,
                date TEXT NOT NULL,
                completedAt INTEGER NOT NULL
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

        // Insert mock data into version 3
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
                    "INSERT INTO task_completions (id, taskId, date, completedAt) VALUES (501, 101, '2026-09-25', 1005)"
                )
                db.execSQL(
                    "INSERT INTO categories (name, colorHex, iconName, isDefault) VALUES ('Work', 4282098422, 'work', 0)"
                )
            }

            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(helper)
            .build()
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
        val preDb = openHelper.writableDatabase
        assertEquals(3, preDb.version)
        preDb.close()

        // 2. Open using Room at version 4 with MIGRATION_3_4
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            val tasks = migratedDb.taskDao().getAllTasks().first()
            assertEquals(1, tasks.size)
            val task = tasks[0]
            assertEquals(101L, task.id)
            assertEquals("Existing V3 Task", task.title)
            assertEquals(null, task.recurrenceDaysOfWeek) // New column defaulted to null

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
        // Task with invalid weekday "8"
        val invalidTask = TaskEntity(
            id = 201,
            title = "Invalid Weekday Task",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "8",
            startDate = "2026-09-10" // past date
        )
        repository.insertTask(invalidTask)

        val today = AppDate(2026, 9, 25)
        // Must complete without hanging or infinite loop
        val result = repository.cleanupAndRolloverTasks(today)
        assertTrue(result.cleanedRecurringOccurrences >= 1)

        val updated = repository.allTasks.first().first { it.id == 201L }
        val updatedStart = AppDate.parseIso(updated.startDate)
        assertTrue("Start date should be moved on or after today", updatedStart >= today)
    }

    @Test
    fun testProductionRolloverCountsRemovedWeekdayOccurrencesCorrectly() = runBlocking {
        // Monday (1), Wednesday (3), Friday (5) starting Monday Sep 21, 2026
        // Sep 21 = Mon (occurrence 1)
        // Sep 22 = Tue
        // Sep 23 = Wed (occurrence 2)
        // Sep 24 = Thu
        // Rollover on Friday Sep 25, 2026
        // Past days before Sep 25 with scheduled occurrences: Sep 21 (Mon), Sep 23 (Wed) -> 2 occurrences
        val mwfTask = TaskEntity(
            id = 301,
            title = "Gym MWF",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "1,3,5",
            startDate = "2026-09-21"
        )
        repository.insertTask(mwfTask)

        val today = AppDate(2026, 9, 25) // Friday
        val result = repository.cleanupAndRolloverTasks(today)

        assertEquals("Should clean exactly 2 occurrences (Monday and Wednesday)", 2, result.cleanedRecurringOccurrences)

        val updated = repository.allTasks.first().first { it.id == 301L }
        assertEquals("Next occurrence should be Friday Sep 25", "2026-09-25", updated.startDate)
    }

    @Test
    fun testProductionRolloverWithOptionalEndDate() = runBlocking {
        // Recurring Tuesday (2) task started on Sep 1, ending on Sep 15
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
        assertTrue(repository.allTasks.first().none { it.id == 401L })
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

        val mon = AppDate(2026, 9, 21) // Mon -> 1
        val tue = AppDate(2026, 9, 22) // Tue -> 2
        val wed = AppDate(2026, 9, 23) // Wed -> 3
        val thu = AppDate(2026, 9, 24) // Thu -> 4
        val fri = AppDate(2026, 9, 25) // Fri -> 5
        val sat = AppDate(2026, 9, 26) // Sat -> 6
        val sun = AppDate(2026, 9, 27) // Sun -> 7

        assertTrue(repository.isTaskScheduledOnDate(mwfTask, mon))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, tue))
        assertTrue(repository.isTaskScheduledOnDate(mwfTask, wed))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, thu))
        assertTrue(repository.isTaskScheduledOnDate(mwfTask, fri))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, sat))
        assertFalse(repository.isTaskScheduledOnDate(mwfTask, sun))
    }
}

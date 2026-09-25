package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.model.AppDate
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import com.example.ui.TaskViewModel
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
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskViewModelSchedulePerformanceTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var driveBackupManager: GoogleDriveBackupManager
    private lateinit var viewModel: TaskViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
        driveBackupManager = GoogleDriveBackupManager(context, repository)
        viewModel = TaskViewModel(repository, driveBackupManager, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testLargeTaskSetPerformanceAndConsistency() = runBlocking {
        // Seed 1500 tasks with various recurrences and completions
        val tasks = mutableListOf<TaskEntity>()
        val completions = mutableListOf<TaskCompletionEntity>()
        val baseDate = AppDate(2026, 9, 1)

        for (i in 1..1500) {
            val isRecurring = (i % 2 == 0)
            val recurrenceDays = when {
                i % 6 == 0 -> 7
                i % 3 == 0 -> 3
                else -> 1
            }
            val recurrenceDaysOfWeek = if (i % 5 == 0) "1,3,5" else null
            val start = baseDate.plusDays((i % 20).toLong()).toIsoString()

            tasks.add(
                TaskEntity(
                    id = i.toLong(),
                    title = "Task #$i",
                    description = "Description for task $i",
                    category = if (i % 4 == 0) "Work" else "General",
                    priority = if (i % 3 == 0) "High" else "Medium",
                    colorHex = if (i % 2 == 0) 0xFF3B82F6 else 0xFF10B981,
                    isRecurring = isRecurring,
                    recurrenceDays = recurrenceDays,
                    recurrenceDaysOfWeek = recurrenceDaysOfWeek,
                    startDate = start
                )
            )

            if (i % 3 == 0) {
                completions.add(
                    TaskCompletionEntity(
                        taskId = i.toLong(),
                        date = "2026-09-25",
                        completedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        database.taskDao().insertTasks(tasks)
        database.taskDao().insertCompletions(completions)

        // Select specific date: Sep 25, 2026
        viewModel.selectDate(AppDate(2026, 9, 25))

        val elapsedDailyTasks = measureTimeMillis {
            val dailyList = viewModel.dailyTasks.first()
            val stats = viewModel.dailyStats.first()
            assertTrue(dailyList.isNotEmpty())
            assertTrue(stats.total > 0)
            assertEquals(dailyList.size, stats.total)
            assertEquals(dailyList.count { it.isCompleted }, stats.completed)
        }

        // Measure month summaries calculation
        viewModel.setViewMode(com.example.ui.model.ViewMode.MONTHLY)
        viewModel.selectMonth(2026, 9)

        val elapsedMonthSummary = measureTimeMillis {
            val summaries = viewModel.monthDaysSummary.first()
            assertTrue(summaries.isNotEmpty())
            val sep25Summary = summaries["2026-09-25"]
            if (sep25Summary != null) {
                assertTrue(sep25Summary.taskColors.size <= 3)
            }
        }

        // Both operations should complete well under 1000ms even with 1500 tasks
        assertTrue("Daily tasks evaluation should be fast ($elapsedDailyTasks ms)", elapsedDailyTasks < 1000)
        assertTrue("Month summary evaluation should be fast ($elapsedMonthSummary ms)", elapsedMonthSummary < 1000)
    }

    @Test
    fun testDurableBackupFlagBehavior() {
        assertFalse(driveBackupManager.isBackupPendingDurable())
        driveBackupManager.setBackupPendingDurable(true)
        assertTrue(driveBackupManager.isBackupPendingDurable())
        driveBackupManager.setBackupPendingDurable(false)
        assertFalse(driveBackupManager.isBackupPendingDurable())
    }
}

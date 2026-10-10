package com.example

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.model.AppDate
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import com.example.ui.TaskViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyStatsOccurrenceExclusionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TaskViewModel
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskflow_user_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("rollover_cleanup_enabled", false).commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
        val backupManager = GoogleDriveBackupManager(context, repository)

        // Prevent startup seeding from racing with the test data.
        runBlocking {
            repository.insertTask(
                TaskEntity(
                    id = 9999,
                    title = "Fixture",
                    startDate = "2099-01-01"
                )
            )
        }
        viewModel = ViewModelProvider(
            viewModelStore,
            TaskViewModel.Factory(repository, backupManager, context)
        ).get(TaskViewModel::class.java)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun dailyStatsMatchVisibleTasksForTodayAndOtherDates() = runBlocking {
        val today = AppDate.today()
        val yesterday = today.minusDays(1)
        val todayIso = today.toIsoString()

        repository.insertTasks(
            listOf(
                TaskEntity(
                    id = 1,
                    title = "Completed daily task",
                    isRecurring = true,
                    recurrenceDays = 1,
                    startDate = yesterday.toIsoString()
                ),
                TaskEntity(
                    id = 2,
                    title = "Deleted daily occurrence",
                    isRecurring = true,
                    recurrenceDays = 1,
                    startDate = yesterday.toIsoString()
                )
            )
        )
        repository.insertCompletions(
            listOf(
                TaskCompletionEntity(
                    taskId = 1,
                    date = todayIso,
                    completedAt = System.currentTimeMillis()
                )
            )
        )
        repository.deleteRecurringOccurrence(taskId = 2, dateIso = todayIso)

        viewModel.selectDate(today)
        val todayTasks = withTimeout(10_000) { viewModel.dailyTasks.first { it.size == 1 } }
        val todayStats = withTimeout(10_000) { viewModel.dailyStats.first { it.total == 1 } }

        assertEquals(1, todayTasks.size)
        assertEquals(1, todayStats.total)
        assertEquals(1, todayStats.completed)
        assertEquals(1, todayTasks.count { it.isCompleted })

        // The exclusion is specific to today; yesterday still includes both occurrences.
        viewModel.selectDate(yesterday)
        val yesterdayTasks = withTimeout(10_000) { viewModel.dailyTasks.first { it.size == 2 } }
        val yesterdayStats = withTimeout(10_000) { viewModel.dailyStats.first { it.total == 2 } }

        assertEquals(2, yesterdayTasks.size)
        assertEquals(2, yesterdayStats.total)
        assertEquals(0, yesterdayStats.completed)
        assertEquals(yesterdayTasks.size, yesterdayStats.total)
        assertEquals(yesterdayTasks.count { it.isCompleted }, yesterdayStats.completed)
    }
}

package rocks.urzahil.taskflow

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.TaskViewModel
import rocks.urzahil.taskflow.ui.monthly.monthGridDates

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskViewModelSearchTest {

    private lateinit var database: AppDatabase
    private lateinit var viewModel: TaskViewModel
    private val viewModelStore = ViewModelStore()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("taskflow_user_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("rollover_cleanup_enabled", false).commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = TaskRepository(database.taskDao())
        runBlocking {
            // Keeps startup seeding from adding starter tasks.
            repository.insertTask(TaskEntity(id = 9999, title = "Fixture", startDate = "2099-01-01"))
            repository.insertTask(TaskEntity(id = 1, title = "Dentist", startDate = "2026-09-30"))
            repository.insertTask(TaskEntity(id = 2, title = "Gym", startDate = "2026-09-30"))
            database.taskDao().insertSubtask(SubtaskEntity(taskId = 1, title = "Bring insurance card"))
        }
        viewModel = ViewModelProvider(
            viewModelStore,
            TaskViewModel.Factory(repository, GoogleDriveBackupManager(context, repository), context)
        ).get(TaskViewModel::class.java)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun gridIncludesAdjacentMonthDays() {
        val grid = monthGridDates(2026, 10) // October 2026 starts on a Thursday
        assertEquals(AppDate(2026, 9, 28), grid.first())
        assertEquals(0, grid.size % 7)
        assertTrue(AppDate(2026, 11, 1) in grid)
    }

    @Test
    fun monthSummaryCoversLeadingDaysOfThePreviousMonth() = runBlocking {
        viewModel.selectMonth(2026, 10)

        val summary = withTimeout(10_000) {
            viewModel.monthDaysSummary.first { it.containsKey("2026-09-30") }
        }

        assertEquals(2, summary.getValue("2026-09-30").totalTasks)
    }

    @Test
    fun searchMatchesSubtaskTitles() = runBlocking {
        viewModel.selectDate(AppDate(2026, 9, 30))
        viewModel.setSearchQuery("insurance")

        val tasks = withTimeout(10_000) { viewModel.dailyTasks.first { it.size == 1 } }

        assertEquals("Dentist", tasks.single().task.title)
    }

    @Test
    fun searchNarrowsTheMonthlySelectedDayAndCalendarDots() = runBlocking {
        viewModel.selectDate(AppDate(2026, 9, 30))
        viewModel.selectMonth(2026, 10)
        viewModel.setSearchQuery("gym")

        val dayTasks = withTimeout(10_000) { viewModel.monthlySelectedDayTasks.first { it.size == 1 } }
        val summary = withTimeout(10_000) {
            viewModel.monthDaysSummary.first { it["2026-09-30"]?.totalTasks == 1 }
        }

        assertEquals("Gym", dayTasks.single().task.title)
        assertEquals(1, summary.getValue("2026-09-30").totalTasks)
        // The day's overall progress stat is not affected by the search.
        assertEquals(2, withTimeout(10_000) { viewModel.dailyStats.first { it.total == 2 } }.total)
    }
}

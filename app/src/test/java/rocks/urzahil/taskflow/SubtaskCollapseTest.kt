package rocks.urzahil.taskflow

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.TaskViewModel

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtaskCollapseTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var driveBackupManager: GoogleDriveBackupManager
    private val viewModelStores = mutableListOf<ViewModelStore>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskflow_user_prefs", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("rollover_cleanup_enabled", false)
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
        driveBackupManager = GoogleDriveBackupManager(context, repository)
        // Keep startup seeding from racing the test fixtures.
        runBlocking {
            repository.insertTask(TaskEntity(id = 9999, title = "Fixture", startDate = "2099-01-01"))
        }
    }

    @After
    fun tearDown() {
        viewModelStores.forEach { it.clear() }
        database.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): TaskViewModel {
        val store = ViewModelStore().also { viewModelStores += it }
        return ViewModelProvider(store, TaskViewModel.Factory(repository, driveBackupManager, context))
            .get(TaskViewModel::class.java)
    }

    @Test
    fun toggleSubtasksCollapsed_flipsStateAndPersistsAcrossViewModels() {
        val viewModel = newViewModel()
        assertTrue(viewModel.collapsedSubtaskTaskIds.value.isEmpty())

        viewModel.toggleSubtasksCollapsed(42L)
        viewModel.toggleSubtasksCollapsed(7L)
        assertEquals(setOf(42L, 7L), viewModel.collapsedSubtaskTaskIds.value)

        viewModel.toggleSubtasksCollapsed(7L)
        assertEquals(setOf(42L), viewModel.collapsedSubtaskTaskIds.value)

        // A fresh ViewModel (e.g. after an app restart) reads the saved state back.
        assertEquals(setOf(42L), newViewModel().collapsedSubtaskTaskIds.value)
    }

    @Test
    fun deletingTask_dropsItsCollapsedState() {
        val viewModel = newViewModel()
        val task = TaskEntity(id = 5, title = "Collapsible", startDate = "2026-10-01")
        runBlocking { repository.insertTask(task) }

        viewModel.toggleSubtasksCollapsed(task.id)
        assertTrue(task.id in viewModel.collapsedSubtaskTaskIds.value)

        viewModel.deleteTask(task)
        assertFalse(task.id in viewModel.collapsedSubtaskTaskIds.value)
    }
}

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
import rocks.urzahil.taskflow.ui.model.ViewMode

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

    private fun TaskViewModel.overrides(view: ViewMode) = subtaskCollapseOverrides.value[view].orEmpty()

    @Test
    fun toggleSubtasksCollapsed_flipsStateAndPersistsAcrossViewModels() {
        val viewModel = newViewModel()
        assertTrue(viewModel.overrides(ViewMode.DAILY).isEmpty())

        viewModel.toggleSubtasksCollapsed(ViewMode.DAILY, 42L)
        viewModel.toggleSubtasksCollapsed(ViewMode.DAILY, 7L)
        assertEquals(setOf(42L, 7L), viewModel.overrides(ViewMode.DAILY))

        viewModel.toggleSubtasksCollapsed(ViewMode.DAILY, 7L)
        assertEquals(setOf(42L), viewModel.overrides(ViewMode.DAILY))

        // A fresh ViewModel (e.g. after an app restart) reads the saved state back.
        assertEquals(setOf(42L), newViewModel().overrides(ViewMode.DAILY))
    }

    @Test
    fun eachViewKeepsItsOwnCollapseState() {
        val viewModel = newViewModel()

        viewModel.toggleSubtasksCollapsed(ViewMode.DAILY, 1L)
        viewModel.toggleSubtasksCollapsed(ViewMode.LIST, 2L)

        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.DAILY, 1L))
        assertFalse(viewModel.isSubtasksCollapsed(ViewMode.MONTHLY, 1L))
        assertFalse(viewModel.isSubtasksCollapsed(ViewMode.LIST, 1L))
        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.LIST, 2L))
        assertFalse(viewModel.isSubtasksCollapsed(ViewMode.DAILY, 2L))

        val restarted = newViewModel()
        assertEquals(setOf(1L), restarted.overrides(ViewMode.DAILY))
        assertTrue(restarted.overrides(ViewMode.MONTHLY).isEmpty())
        assertEquals(setOf(2L), restarted.overrides(ViewMode.LIST))
    }

    @Test
    fun legacySharedCollapseState_isDiscarded() {
        val prefs = context.getSharedPreferences("taskflow_user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("collapsed_subtask_task_ids", setOf("3")).commit()

        val viewModel = newViewModel()
        TaskViewModel.SUBTASK_COLLAPSE_VIEWS.forEach { assertTrue(viewModel.overrides(it).isEmpty()) }
        assertFalse(prefs.contains("collapsed_subtask_task_ids"))
    }

    @Test
    fun deletingTask_dropsItsCollapsedStateInEveryView() {
        val viewModel = newViewModel()
        val task = TaskEntity(id = 5, title = "Collapsible", startDate = "2026-10-01")
        runBlocking { repository.insertTask(task) }

        viewModel.toggleSubtasksCollapsed(ViewMode.DAILY, task.id)
        viewModel.toggleSubtasksCollapsed(ViewMode.MONTHLY, task.id)
        assertTrue(task.id in viewModel.overrides(ViewMode.DAILY))

        viewModel.deleteTask(task)
        TaskViewModel.SUBTASK_COLLAPSE_VIEWS.forEach { assertFalse(task.id in viewModel.overrides(it)) }
    }

    @Test
    fun collapsedByDefault_collapsesUntoggledTasksAndPersists() {
        val viewModel = newViewModel()
        assertFalse(viewModel.isSubtasksCollapsed(ViewMode.DAILY, 1L))

        viewModel.toggleSubtasksCollapsed(ViewMode.DAILY, 1L)
        viewModel.toggleSubtasksCollapsed(ViewMode.LIST, 1L)
        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.DAILY, 1L))

        // Switching the default applies to every task in every view and drops earlier toggles.
        viewModel.setSubtasksCollapsedByDefault(true)
        TaskViewModel.SUBTASK_COLLAPSE_VIEWS.forEach { assertTrue(viewModel.overrides(it).isEmpty()) }
        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.DAILY, 1L))
        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.LIST, 1L))
        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.MONTHLY, 2L))

        // Toggling a task now expands it, in that view only.
        viewModel.toggleSubtasksCollapsed(ViewMode.MONTHLY, 2L)
        assertFalse(viewModel.isSubtasksCollapsed(ViewMode.MONTHLY, 2L))
        assertTrue(viewModel.isSubtasksCollapsed(ViewMode.DAILY, 2L))

        val restarted = newViewModel()
        assertTrue(restarted.subtasksCollapsedByDefault.value)
        assertTrue(restarted.isSubtasksCollapsed(ViewMode.DAILY, 1L))
        assertFalse(restarted.isSubtasksCollapsed(ViewMode.MONTHLY, 2L))
    }

    @Test
    fun hideCompletedInList_defaultsOffAndPersists() {
        val viewModel = newViewModel()
        assertFalse(viewModel.hideCompletedInList.value)

        viewModel.setHideCompletedInList(true)
        assertTrue(viewModel.hideCompletedInList.value)
        assertTrue(newViewModel().hideCompletedInList.value)
    }
}

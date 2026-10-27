package rocks.urzahil.taskflow

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.TaskViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StarterCategoriesTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private val viewModelStore = ViewModelStore()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    private val starterNames = listOf("General", "Work", "Personal", "Health", "Fitness", "Home", "Study")

    @Test
    fun emptyDatabaseGetsTheStarterSetWithGeneralAsDefault() = runBlocking {
        assertTrue(repository.seedInitialCategoriesIfEmpty())

        val categories = repository.allCategories.first()
        assertEquals(starterNames.sorted(), categories.map { it.name }.sorted())
        assertEquals(listOf("General"), categories.filter { it.isDefault }.map { it.name })
    }

    @Test
    fun seedingTwiceAddsNothingMore() = runBlocking {
        repository.seedInitialCategoriesIfEmpty()

        assertFalse(repository.seedInitialCategoriesIfEmpty())
        assertEquals(7, repository.allCategories.first().size)
    }

    @Test
    fun usersOwnCategoriesAreLeftAlone() = runBlocking {
        repository.insertCategory(CategoryEntity("Errands", 0xFFEF4444, "shopping", isDefault = true))

        assertFalse(repository.seedInitialCategoriesIfEmpty())
        assertEquals(listOf("Errands"), repository.allCategories.first().map { it.name })
    }

    @Test
    fun installsThatAlreadyHaveTasksButNoCategoriesGetTheStarterSet() = runBlocking {
        // Release builds used to start without categories, so tasks were saved under the
        // hard-coded placeholder names. Seeding makes those names real categories again.
        repository.insertTask(TaskEntity(title = "Report", category = "Work", startDate = "2026-10-27"))

        assertTrue(repository.seedInitialCategoriesIfEmpty())

        val names = repository.allCategories.first().map { it.name }
        assertTrue("Work" in names)
    }

    @Test
    fun appStartupSeedsTheStarterSet() = runBlocking {
        context.getSharedPreferences("taskflow_user_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("rollover_cleanup_enabled", false).commit()
        val viewModel = ViewModelProvider(
            viewModelStore,
            TaskViewModel.Factory(repository, GoogleDriveBackupManager(context, repository), context)
        ).get(TaskViewModel::class.java)

        val categories = withTimeout(10_000) { viewModel.categories.first { it.size == 7 } }

        assertEquals(starterNames.sorted(), categories.map { it.name }.sorted())
    }
}

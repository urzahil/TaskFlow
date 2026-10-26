package rocks.urzahil.taskflow

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.MainScreen
import rocks.urzahil.taskflow.ui.TaskViewModel
import rocks.urzahil.taskflow.ui.model.ViewMode
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme
import kotlinx.coroutines.runBlocking
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the real MainScreen and moves through every view, so each one's lists get collected. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class MainScreenNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: AppDatabase
    private lateinit var viewModel: TaskViewModel
    private val viewModelStore = ViewModelStore()
    private val taskId = 42L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("taskflow_user_prefs", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("rollover_cleanup_enabled", false)
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = TaskRepository(database.taskDao())
        runBlocking {
            repository.insertTask(
                TaskEntity(id = taskId, title = "Water plants", startDate = AppDate.today().toIsoString())
            )
        }
        viewModel = ViewModelProvider(
            viewModelStore,
            TaskViewModel.Factory(repository, GoogleDriveBackupManager(context, repository), context)
        ).get(TaskViewModel::class.java)
        composeRule.setContent { TaskFlowTheme { MainScreen(viewModel = viewModel) } }
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun everyViewShowsTheTaskAndTogglingKeepsItListed() {
        awaitTag("task_item_$taskId")

        composeRule.onNodeWithTag("tab_monthly_view").performClick()
        awaitTag("monthly_view_container")
        // The day's tasks sit below the calendar, outside the small test viewport.
        composeRule.waitUntil(timeoutMillis = 10_000) { viewModel.monthlySelectedDayTasks.value.isNotEmpty() }
        composeRule.onNodeWithTag("monthly_view_container")
            .performScrollToNode(hasTestTag("monthly_task_item_$taskId"))
        awaitTag("monthly_task_item_$taskId")

        composeRule.onNodeWithTag("tab_list_view").performClick()
        awaitTag("list_task_item_$taskId")
        composeRule.onNodeWithTag("toggle_list_task_$taskId").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Completed").fetchSemanticsNodes().isNotEmpty()
        }
        awaitTag("list_task_item_$taskId")
    }

    @Test
    fun backFromSettingsReturnsToThePreviousView() {
        composeRule.onNodeWithTag("tab_list_view").performClick()
        awaitTag("list_task_item_$taskId")

        composeRule.onNodeWithTag("settings_top_button").performClick()
        awaitTag("settings_screen_container")
        assertEquals(ViewMode.SETTINGS, viewModel.viewMode.value)

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        awaitTag("list_view_container")
        assertEquals(ViewMode.LIST, viewModel.viewMode.value)
    }

    @Test
    fun backWithSearchOpenInSettingsClosesSettingsFirst() {
        composeRule.onNodeWithTag("search_toggle_button").performClick()
        composeRule.onNodeWithTag("settings_top_button").performClick()
        awaitTag("settings_screen_container")

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(ViewMode.DAILY, viewModel.viewMode.value)
    }
}

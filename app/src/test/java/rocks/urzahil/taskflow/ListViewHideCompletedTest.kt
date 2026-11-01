package rocks.urzahil.taskflow

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.list.ListView
import rocks.urzahil.taskflow.ui.model.DayTasksGroupUi
import rocks.urzahil.taskflow.ui.model.SubtaskItemUi
import rocks.urzahil.taskflow.ui.model.TaskItemUi
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class ListViewHideCompletedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val today = AppDate.today()

    private fun task(id: Long, title: String, completed: Boolean, subtasks: List<SubtaskItemUi> = emptyList()) =
        TaskItemUi(
            task = TaskEntity(id = id, title = title, startDate = today.toIsoString()),
            date = today,
            isCompleted = completed,
            subtasks = subtasks
        )

    private val days = listOf(
        DayTasksGroupUi(
            date = today,
            dayName = "Today",
            tasks = listOf(
                task(
                    1, "Pending", completed = false,
                    subtasks = listOf(
                        SubtaskItemUi(10, "Open step", isChecked = false, shared = true),
                        SubtaskItemUi(11, "Done step", isChecked = true, shared = true)
                    )
                ),
                task(2, "Finished", completed = true)
            )
        )
    )

    @Test
    fun hideCompleted_dropsCompletedTasksAndCheckedSubtasks() {
        var hideCompleted by mutableStateOf(false)
        composeRule.setContent { TaskFlowTheme { ListView(days = days, onToggleTask = {}, hideCompleted = hideCompleted) } }

        composeRule.onNodeWithTag("list_task_item_2").assertIsDisplayed()
        composeRule.onNodeWithText("Done step").assertIsDisplayed()

        hideCompleted = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("list_task_item_2").assertDoesNotExist()
        composeRule.onNodeWithText("Done step").assertDoesNotExist()
        composeRule.onNodeWithTag("list_task_item_1").assertIsDisplayed()
        composeRule.onNodeWithText("Open step").assertIsDisplayed()
    }

    @Test
    fun hideCompleted_showsCaughtUpStateWhenEverythingIsDone() {
        val allDone = listOf(DayTasksGroupUi(today, "Today", listOf(task(2, "Finished", completed = true))))
        composeRule.setContent { TaskFlowTheme { ListView(days = allDone, onToggleTask = {}, hideCompleted = true) } }

        composeRule.onNodeWithTag("list_view_empty_state").assertIsDisplayed()
        composeRule.onNodeWithText("All caught up").assertIsDisplayed()
    }

    @Test
    fun chevronCollapsesAndExpandsSubtasks() {
        var overrides by mutableStateOf(emptySet<Long>())
        composeRule.setContent {
            TaskFlowTheme {
                ListView(
                    days = days,
                    onToggleTask = {},
                    subtaskCollapseOverrides = overrides,
                    onToggleSubtasksCollapsed = { id -> overrides = if (id in overrides) overrides - id else overrides + id }
                )
            }
        }

        composeRule.onNodeWithText("Open step").assertIsDisplayed()
        // A task without subtasks has no chevron.
        composeRule.onNodeWithTag("list_subtask_toggle_2").assertDoesNotExist()

        composeRule.onNodeWithTag("list_subtask_toggle_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open step").assertDoesNotExist()

        composeRule.onNodeWithTag("list_subtask_toggle_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open step").assertIsDisplayed()
    }
}

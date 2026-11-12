package rocks.urzahil.taskflow

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.list.ListView
import rocks.urzahil.taskflow.ui.model.DayTasksGroupUi
import rocks.urzahil.taskflow.ui.model.TaskItemUi
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class ListViewCollapsibleDaysTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val today = AppDate.today()

    private val days = listOf(
        DayTasksGroupUi(
            date = today,
            dayName = "Today",
            tasks = listOf(
                TaskItemUi(
                    task = TaskEntity(id = 1, title = "Pending", startDate = today.toIsoString()),
                    date = today,
                    isCompleted = false,
                    subtasks = emptyList()
                )
            )
        )
    )

    @Test
    fun collapsibleDays_headerTapHidesAndShowsTasksWithChevron() {
        var collapsedDays by mutableStateOf(emptySet<AppDate>())
        composeRule.setContent {
            TaskFlowTheme {
                ListView(
                    days = days,
                    onToggleTask = {},
                    collapsibleDays = true,
                    collapsedDays = collapsedDays,
                    onToggleDayCollapsed = { date ->
                        collapsedDays = if (date in collapsedDays) collapsedDays - date else collapsedDays + date
                    }
                )
            }
        }

        composeRule.onNodeWithTag("list_task_item_1").assertIsDisplayed()
        composeRule.onNodeWithTag("list_day_chevron_0", useUnmergedTree = true).assertDoesNotExist()

        composeRule.onNodeWithTag("list_day_header_0").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("list_task_item_1").assertDoesNotExist()
        composeRule.onNodeWithTag("list_day_chevron_0", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag("list_day_header_0").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("list_task_item_1").assertIsDisplayed()
        composeRule.onNodeWithTag("list_day_chevron_0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun collapsibleDaysOff_headerTapDoesNothingEvenIfDayIsInCollapsedSet() {
        var toggled = false
        composeRule.setContent {
            TaskFlowTheme {
                ListView(
                    days = days,
                    onToggleTask = {},
                    collapsibleDays = false,
                    collapsedDays = setOf(today),
                    onToggleDayCollapsed = { toggled = true }
                )
            }
        }

        composeRule.onNodeWithTag("list_day_header_0").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("list_task_item_1").assertIsDisplayed()
        composeRule.onNodeWithTag("list_day_chevron_0", useUnmergedTree = true).assertDoesNotExist()
        assertFalse("Header must not toggle while days are not collapsible", toggled)
    }
}

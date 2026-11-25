package rocks.urzahil.taskflow

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
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
class ListViewContextAndEditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val today = AppDate(2026, 11, 25)
    private val task = TaskItemUi(
        task = TaskEntity(id = 1, title = "Pending", startDate = today.toIsoString()),
        date = today,
        isCompleted = false
    )

    @Test
    fun headerShowsDayLabelAndShortDate() {
        composeRule.setContent {
            TaskFlowTheme {
                ListView(
                    days = listOf(DayTasksGroupUi(today, "Today", listOf(task))),
                    onToggleTask = {}
                )
            }
        }

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithTag("list_day_date_0")
            .assertTextEquals(today.formatShort(includeYear = false))
    }

    @Test
    fun longPressOpensTheTaskForEditing() {
        var edited: TaskItemUi? = null
        var toggled = 0
        composeRule.setContent {
            TaskFlowTheme {
                ListView(
                    days = listOf(DayTasksGroupUi(today, "Today", listOf(task))),
                    onToggleTask = { toggled++ },
                    onEditTask = { edited = it }
                )
            }
        }

        composeRule.onNodeWithTag("list_task_item_1").performTouchInput { longClick() }

        assertEquals(task, edited)
        assertEquals(0, toggled)
    }
}

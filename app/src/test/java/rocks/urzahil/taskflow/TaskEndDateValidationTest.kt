package rocks.urzahil.taskflow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.components.TaskAddEditSheet
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskEndDateValidationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun recurringTask(endDate: String) = TaskEntity(
        id = 1,
        title = "Course",
        isRecurring = true,
        recurrenceDays = 1,
        startDate = "2026-12-01",
        endDate = endDate
    )

    private fun showSheet(task: TaskEntity) {
        composeTestRule.setContent {
            TaskFlowTheme {
                TaskAddEditSheet(
                    initialDate = AppDate(2026, 10, 26),
                    existingTask = task,
                    onDismiss = {},
                    onSave = {}
                )
            }
        }
    }

    @Test
    fun endDateBeforeStartDateBlocksSaving() {
        showSheet(recurringTask(endDate = "2026-11-25"))

        composeTestRule.onNodeWithTag("end_date_error").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_task_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun endDateOnOrAfterStartDateCanBeSaved() {
        showSheet(recurringTask(endDate = "2026-12-01"))

        composeTestRule.onNodeWithTag("end_date_error").assertDoesNotExist()
        composeTestRule.onNodeWithTag("save_task_button").performScrollTo().assertIsEnabled()
    }
}

package rocks.urzahil.taskflow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.components.TaskAddEditSheet
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfirmDeleteTaskTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var deleted = 0

    private fun showSheet() {
        composeTestRule.setContent {
            TaskFlowTheme {
                TaskAddEditSheet(
                    initialDate = AppDate(2026, 10, 26),
                    existingTask = TaskEntity(id = 1, title = "Water plants", startDate = "2026-10-26"),
                    onDismiss = {},
                    onSave = { _, _ -> },
                    onDelete = { deleted++ }
                )
            }
        }
    }

    @Test
    fun deleteNeedsConfirmation() {
        showSheet()

        composeTestRule.onNodeWithTag("delete_task_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("confirm_delete_task_dialog").assertIsDisplayed()
        assertEquals(0, deleted)

        composeTestRule.onNodeWithTag("confirm_delete_task_button").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun cancelKeepsTheTask() {
        showSheet()

        composeTestRule.onNodeWithTag("delete_task_button").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, deleted)
        composeTestRule.onNodeWithTag("confirm_delete_task_dialog").assertDoesNotExist()
    }
}

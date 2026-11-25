package rocks.urzahil.taskflow

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.components.TaskAddEditSheet
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskSheetStatePersistenceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun typedTitleSurvivesRecreation() {
        val tester = StateRestorationTester(composeTestRule)
        tester.setContent {
            TaskFlowTheme {
                TaskAddEditSheet(
                    initialDate = AppDate(2026, 10, 26),
                    existingTask = null,
                    onDismiss = {},
                    onSave = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("task_title_input").performTextInput("Buy milk")
        tester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Buy milk").assertIsDisplayed()
    }

    @Test
    fun backgroundSubtaskEmissionsDoNotWipeEdits() {
        val task = TaskEntity(id = 1, title = "Task", startDate = "2026-10-26")
        val emitted = mutableStateOf(emptyList<SubtaskEntity>())
        composeTestRule.setContent {
            TaskFlowTheme {
                TaskAddEditSheet(
                    initialDate = AppDate(2026, 10, 26),
                    existingTask = task,
                    existingSubtasks = emitted.value,
                    onDismiss = {},
                    onSave = { _, _ -> }
                )
            }
        }

        // The saved subtasks arrive after the sheet opened (the flow starts empty).
        emitted.value = listOf(SubtaskEntity(id = 5, taskId = 1, title = "Pack"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Pack").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Pack").performTextInput(" bags")
        // Any later database change re-emits the list; typing must survive it.
        emitted.value = listOf(SubtaskEntity(id = 5, taskId = 1, title = "Pack"), SubtaskEntity(id = 6, taskId = 1, title = "Other"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("bags", substring = true).performScrollTo().assertIsDisplayed()
    }
}

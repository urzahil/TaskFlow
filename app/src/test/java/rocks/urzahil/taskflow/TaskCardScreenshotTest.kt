package rocks.urzahil.taskflow

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.daily.TaskCardItem
import rocks.urzahil.taskflow.ui.model.TaskItemUi
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class TaskCardScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun task_card_screenshot() {
        val sampleTask = TaskEntity(
            id = 1,
            title = "Water indoor plants",
            description = "Check soil moisture for monstera and orchids",
            category = "Home",
            priority = "Low",
            isRecurring = true,
            recurrenceDays = 3,
            startDate = "2026-09-23"
        )
        val taskItem = TaskItemUi(
            task = sampleTask,
            date = AppDate(2026, 9, 23),
            isCompleted = false
        )

        composeTestRule.setContent {
            TaskFlowTheme {
                TaskCardItem(
                    taskItem = taskItem,
                    onToggle = {},
                    onEdit = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/task_card.png")
    }
}

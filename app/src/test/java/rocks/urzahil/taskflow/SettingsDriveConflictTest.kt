package rocks.urzahil.taskflow

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.ui.DriveSyncState
import rocks.urzahil.taskflow.ui.settings.SettingsScreen
import rocks.urzahil.taskflow.ui.theme.TaskFlowTheme

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class SettingsDriveConflictTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        state: DriveSyncState,
        keepDrive: () -> Unit = {},
        keepDevice: () -> Unit = {}
    ) {
        composeRule.setContent {
            TaskFlowTheme {
                SettingsScreen(
                    categories = emptyList(),
                    onAddCategory = { _, _, _ -> },
                    onEditCategory = { _, _, _, _, _ -> },
                    onDeleteCategory = {},
                    driveSyncState = state,
                    onResolveSyncConflictKeepDrive = keepDrive,
                    onResolveSyncConflictKeepThisDevice = keepDevice
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("settings_screen_container")
            .performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun conflictShowsPausedBadgeAndResolutionButtons() {
        var keptDrive = 0
        var keptDevice = 0
        show(
            DriveSyncState(isSignedIn = true, userEmail = "me@example.com", syncConflict = true),
            keepDrive = { keptDrive++ },
            keepDevice = { keptDevice++ }
        )

        scrollTo("drive_conflict_banner")
        composeRule.onNodeWithTag("drive_conflict_banner").assertIsDisplayed()
        composeRule.onNodeWithText("PAUSED").assertIsDisplayed()

        composeRule.onNodeWithTag("conflict_use_drive_button").performClick()
        composeRule.onNodeWithTag("conflict_keep_device_button").performClick()
        assertEquals(1, keptDrive)
        assertEquals(1, keptDevice)
    }

    @Test
    fun noConflictShowsActiveBadgeAndNoBanner() {
        show(DriveSyncState(isSignedIn = true, userEmail = "me@example.com"))

        scrollTo("backup_now_button")
        composeRule.onNodeWithText("ACTIVE").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("drive_conflict_banner").fetchSemanticsNodes().size
        )
    }
}

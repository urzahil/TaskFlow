package rocks.urzahil.taskflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.DriveBackupWorker
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.repository.TaskRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveBackupWorkerTest {

    private lateinit var context: Context
    private lateinit var manager: GoogleDriveBackupManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskflow_drive_backup", Context.MODE_PRIVATE).edit().clear().commit()
        manager = GoogleDriveBackupManager(
            context,
            TaskRepository(AppDatabase.getInstance(context).taskDao())
        )
    }

    private fun run() = runBlocking { TestListenableWorkerBuilder<DriveBackupWorker>(context).build().doWork() }

    @Test
    fun nothingPendingDoesNothing() {
        assertEquals(ListenableWorker.Result.success(), run())
    }

    @Test
    fun pendingFlagIsClearedWhenAutoBackupWasTurnedOff() {
        manager.setBackupPendingDurable(true)
        manager.setAutoBackupEnabled(false)

        assertEquals(ListenableWorker.Result.success(), run())
        assertFalse(manager.isBackupPendingDurable())
    }

    @Test
    fun pendingBackupIsKeptWhileDriveIsNotConnected() {
        manager.setBackupPendingDurable(true)

        assertEquals(ListenableWorker.Result.success(), run())
        // Still pending: it uploads once the user connects Drive.
        assertTrue(manager.isBackupPendingDurable())
    }
}

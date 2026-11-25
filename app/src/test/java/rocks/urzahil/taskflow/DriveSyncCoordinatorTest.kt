package rocks.urzahil.taskflow

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.DriveAuthorization
import rocks.urzahil.taskflow.data.drive.DriveAuthorizationOutcome
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.drive.GoogleDriveService
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.DriveSyncCoordinator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveSyncCoordinatorTest {

    private class FakeAuthorization : DriveAuthorization {
        override suspend fun authorize(accountEmail: String?): DriveAuthorizationOutcome =
            DriveAuthorizationOutcome.Granted("token", "me@example.com")

        override fun outcomeFromConsentResult(data: Intent?): DriveAuthorizationOutcome =
            DriveAuthorizationOutcome.Granted("token", "me@example.com")

        override suspend fun clearToken(accessToken: String) {}
        override suspend fun revokeAccess(accountEmail: String) {}
    }

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var server: MockWebServer
    private lateinit var manager: GoogleDriveBackupManager
    private lateinit var scope: CoroutineScope
    private var replaced = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskflow_drive_backup", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = TaskRepository(database.taskDao())
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')
        val authorization = FakeAuthorization()
        manager = GoogleDriveBackupManager(
            context, repository, authorization,
            GoogleDriveService(authorization, "$base/files", "$base/upload")
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        database.close()
    }

    private fun coordinator() =
        DriveSyncCoordinator(repository, manager, scope, null) { replaced++ }

    @Test
    fun autoBackupUploadsAndClearsThePendingFlag() = runBlocking {
        repository.insertTask(TaskEntity(id = 1, title = "Task", startDate = "2026-10-26"))
        manager.connect()
        // Lookup finds no file, then the first upload creates it.
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"abc","modifiedTime":"t"}"""))
        val coordinator = coordinator()

        coordinator.triggerAutoBackup(debounceMs = 0)
        // Give the debounce coroutine time to hand the work to the serialized loop.
        repeat(100) { if (server.requestCount < 2) Thread.sleep(50) }
        coordinator.flushAutoBackup()

        assertEquals(2, server.requestCount)
        assertFalse(manager.isBackupPendingDurable())
        assertTrue(coordinator.state.value.isSignedIn)
    }

    @Test
    fun noBackupHappensWhileASyncConflictIsPending() = runBlocking {
        manager.connect()
        manager.setSyncConflictPending(true)
        val coordinator = coordinator()

        coordinator.triggerAutoBackup(debounceMs = 0)
        coordinator.flushAutoBackup()

        assertEquals(0, server.requestCount)
        // The change is remembered so it uploads once the conflict is resolved.
        assertTrue(manager.isBackupPendingDurable())
        assertTrue(coordinator.state.value.syncConflict)
    }

    @Test
    fun disconnectingUpdatesTheState() = runBlocking {
        manager.connect()
        val coordinator = coordinator()
        coordinator.refreshState()
        assertTrue(coordinator.state.value.isSignedIn)

        coordinator.disconnect()
        repeat(100) { if (coordinator.state.value.isSignedIn) Thread.sleep(50) }

        assertFalse(coordinator.state.value.isSignedIn)
        assertEquals("Disconnected from Google Drive", coordinator.state.value.syncMessage)
    }
}

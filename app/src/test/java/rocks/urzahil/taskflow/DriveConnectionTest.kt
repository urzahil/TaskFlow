package rocks.urzahil.taskflow

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.drive.DRIVE_APPDATA_SCOPE
import rocks.urzahil.taskflow.data.drive.DriveAccessLostException
import rocks.urzahil.taskflow.data.drive.DriveAuthorization
import rocks.urzahil.taskflow.data.drive.DriveAuthorizationOutcome
import rocks.urzahil.taskflow.data.drive.DriveConnectStep
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveConnectionTest {

    private class FakeAuthorization : DriveAuthorization {
        var silentOutcome: DriveAuthorizationOutcome =
            DriveAuthorizationOutcome.Granted("token", "me@example.com")
        var consentOutcome: DriveAuthorizationOutcome =
            DriveAuthorizationOutcome.Granted("token", "picked@example.com")
        var failRevoke = false
        val authorizeCalls = mutableListOf<String?>()
        val revoked = mutableListOf<String>()

        override suspend fun authorize(accountEmail: String?): DriveAuthorizationOutcome {
            authorizeCalls += accountEmail
            return silentOutcome
        }

        override fun outcomeFromConsentResult(data: Intent?) = consentOutcome

        override suspend fun clearToken(accessToken: String) {}

        override suspend fun revokeAccess(accountEmail: String) {
            if (failRevoke) throw IllegalStateException("offline")
            revoked += accountEmail
        }
    }

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private val authorization = FakeAuthorization()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskflow_drive_backup", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TaskRepository(database.taskDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun manager() = GoogleDriveBackupManager(context, repository, authorization)

    private fun consentIntent(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    @Test
    fun onlyTheAppDataScopeIsRequested() {
        assertEquals("https://www.googleapis.com/auth/drive.appdata", DRIVE_APPDATA_SCOPE.scopeUri)
    }

    @Test
    fun startsDisconnected() {
        assertFalse(manager().isDriveConnected())
        assertNull(manager().getConnectedAccountEmail())
    }

    @Test
    fun existingGrantConnectsWithoutAConsentScreen() = runBlocking {
        val manager = manager()

        val step = manager.connect()

        assertEquals(DriveConnectStep.Connected("me@example.com"), step)
        assertEquals("me@example.com", manager.getConnectedAccountEmail())
        // Connecting lets Google pick the account rather than forcing one.
        assertEquals(listOf<String?>(null), authorization.authorizeCalls)
    }

    @Test
    fun connectingThroughTheConsentScreenStoresThePickedAccount() = runBlocking {
        val pendingIntent = consentIntent()
        authorization.silentOutcome = DriveAuthorizationOutcome.NeedsUserConsent(pendingIntent)
        val manager = manager()

        val step = manager.connect()
        assertEquals(DriveConnectStep.NeedsUserConsent(pendingIntent), step)
        assertFalse(manager.isDriveConnected())

        assertEquals("picked@example.com", manager.completeConnect(Intent()))
        assertEquals("picked@example.com", manager.getConnectedAccountEmail())
    }

    @Test
    fun connectionSurvivesAppRestart() = runBlocking {
        manager().connect()

        assertEquals("me@example.com", manager().getConnectedAccountEmail())
    }

    @Test
    fun disconnectRevokesAccessAndForgetsTheAccount() = runBlocking {
        val manager = manager()
        manager.connect()
        manager.setSyncConflictPending(true)

        manager.disconnect()

        assertEquals(listOf("me@example.com"), authorization.revoked)
        assertFalse(manager.isDriveConnected())
        assertFalse(manager.isSyncConflictPending())
    }

    @Test
    fun disconnectStillForgetsTheAccountWhenRevokingFails() = runBlocking {
        val manager = manager()
        manager.connect()
        authorization.failRevoke = true

        manager.disconnect()

        assertFalse(manager.isDriveConnected())
    }

    @Test
    fun backupDisconnectsWhenDriveAccessWasRemoved() = runBlocking {
        val manager = manager()
        manager.connect()
        // The user removed TaskFlow from their Google account's connected apps.
        authorization.silentOutcome = DriveAuthorizationOutcome.NeedsUserConsent(consentIntent())

        val result = manager.backupToDrive(emptyList(), emptyList())

        assertTrue(result.exceptionOrNull() is DriveAccessLostException)
        assertFalse(manager.isDriveConnected())
        // Operations for a connected account always name that account.
        assertEquals(listOf(null, "me@example.com"), authorization.authorizeCalls)
    }

    private fun legacySignInPrefs() =
        context.getSharedPreferences("com.google.android.gms.signin", Context.MODE_PRIVATE)

    @Test
    fun legacySignInIsCarriedOverWithoutAskingGoogle() = runBlocking {
        legacySignInPrefs().edit()
            .putString("defaultGoogleSignInAccount", "123")
            .putString("googleSignInAccount:123", """{"id":"123","email":"old@example.com"}""")
            .commit()
        val manager = manager()

        manager.migrateLegacySignIn()

        assertEquals("old@example.com", manager.getConnectedAccountEmail())
        assertTrue(authorization.authorizeCalls.isEmpty())
        assertTrue(legacySignInPrefs().all.isEmpty())
    }

    @Test
    fun legacySignInWithoutAnEmailAsksGoogleForTheAccount() = runBlocking {
        legacySignInPrefs().edit().putString("defaultGoogleSignInAccount", "123").commit()
        val manager = manager()

        manager.migrateLegacySignIn()

        assertEquals("me@example.com", manager.getConnectedAccountEmail())
    }

    @Test
    fun usersWhoHadSignedOutStayDisconnected() = runBlocking {
        legacySignInPrefs().edit().clear().commit()
        val manager = manager()

        manager.migrateLegacySignIn()

        assertFalse(manager.isDriveConnected())
        assertTrue(authorization.authorizeCalls.isEmpty())
    }

    @Test
    fun legacySignInIsOnlyMigratedOnce() = runBlocking {
        val manager = manager()
        manager.migrateLegacySignIn()
        legacySignInPrefs().edit().putString("defaultGoogleSignInAccount", "123").commit()

        manager.migrateLegacySignIn()

        assertFalse(manager.isDriveConnected())
    }

    @Test
    fun backupWithoutAConnectionFailsWithoutAskingGoogle() = runBlocking {
        val result = manager().backupToDrive(emptyList(), emptyList())

        assertTrue(result.isFailure)
        assertTrue(authorization.authorizeCalls.isEmpty())
    }
}

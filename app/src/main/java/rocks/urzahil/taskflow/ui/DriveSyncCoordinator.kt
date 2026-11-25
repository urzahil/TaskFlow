package rocks.urzahil.taskflow.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rocks.urzahil.taskflow.data.drive.DriveBackupWorker
import rocks.urzahil.taskflow.data.drive.DriveConnectStep
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.repository.TaskRepository
import java.util.concurrent.CancellationException

data class DriveSyncState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val isSyncing: Boolean = false,
    val lastBackupTimeFormatted: String? = null,
    val lastBackupCount: Int = 0,
    val lastBackupCategoriesCount: Int = 0,
    val autoBackupEnabled: Boolean = true,
    val syncConflict: Boolean = false,
    val syncMessage: String? = null,
    val isError: Boolean = false
)

/**
 * Everything about syncing with Google Drive: connecting and disconnecting, manual and
 * automatic backups (debounced, serialized, with a WorkManager fallback), restoring, and the
 * [driveSyncState] the UI shows. Owned by [TaskViewModel], which supplies the [scope] and is told
 * through [onLocalDataReplaced] when a restore swapped the local data.
 */
class DriveSyncCoordinator(
    private val repository: TaskRepository,
    val backupManager: GoogleDriveBackupManager,
    private val scope: CoroutineScope,
    private val context: Context?,
    private val onLocalDataReplaced: () -> Unit
) {
    companion object {
        const val DEFAULT_AUTO_BACKUP_DEBOUNCE_MS = 800L
        private const val WORKER_BACKUP_DELAY_MS = 60_000L
    }

    // Serializes every operation that reads or replaces data against Drive.
    private val mutex = Mutex()
    private val autoBackupChannel = Channel<Unit>(Channel.CONFLATED)
    private var debounceJob: Job? = null

    @Volatile
    private var isAutoBackupPending = false

    private val _state = MutableStateFlow(
        DriveSyncState(
            isSignedIn = backupManager.isDriveConnected(),
            userEmail = backupManager.getConnectedAccountEmail(),
            lastBackupTimeFormatted = backupManager.getLastBackupTimeFormatted(),
            lastBackupCount = backupManager.getLastBackupCount(),
            lastBackupCategoriesCount = backupManager.getLastBackupCategoriesCount(),
            autoBackupEnabled = backupManager.isAutoBackupEnabled(),
            syncConflict = backupManager.isSyncConflictPending(),
        )
    )
    val state: StateFlow<DriveSyncState> = _state.asStateFlow()

    /** Consent screens for Drive access, for the UI to launch; results go to [onConsentResult]. */
    private val _consentRequests = Channel<PendingIntent>(Channel.CONFLATED)
    val consentRequests: Flow<PendingIntent> = _consentRequests.receiveAsFlow()

    init {
        // Serialized auto-backup worker loop
        scope.launch {
            for (event in autoBackupChannel) {
                executeAutoBackupLoop()
            }
        }
    }

    fun dismissSyncMessage() {
        _state.value = _state.value.copy(syncMessage = null)
    }

    fun refreshState(message: String? = null, isError: Boolean = false) {
        val accountEmail = backupManager.getConnectedAccountEmail()
        _state.value = DriveSyncState(
            isSignedIn = accountEmail != null,
            userEmail = accountEmail,
            isSyncing = false,
            lastBackupTimeFormatted = backupManager.getLastBackupTimeFormatted(),
            lastBackupCount = backupManager.getLastBackupCount(),
            lastBackupCategoriesCount = backupManager.getLastBackupCategoriesCount(),
            autoBackupEnabled = backupManager.isAutoBackupEnabled(),
            syncConflict = backupManager.isSyncConflictPending(),
            syncMessage = message,
            isError = isError
        )
    }

    private fun markSyncing() {
        _state.value = _state.value.copy(isSyncing = true, syncMessage = null)
    }

    /** Retries a backup that was durably marked pending (after a kill, a failure or a relaunch). */
    fun resumePendingBackup() {
        if (backupManager.isBackupPendingDurable() &&
            backupManager.isAutoBackupEnabled() &&
            backupManager.isDriveConnected()
        ) {
            isAutoBackupPending = true
            autoBackupChannel.trySend(Unit)
        }
    }

    // --- Automatic backup ---

    fun triggerAutoBackup(debounceMs: Long = DEFAULT_AUTO_BACKUP_DEBOUNCE_MS) {
        if (!backupManager.isAutoBackupEnabled()) return
        if (!backupManager.isDriveConnected()) return

        // Stay quiet (but remember the change) until the user resolves a Drive/local conflict.
        if (backupManager.isSyncConflictPending()) {
            backupManager.setBackupPendingDurable(true)
            return
        }

        isAutoBackupPending = true
        backupManager.setBackupPendingDurable(true)
        // Safety net if the app is closed before the in-process upload runs: WorkManager uploads
        // once online. When the in-process upload succeeds first, the pending flag is clear and
        // the worker does nothing.
        context?.let {
            // Best effort: a scheduling problem must never break editing.
            runCatching { DriveBackupWorker.schedule(it.applicationContext, WORKER_BACKUP_DELAY_MS) }
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            autoBackupChannel.send(Unit)
        }
    }

    suspend fun flushAutoBackup() {
        debounceJob?.cancel()
        if (isAutoBackupPending) {
            autoBackupChannel.send(Unit)
        }
        mutex.withLock { /* Wait for in-flight backup to complete */ }
    }

    private suspend fun executeAutoBackupLoop() {
        mutex.withLock {
            while (isAutoBackupPending) {
                isAutoBackupPending = false
                if (backupManager.isSyncConflictPending()) break
                if (!backupManager.isAutoBackupEnabled() || !backupManager.isDriveConnected()) {
                    if (!backupManager.isAutoBackupEnabled()) {
                        backupManager.setBackupPendingDurable(false)
                    }
                    break
                }
                try {
                    val snapshot = repository.getBackupSnapshot()
                    val result = backupManager.backupSnapshot(snapshot)
                    if (result.isSuccess) {
                        backupManager.setBackupPendingDurable(false)
                        refreshState()
                    } else {
                        isAutoBackupPending = true
                        backupManager.setBackupPendingDurable(true)
                        val err = result.exceptionOrNull()?.message ?: "Auto-backup failed"
                        refreshState(message = "Auto-backup error: $err", isError = true)
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    refreshState(message = "Auto-backup failed: ${e.message}", isError = true)
                }
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        backupManager.setAutoBackupEnabled(enabled)
        refreshState()
    }

    // --- Manual backup and restore ---

    fun backupToDrive() {
        scope.launch {
            markSyncing()
            try {
                mutex.withLock {
                    val snapshot = repository.getBackupSnapshot()
                    val result = backupManager.backupSnapshot(snapshot)
                    if (result.isSuccess) {
                        refreshState(
                            message = "Backed up ${snapshot.tasks.size} tasks & ${snapshot.categories.size} categories to Google Drive! ☁️",
                            isError = false
                        )
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Backup failed"
                        refreshState(message = "Backup error: $err", isError = true)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                refreshState(message = "Backup failed: ${e.message}", isError = true)
            }
        }
    }

    fun restoreFromDrive() {
        scope.launch {
            markSyncing()
            try {
                val result = mutex.withLock { backupManager.restoreFromDrive() }
                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    onLocalDataReplaced()
                    refreshState(
                        message = "Restored ${res.taskCount} tasks & ${res.categoryCount} categories from Google Drive! 🎉",
                        isError = false
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Restore failed"
                    refreshState(message = "Restore error: $err", isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refreshState(message = "Restore failed: ${e.message}", isError = true)
            }
        }
    }

    /** Resolve a sync conflict by replacing this device's data with the Drive backup. */
    fun resolveSyncConflictKeepDrive() = restoreFromDrive()

    /** Resolve a sync conflict by replacing the Drive backup with this device's data. */
    fun resolveSyncConflictUseThisDevice() = backupToDrive()

    suspend fun getBackupJsonString(): String =
        backupManager.exportBackupJson(repository.getBackupSnapshot())

    fun restoreFromJson(jsonString: String) {
        scope.launch {
            markSyncing()
            try {
                val result = mutex.withLock { backupManager.restoreFromJson(jsonString) }
                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    onLocalDataReplaced()
                    refreshState(
                        message = "Restored ${res.taskCount} tasks & ${res.categoryCount} categories from backup file! 🎉",
                        isError = false
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Invalid backup file"
                    refreshState(message = "Restore error: $err", isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refreshState(message = "Restore failed: ${e.message}", isError = true)
            }
        }
    }

    // --- Connecting ---

    /** Connects Google Drive, asking the UI to show Google's consent screen when needed. */
    fun connect() {
        scope.launch {
            markSyncing()
            try {
                when (val step = backupManager.connect()) {
                    is DriveConnectStep.Connected -> onConnected(step.accountEmail)
                    is DriveConnectStep.NeedsUserConsent -> {
                        refreshState()
                        _consentRequests.send(step.pendingIntent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onConnectFailed(e)
            }
        }
    }

    /** Handles the result of the consent screen requested through [consentRequests]. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            refreshState("Google Drive was not connected", isError = true)
            return
        }
        scope.launch {
            try {
                onConnected(backupManager.completeConnect(data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onConnectFailed(e)
            }
        }
    }

    private fun onConnectFailed(e: Exception) {
        refreshState("Could not connect Google Drive: ${e.message}", isError = true)
    }

    private fun onConnected(accountEmail: String) {
        refreshState(message = "Connected to Google Drive as $accountEmail")
        // Check if fresh tasks can be auto-restored
        scope.launch {
            val currentTasks = repository.allTasks.first()
            if (currentTasks.isEmpty()) {
                val result = mutex.withLock { backupManager.restoreFromDrive() }
                val res = result.getOrNull()
                if (res != null && (res.taskCount > 0 || res.categoryCount > 0)) {
                    onLocalDataReplaced()
                    refreshState(
                        message = "Connected as $accountEmail and restored ${res.taskCount} tasks & ${res.categoryCount} categories from Drive! 🎉",
                        isError = false
                    )
                }
            } else if (backupManager.detectSyncConflict()) {
                // Both this device and Drive hold data: ask before anything is overwritten.
                refreshState(
                    message = "Connected as $accountEmail. Drive already has a backup, so automatic backup is paused until you choose which data to keep.",
                    isError = false
                )
            }
        }
    }

    fun disconnect() {
        scope.launch {
            backupManager.disconnect()
            refreshState(message = "Disconnected from Google Drive")
        }
    }
}

package com.example.ui

import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskBackupCoordinator(
    private val repository: TaskRepository,
    private val driveBackupManager: GoogleDriveBackupManager,
    private val scope: CoroutineScope,
    private val onSyncStateChanged: (message: String?, isError: Boolean) -> Unit
) {
    companion object {
        const val DEFAULT_AUTO_BACKUP_DEBOUNCE_MS = 800L
    }

    private val driveBackupMutex = Mutex()
    private val autoBackupChannel = Channel<Unit>(Channel.CONFLATED)
    private var debounceJob: Job? = null

    @Volatile
    private var isAutoBackupPending = false

    fun handlePendingBackupOnForeground() {
        if (driveBackupManager.isBackupPendingDurable() &&
            driveBackupManager.isAutoBackupEnabled() &&
            driveBackupManager.getSignedInAccount() != null
        ) {
            isAutoBackupPending = true
            autoBackupChannel.trySend(Unit)
        }
    }

    fun triggerAutoBackup(debounceMs: Long = DEFAULT_AUTO_BACKUP_DEBOUNCE_MS) {
        if (!driveBackupManager.isAutoBackupEnabled()) return
        if (driveBackupManager.getSignedInAccount() == null) return

        isAutoBackupPending = true
        driveBackupManager.setBackupPendingDurable(true)
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (debounceMs > 0L) {
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
        driveBackupMutex.withLock { /* Wait for in-flight backup to complete */ }
    }

    fun start() {
        scope.launch {
            for (event in autoBackupChannel) {
                executeAutoBackupLoop()
            }
        }
    }

    suspend fun executeAutoBackupLoop() {
        driveBackupMutex.withLock {
            while (isAutoBackupPending) {
                isAutoBackupPending = false
                if (!driveBackupManager.isAutoBackupEnabled() || driveBackupManager.getSignedInAccount() == null) {
                    if (!driveBackupManager.isAutoBackupEnabled()) {
                        driveBackupManager.setBackupPendingDurable(false)
                    }
                    break
                }
                try {
                    val tasks = repository.allTasks.first()
                    val completions = repository.allCompletions.first()
                    val categories = repository.allCategories.first()
                    val exclusions = repository.allOccurrenceExclusions.first()
                    val result =
                        driveBackupManager.backupToDrive(tasks, completions, categories, exclusions)
                    if (result.isSuccess) {
                        driveBackupManager.setBackupPendingDurable(false)
                        onSyncStateChanged(null, false)
                    } else {
                        isAutoBackupPending = true
                        driveBackupManager.setBackupPendingDurable(true)
                        val err = result.exceptionOrNull()?.message ?: "Auto-backup failed"
                        onSyncStateChanged("Auto-backup error: $err", true)
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onSyncStateChanged("Auto-backup failed: ${e.message}", true)
                }
            }
        }
    }
}

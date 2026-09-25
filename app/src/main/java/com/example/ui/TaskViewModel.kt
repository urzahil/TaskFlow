package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import com.example.ui.model.DaySummaryUi
import com.example.ui.model.TaskFilter
import com.example.ui.model.TaskItemUi
import com.example.ui.model.ViewMode
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DriveSyncState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val isSyncing: Boolean = false,
    val lastBackupTimeFormatted: String? = null,
    val lastBackupCount: Int = 0,
    val lastBackupCategoriesCount: Int = 0,
    val autoBackupEnabled: Boolean = true,
    val syncMessage: String? = null,
    val isError: Boolean = false
)

data class DailyStats(
    val total: Int = 0,
    val completed: Int = 0
) {
    val progress: Float
        get() = if (total > 0) completed.toFloat() / total.toFloat() else 0f
}

class TaskViewModel(
    private val repository: TaskRepository,
    val driveBackupManager: GoogleDriveBackupManager,
    private val context: Context? = null
) : ViewModel() {

    companion object {
        const val DEFAULT_AUTO_BACKUP_DEBOUNCE_MS = 800L
        private const val PREFS_NAME = "taskflow_user_prefs"
        private const val KEY_SHOW_DAILY_PROGRESS = "show_daily_progress"
    }

    private val userPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _showDailyProgress = MutableStateFlow(
        userPrefs?.getBoolean(KEY_SHOW_DAILY_PROGRESS, true) ?: true
    )
    val showDailyProgress: StateFlow<Boolean> = _showDailyProgress.asStateFlow()

    fun setShowDailyProgress(show: Boolean) {
        _showDailyProgress.value = show
        userPrefs?.edit()?.putBoolean(KEY_SHOW_DAILY_PROGRESS, show)?.apply()
    }

    private val driveBackupMutex = Mutex()
    private val autoBackupChannel = Channel<Unit>(Channel.CONFLATED)
    private var debounceJob: Job? = null
    @Volatile
    private var isAutoBackupPending = false

    private val today = AppDate.today()

    private val _selectedDate = MutableStateFlow(today)
    val selectedDate: StateFlow<AppDate> = _selectedDate.asStateFlow()

    private val _selectedYearMonth = MutableStateFlow(Pair(today.year, today.month))
    val selectedYearMonth: StateFlow<Pair<Int, Int>> = _selectedYearMonth.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.DAILY)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _taskFilter = MutableStateFlow(TaskFilter.ALL)
    val taskFilter: StateFlow<TaskFilter> = _taskFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Dialog state for adding/editing tasks
    private val _editingTask = MutableStateFlow<TaskEntity?>(null)
    val editingTask: StateFlow<TaskEntity?> = _editingTask.asStateFlow()

    private val _isAddEditSheetOpen = MutableStateFlow(false)
    val isAddEditSheetOpen: StateFlow<Boolean> = _isAddEditSheetOpen.asStateFlow()

    private val _maintenanceMessage = MutableStateFlow<String?>(null)
    val maintenanceMessage: StateFlow<String?> = _maintenanceMessage.asStateFlow()

    fun dismissMaintenanceMessage() {
        _maintenanceMessage.value = null
    }

    private val _showGoogleError10Dialog = MutableStateFlow(false)
    val showGoogleError10Dialog: StateFlow<Boolean> = _showGoogleError10Dialog.asStateFlow()

    fun setShowGoogleError10Dialog(show: Boolean) {
        _showGoogleError10Dialog.value = show
    }

    private val _driveSyncState = MutableStateFlow(
        DriveSyncState(
            isSignedIn = driveBackupManager.getSignedInAccount() != null,
            userEmail = driveBackupManager.getSignedInAccount()?.email,
            lastBackupTimeFormatted = driveBackupManager.getLastBackupTimeFormatted(),
            lastBackupCount = driveBackupManager.getLastBackupCount(),
            lastBackupCategoriesCount = driveBackupManager.getLastBackupCategoriesCount(),
            autoBackupEnabled = driveBackupManager.isAutoBackupEnabled()
        )
    )
    val driveSyncState: StateFlow<DriveSyncState> = _driveSyncState.asStateFlow()

    fun dismissSyncMessage() {
        _driveSyncState.value = _driveSyncState.value.copy(syncMessage = null)
    }

    val categories: StateFlow<List<CategoryEntity>> = repository.allCategories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            val currentCategories = repository.allCategories.first()
            if (currentCategories.isEmpty()) {
                repository.seedInitialCategoriesIfEmpty()
            }

            // On app installation: auto-restore from Google Drive if a backup exists
            val restoredResult = driveBackupManager.checkAndAutoRestoreOnInstall()
            val restored = restoredResult.getOrNull()
            if (restored != null && (restored.taskCount > 0 || restored.categoryCount > 0)) {
                _maintenanceMessage.value = "Restored ${restored.taskCount} tasks & ${restored.categoryCount} categories from Google Drive backup 🎉"
            } else {
                val currentTasks = repository.allTasks.first()
                if (currentTasks.isEmpty()) {
                    repository.seedInitialTasksIfEmpty()
                }
            }
            refreshDriveState()

            // Run startup task to clean old completed tasks and move uncompleted tasks to today
            runCleanupAndRollover()

            // If a previous auto-backup was pending durable across restart, trigger it now
            if (driveBackupManager.isBackupPendingDurable()) {
                triggerAutoBackup(debounceMs = 1500L)
            }
        }

        // Serialized auto-backup worker loop
        viewModelScope.launch {
            for (event in autoBackupChannel) {
                executeAutoBackupLoop()
            }
        }
    }

    fun runCleanupAndRollover(onComplete: ((Int, Int, Int) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.cleanupAndRolloverTasks(today)
            if (result.cleanedCount > 0 || result.movedCount > 0 || result.cleanedRecurringOccurrences > 0) {
                val details = mutableListOf<String>()
                if (result.cleanedCount > 0) details.add("cleaned ${result.cleanedCount} completed task(s)")
                if (result.movedCount > 0) details.add("moved ${result.movedCount} uncompleted task(s) to today")
                if (result.cleanedRecurringOccurrences > 0) details.add("removed ${result.cleanedRecurringOccurrences} old recurring occurrence(s)")
                _maintenanceMessage.value = details.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
            }
            onComplete?.invoke(result.cleanedCount, result.movedCount, result.cleanedRecurringOccurrences)
        }
    }

    private data class ParsedTaskSchedule(
        val id: Long,
        val colorHex: Long,
        val isRecurring: Boolean,
        val recurrenceDays: Int,
        val recurrenceDaysOfWeek: Set<Int>,
        val startDate: AppDate,
        val endDate: AppDate?
    ) {
        fun isScheduledOn(date: AppDate): Boolean {
            if (!isRecurring) {
                return startDate == date
            }
            if (date < startDate) return false
            if (endDate != null && date > endDate) return false
            if (recurrenceDaysOfWeek.isNotEmpty()) {
                return recurrenceDaysOfWeek.contains(date.dayOfWeek())
            }
            return date.daysBetween(startDate) % recurrenceDays == 0L
        }
    }

    /**
     * Pre-parsed tasks for fast scheduling calculations without repeated string parsing or date calculations.
     */
    private val parsedTasksState: StateFlow<List<Pair<TaskEntity, ParsedTaskSchedule>>> = repository.allTasks
        .map { tasks ->
            tasks.mapNotNull { task ->
                val start = try {
                    AppDate.parseIso(task.startDate)
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null

                val end = if (!task.endDate.isNullOrBlank()) {
                    try { AppDate.parseIso(task.endDate) } catch (_: Exception) { null }
                } else null

                val interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1
                val daysOfWeekSet = task.parsedDaysOfWeek()

                val schedule = ParsedTaskSchedule(
                    id = task.id,
                    colorHex = task.colorHex,
                    isRecurring = task.isRecurring,
                    recurrenceDays = interval,
                    recurrenceDaysOfWeek = daysOfWeekSet,
                    startDate = start,
                    endDate = end
                )
                Pair(task, schedule)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private data class DailyCalculationResult(
        val items: List<TaskItemUi>,
        val stats: DailyStats
    )

    /**
     * Shared computation of daily scheduled tasks and stats for the selected date.
     */
    private val dailyCalculation: StateFlow<DailyCalculationResult> = combine(
        parsedTasksState,
        repository.allCompletions,
        _selectedDate
    ) { parsedTasks, completions, date ->
        val dateIso = date.toIsoString()
        val completedTaskIds = completions
            .filter { it.date == dateIso }
            .map { it.taskId }
            .toSet()

        val scheduledItems = mutableListOf<TaskItemUi>()
        var completedCount = 0

        for ((task, schedule) in parsedTasks) {
            if (schedule.isScheduledOn(date)) {
                val isCompleted = completedTaskIds.contains(task.id)
                if (isCompleted) completedCount++
                scheduledItems.add(
                    TaskItemUi(
                        task = task,
                        date = date,
                        isCompleted = isCompleted
                    )
                )
            }
        }

        DailyCalculationResult(
            items = scheduledItems,
            stats = DailyStats(total = scheduledItems.size, completed = completedCount)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DailyCalculationResult(emptyList(), DailyStats())
    )

    /**
     * Tasks for the currently selected date with filter & search applied.
     */
    val dailyTasks: StateFlow<List<TaskItemUi>> = combine(
        dailyCalculation,
        _taskFilter,
        _searchQuery
    ) { calculation, filter, query ->
        val items = calculation.items

        // Apply search query
        val queryFiltered = if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.task.title.contains(query, ignoreCase = true) ||
                    it.task.description.contains(query, ignoreCase = true) ||
                    it.task.category.contains(query, ignoreCase = true)
            }
        }

        // Apply completion filter
        when (filter) {
            TaskFilter.ALL -> queryFiltered
            TaskFilter.PENDING -> queryFiltered.filter { !it.isCompleted }
            TaskFilter.COMPLETED -> queryFiltered.filter { it.isCompleted }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Statistics for the currently selected date (total, completed).
     */
    val dailyStats: StateFlow<DailyStats> = dailyCalculation
        .map { it.stats }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DailyStats()
        )

    /**
     * Map of Date ISO string to DaySummaryUi for all days in the currently selected month.
     * Highlighting days with something scheduled while preserving task color ordering!
     */
    val monthDaysSummary: StateFlow<Map<String, DaySummaryUi>> = combine(
        parsedTasksState,
        repository.allCompletions,
        _selectedYearMonth,
        _viewMode
    ) { parsedTasks, completions, (year, month), currentViewMode ->
        // Avoid month-summary work when the monthly view is not active
        if (currentViewMode != ViewMode.MONTHLY) {
            return@combine emptyMap<String, DaySummaryUi>()
        }

        val daysInMonth = AppDate.daysInMonth(year, month)
        val summaryMap = mutableMapOf<String, DaySummaryUi>()

        // Pre-group completions by date
        val completionsByDate = completions.groupBy { it.date }

        // Invert iteration: evaluate each day with pre-parsed schedules
        for (day in 1..daysInMonth) {
            val date = AppDate(year, month, day)
            val dateIso = date.toIsoString()

            val completedIds = completionsByDate[dateIso]?.map { it.taskId }?.toSet() ?: emptySet()

            var totalTasks = 0
            var completedCount = 0
            val colors = mutableListOf<Long>()

            for ((_, schedule) in parsedTasks) {
                if (schedule.isScheduledOn(date)) {
                    totalTasks++
                    if (completedIds.contains(schedule.id)) {
                        completedCount++
                    }
                    if (colors.size < 3) {
                        colors.add(schedule.colorHex)
                    }
                }
            }

            if (totalTasks > 0) {
                summaryMap[dateIso] = DaySummaryUi(
                    date = date,
                    totalTasks = totalTasks,
                    completedTasks = completedCount,
                    taskColors = colors
                )
            }
        }

        summaryMap
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap()
    )

    fun selectDate(date: AppDate) {
        _selectedDate.value = date
        _selectedYearMonth.value = Pair(date.year, date.month)
    }

    fun selectMonth(year: Int, month: Int) {
        _selectedYearMonth.value = Pair(year, month)
    }

    fun nextDay() {
        val next = _selectedDate.value.plusDays(1)
        selectDate(next)
    }

    fun prevDay() {
        val prev = _selectedDate.value.minusDays(1)
        selectDate(prev)
    }

    fun jumpToToday() {
        selectDate(AppDate.today())
    }

    fun nextMonth() {
        val (year, month) = _selectedYearMonth.value
        if (month == 12) {
            _selectedYearMonth.value = Pair(year + 1, 1)
        } else {
            _selectedYearMonth.value = Pair(year, month + 1)
        }
    }

    fun prevMonth() {
        val (year, month) = _selectedYearMonth.value
        if (month == 1) {
            _selectedYearMonth.value = Pair(year - 1, 12)
        } else {
            _selectedYearMonth.value = Pair(year, month - 1)
        }
    }

    fun jumpToCurrentMonth() {
        val now = AppDate.today()
        _selectedYearMonth.value = Pair(now.year, now.month)
        _selectedDate.value = now
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
    }

    fun setFilter(filter: TaskFilter) {
        _taskFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openAddTaskDialog() {
        _editingTask.value = null
        _isAddEditSheetOpen.value = true
    }

    fun openEditTaskDialog(task: TaskEntity) {
        _editingTask.value = task
        _isAddEditSheetOpen.value = true
    }

    fun closeAddEditDialog() {
        _isAddEditSheetOpen.value = false
        _editingTask.value = null
    }

    fun toggleTaskCompletion(taskItem: TaskItemUi) {
        viewModelScope.launch {
            repository.toggleCompletion(
                taskId = taskItem.task.id,
                dateIso = taskItem.date.toIsoString(),
                currentlyCompleted = taskItem.isCompleted
            )
            triggerAutoBackup()
        }
    }

    fun saveTask(task: TaskEntity) {
        viewModelScope.launch {
            if (task.id == 0L) {
                repository.insertTask(task)
            } else {
                repository.updateTask(task)
            }
            closeAddEditDialog()
            triggerAutoBackup()
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
            if (_editingTask.value?.id == task.id) {
                closeAddEditDialog()
            }
            triggerAutoBackup()
        }
    }

    fun addCategory(name: String, colorHex: Long, iconName: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.insertCategory(
                CategoryEntity(
                    name = trimmed,
                    colorHex = colorHex,
                    iconName = iconName,
                    isDefault = false
                )
            )
            triggerAutoBackup()
        }
    }

    fun deleteCategory(categoryName: String) {
        viewModelScope.launch {
            repository.deleteCategory(categoryName)
            triggerAutoBackup()
        }
    }

    fun updateCategory(
        oldName: String,
        newName: String,
        colorHex: Long,
        iconName: String,
        isDefault: Boolean
    ) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.updateCategory(oldName, trimmed, colorHex, iconName, isDefault)
            triggerAutoBackup()
        }
    }

    private var autoBackupFailureRetryCount = 0
    private val maxAutoBackupRetries = 3

    fun triggerAutoBackup(debounceMs: Long = DEFAULT_AUTO_BACKUP_DEBOUNCE_MS) {
        if (!driveBackupManager.isAutoBackupEnabled()) return
        if (driveBackupManager.getSignedInAccount() == null) return

        isAutoBackupPending = true
        driveBackupManager.setBackupPendingDurable(true)
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
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
        driveBackupMutex.withLock { /* Wait for in-flight backup to complete */ }
    }

    private suspend fun executeAutoBackupLoop() {
        driveBackupMutex.withLock {
            while (isAutoBackupPending) {
                if (!driveBackupManager.isAutoBackupEnabled() || driveBackupManager.getSignedInAccount() == null) {
                    isAutoBackupPending = false
                    break
                }

                // Snap current snapshot of data
                val tasks = repository.allTasks.first()
                val completions = repository.allCompletions.first()
                val categories = repository.allCategories.first()

                // Mark that we are uploading this state
                val uploaded = try {
                    val result = driveBackupManager.backupToDrive(tasks, completions, categories)
                    if (result.isSuccess) {
                        autoBackupFailureRetryCount = 0
                        // Clear pending flag only on successful upload if no new edits arrived during upload
                        driveBackupManager.setBackupPendingDurable(false)
                        isAutoBackupPending = false
                        refreshDriveState()
                        true
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Auto-backup failed"
                        refreshDriveState(message = "Auto-backup error: $err", isError = true)
                        false
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    refreshDriveState(message = "Auto-backup failed: ${e.message}", isError = true)
                    false
                }

                if (!uploaded) {
                    autoBackupFailureRetryCount++
                    if (autoBackupFailureRetryCount <= maxAutoBackupRetries) {
                        // Bounded exponential backoff: 2s, 4s, 8s
                        val backoffMs = (1000L * (1 shl autoBackupFailureRetryCount)).coerceAtMost(10000L)
                        delay(backoffMs)
                        // Keep isAutoBackupPending = true to retry next iteration
                    } else {
                        // Exhausted retries for this attempt, retain durable pending flag so future edit or app launch retries
                        isAutoBackupPending = false
                        break
                    }
                }
            }
        }
    }

    fun refreshDriveState(message: String? = null, isError: Boolean = false) {
        val account = driveBackupManager.getSignedInAccount()
        _driveSyncState.value = DriveSyncState(
            isSignedIn = account != null,
            userEmail = account?.email,
            isSyncing = false,
            lastBackupTimeFormatted = driveBackupManager.getLastBackupTimeFormatted(),
            lastBackupCount = driveBackupManager.getLastBackupCount(),
            lastBackupCategoriesCount = driveBackupManager.getLastBackupCategoriesCount(),
            autoBackupEnabled = driveBackupManager.isAutoBackupEnabled(),
            syncMessage = message,
            isError = isError
        )
    }

    fun backupToDrive() {
        viewModelScope.launch {
            _driveSyncState.value = _driveSyncState.value.copy(isSyncing = true, syncMessage = null)
            try {
                driveBackupMutex.withLock {
                    val tasks = repository.allTasks.first()
                    val completions = repository.allCompletions.first()
                    val categories = repository.allCategories.first()
                    val result = driveBackupManager.backupToDrive(tasks, completions, categories)
                    if (result.isSuccess) {
                        refreshDriveState(
                            message = "Backed up ${tasks.size} tasks & ${categories.size} categories to Google Drive! ☁️",
                            isError = false
                        )
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Backup failed"
                        refreshDriveState(message = "Backup error: $err", isError = true)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                refreshDriveState(message = "Backup failed: ${e.message}", isError = true)
            }
        }
    }

    fun restoreFromDrive() {
        viewModelScope.launch {
            _driveSyncState.value = _driveSyncState.value.copy(isSyncing = true, syncMessage = null)
            try {
                val result = driveBackupManager.restoreFromDrive()
                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    refreshDriveState(
                        message = "Restored ${res.taskCount} tasks & ${res.categoryCount} categories from Google Drive! 🎉",
                        isError = false
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Restore failed"
                    refreshDriveState(message = "Restore error: $err", isError = true)
                }
            } catch (e: Exception) {
                refreshDriveState(message = "Restore failed: ${e.message}", isError = true)
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        driveBackupManager.setAutoBackupEnabled(enabled)
        refreshDriveState()
    }

    fun onGoogleSignInSuccess(account: GoogleSignInAccount) {
        refreshDriveState(message = "Connected to Google Drive as ${account.email}")
        // Check if fresh tasks can be auto-restored
        viewModelScope.launch {
            val currentTasks = repository.allTasks.first()
            if (currentTasks.isEmpty()) {
                val result = driveBackupManager.restoreFromDrive()
                val res = result.getOrNull()
                if (res != null && (res.taskCount > 0 || res.categoryCount > 0)) {
                    refreshDriveState(
                        message = "Connected as ${account.email} and restored ${res.taskCount} tasks & ${res.categoryCount} categories from Drive! 🎉",
                        isError = false
                    )
                }
            }
        }
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            try {
                driveBackupManager.getGoogleSignInClient().signOut()
            } catch (_: Exception) {}
            refreshDriveState(message = "Disconnected from Google Drive")
        }
    }

    suspend fun getBackupJsonString(): String {
        val tasks = repository.allTasks.first()
        val completions = repository.allCompletions.first()
        val categories = repository.allCategories.first()
        return driveBackupManager.exportBackupJson(tasks, completions, categories)
    }

    fun restoreFromJson(jsonString: String) {
        viewModelScope.launch {
            _driveSyncState.value = _driveSyncState.value.copy(isSyncing = true, syncMessage = null)
            try {
                val result = driveBackupManager.restoreFromJson(jsonString)
                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    refreshDriveState(
                        message = "Restored ${res.taskCount} tasks & ${res.categoryCount} categories from backup file! 🎉",
                        isError = false
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Invalid backup file"
                    refreshDriveState(message = "Restore error: $err", isError = true)
                }
            } catch (e: Exception) {
                refreshDriveState(message = "Restore failed: ${e.message}", isError = true)
            }
        }
    }

    class Factory(
        private val repository: TaskRepository,
        private val driveBackupManager: GoogleDriveBackupManager,
        private val context: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
                return TaskViewModel(repository, driveBackupManager, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

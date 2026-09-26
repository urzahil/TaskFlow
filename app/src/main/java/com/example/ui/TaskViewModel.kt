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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
        private const val KEY_LAST_ROLLOVER_DATE = "last_rollover_date"
        private const val KEY_LAST_ROLLOVER_TIMESTAMP = "last_rollover_timestamp"
    }

    private val userPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _showDailyProgress = MutableStateFlow(
        userPrefs?.getBoolean(KEY_SHOW_DAILY_PROGRESS, true) ?: true
    )
    val showDailyProgress: StateFlow<Boolean> = _showDailyProgress.asStateFlow()

    private val _currentToday = MutableStateFlow(AppDate.today())
    val currentToday: StateFlow<AppDate> = _currentToday.asStateFlow()

    private val _lastRolloverInfo = MutableStateFlow<Pair<String, String>?>(
        userPrefs?.getString(KEY_LAST_ROLLOVER_DATE, null)?.let { date ->
            Pair(date, userPrefs.getString(KEY_LAST_ROLLOVER_TIMESTAMP, "") ?: "")
        }
    )
    val lastRolloverInfo: StateFlow<Pair<String, String>?> = _lastRolloverInfo.asStateFlow()

    fun setShowDailyProgress(show: Boolean) {
        _showDailyProgress.value = show
        userPrefs?.edit()?.putBoolean(KEY_SHOW_DAILY_PROGRESS, show)?.apply()
    }

    private val driveBackupMutex = Mutex()
    private val autoBackupChannel = Channel<Unit>(Channel.CONFLATED)
    private var debounceJob: Job? = null
    @Volatile
    private var isAutoBackupPending = false

    private val _selectedDate = MutableStateFlow(_currentToday.value)
    val selectedDate: StateFlow<AppDate> = _selectedDate.asStateFlow()

    private val _selectedYearMonth = MutableStateFlow(Pair(_currentToday.value.year, _currentToday.value.month))
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

            // Run startup check to clean old completed tasks and roll forward tasks to today
            onAppForegrounded()
        }

        // Lightweight watcher for midnight boundary transition when app is open
        scheduleMidnightWatcher()

        // Serialized auto-backup worker loop
        viewModelScope.launch {
            for (event in autoBackupChannel) {
                executeAutoBackupLoop()
            }
        }
    }

    private var midnightJob: Job? = null
    private fun scheduleMidnightWatcher() {
        midnightJob?.cancel()
        midnightJob = viewModelScope.launch {
            while (true) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 1)
                cal.set(Calendar.MILLISECOND, 0)
                val delayMs = (cal.timeInMillis - System.currentTimeMillis()).coerceAtLeast(1000L)
                delay(delayMs)
                onAppForegrounded()
            }
        }
    }

    /**
     * Called whenever the app is brought into foreground or when the system reports date change.
     * Checks if it is a new day compared to the last recorded rollover.
     * Uses 0 background battery since it executes only on foreground transition.
     */
    fun onAppForegrounded() {
        val now = AppDate.today()
        val previousToday = _currentToday.value
        _currentToday.value = now

        val lastRolloverDateIso = userPrefs?.getString(KEY_LAST_ROLLOVER_DATE, null)
        val todayIso = now.toIsoString()

        val isNewDay = lastRolloverDateIso != todayIso || previousToday != now

        if (isNewDay) {
            // Auto-advance selected date if the user was on yesterday's 'today'
            if (_selectedDate.value == previousToday) {
                _selectedDate.value = now
                _selectedYearMonth.value = Pair(now.year, now.month)
            }
            runCleanupAndRollover(targetDate = now, isForegroundCheck = true)
        }
    }

    fun onDateChanged() {
        onAppForegrounded()
    }

    fun runCleanupAndRollover(
        targetDate: AppDate = AppDate.today(),
        isForegroundCheck: Boolean = false,
        onComplete: ((Int, Int, Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val result = repository.cleanupAndRolloverTasks(targetDate)
            val nowTimeFormatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            userPrefs?.edit()
                ?.putString(KEY_LAST_ROLLOVER_DATE, targetDate.toIsoString())
                ?.putString(KEY_LAST_ROLLOVER_TIMESTAMP, nowTimeFormatted)
                ?.apply()
            _lastRolloverInfo.value = Pair(targetDate.toIsoString(), nowTimeFormatted)

            if (result.cleanedCount > 0 || result.movedCount > 0 || result.cleanedRecurringOccurrences > 0) {
                val details = mutableListOf<String>()
                if (result.cleanedCount > 0) details.add("cleaned ${result.cleanedCount} completed task(s)")
                if (result.movedCount > 0) details.add("moved ${result.movedCount} uncompleted task(s) to today")
                if (result.cleanedRecurringOccurrences > 0) details.add("removed ${result.cleanedRecurringOccurrences} old recurring occurrence(s)")
                _maintenanceMessage.value = details.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
            } else if (!isForegroundCheck) {
                _maintenanceMessage.value = "Tasks are up to date for today. No rollover needed."
            }
            onComplete?.invoke(result.cleanedCount, result.movedCount, result.cleanedRecurringOccurrences)
        }
    }

    /**
     * Tasks for the currently selected date.
     */
    val dailyTasks: StateFlow<List<TaskItemUi>> = combine(
        repository.allTasks,
        repository.allCompletions,
        _selectedDate,
        _taskFilter,
        _searchQuery
    ) { tasks, completions, date, filter, query ->
        val dateIso = date.toIsoString()
        val completedTaskIds = completions
            .filter { it.date == dateIso }
            .map { it.taskId }
            .toSet()

        val scheduledTasks = tasks.filter { task ->
            repository.isTaskScheduledOnDate(task, date)
        }

        val items = scheduledTasks.map { task ->
            TaskItemUi(
                task = task,
                date = date,
                isCompleted = completedTaskIds.contains(task.id)
            )
        }

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
    val dailyStats: StateFlow<DailyStats> = combine(
        repository.allTasks,
        repository.allCompletions,
        _selectedDate
    ) { tasks, completions, date ->
        val dateIso = date.toIsoString()
        val completedTaskIds = completions
            .filter { it.date == dateIso }
            .map { it.taskId }
            .toSet()

        val scheduledTasks = tasks.filter { task ->
            repository.isTaskScheduledOnDate(task, date)
        }

        val total = scheduledTasks.size
        val completed = scheduledTasks.count { completedTaskIds.contains(it.id) }
        DailyStats(total = total, completed = completed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DailyStats()
    )

    private data class ParsedTaskSchedule(
        val id: Long,
        val colorHex: Long,
        val isRecurring: Boolean,
        val recurrenceDays: Int,
        val recurrenceDaysOfWeek: Set<Int>?,
        val startDate: AppDate,
        val endDate: AppDate?
    )

    /**
     * Map of Date ISO string to DaySummaryUi for all days in the currently selected month.
     * Highlighting days with something scheduled!
     */
    val monthDaysSummary: StateFlow<Map<String, DaySummaryUi>> = combine(
        repository.allTasks,
        repository.allCompletions,
        _selectedYearMonth,
        _viewMode
    ) { tasks, completions, (year, month), currentViewMode ->
        // Avoid month-summary work when the monthly view is not active
        if (currentViewMode != ViewMode.MONTHLY) {
            return@combine emptyMap<String, DaySummaryUi>()
        }

        val daysInMonth = AppDate.daysInMonth(year, month)
        val summaryMap = mutableMapOf<String, DaySummaryUi>()

        // Precompute reusable schedule information once per task update instead of per day
        val parsedTasks = tasks.mapNotNull { task ->
            val start = try {
                AppDate.parseIso(task.startDate)
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null

            val end = if (!task.endDate.isNullOrBlank()) {
                try { AppDate.parseIso(task.endDate) } catch (_: Exception) { null }
            } else null

            val interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1
            val daysOfWeekSet = if (!task.recurrenceDaysOfWeek.isNullOrBlank()) {
                task.recurrenceDaysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            } else null

            ParsedTaskSchedule(
                id = task.id,
                colorHex = task.colorHex,
                isRecurring = task.isRecurring,
                recurrenceDays = interval,
                recurrenceDaysOfWeek = daysOfWeekSet,
                startDate = start,
                endDate = end
            )
        }

        // Pre-group completions by date
        val completionsByDate = completions.groupBy { it.date }

        for (day in 1..daysInMonth) {
            val date = AppDate(year, month, day)
            val dateIso = date.toIsoString()

            val completedIds = completionsByDate[dateIso]?.map { it.taskId }?.toSet() ?: emptySet()

            val scheduledTasks = parsedTasks.filter { task ->
                if (!task.isRecurring) {
                    task.startDate == date
                } else {
                    if (date < task.startDate) {
                        false
                    } else if (task.endDate != null && date > task.endDate) {
                        false
                    } else if (!task.recurrenceDaysOfWeek.isNullOrEmpty()) {
                        task.recurrenceDaysOfWeek.contains(date.dayOfWeek())
                    } else {
                        date.daysBetween(task.startDate) % task.recurrenceDays == 0L
                    }
                }
            }

            if (scheduledTasks.isNotEmpty()) {
                val completedCount = scheduledTasks.count { completedIds.contains(it.id) }
                val colors = scheduledTasks.take(3).map { it.colorHex }

                summaryMap[dateIso] = DaySummaryUi(
                    date = date,
                    totalTasks = scheduledTasks.size,
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
        val now = AppDate.today()
        _currentToday.value = now
        selectDate(now)
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
        _currentToday.value = now
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

    fun triggerAutoBackup(debounceMs: Long = DEFAULT_AUTO_BACKUP_DEBOUNCE_MS) {
        if (!driveBackupManager.isAutoBackupEnabled()) return
        if (driveBackupManager.getSignedInAccount() == null) return

        isAutoBackupPending = true
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
                isAutoBackupPending = false
                if (!driveBackupManager.isAutoBackupEnabled() || driveBackupManager.getSignedInAccount() == null) {
                    break
                }
                try {
                    val tasks = repository.allTasks.first()
                    val completions = repository.allCompletions.first()
                    val categories = repository.allCategories.first()
                    val result = driveBackupManager.backupToDrive(tasks, completions, categories)
                    if (result.isSuccess) {
                        refreshDriveState()
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Auto-backup failed"
                        refreshDriveState(message = "Auto-backup error: $err", isError = true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    refreshDriveState(message = "Auto-backup failed: ${e.message}", isError = true)
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

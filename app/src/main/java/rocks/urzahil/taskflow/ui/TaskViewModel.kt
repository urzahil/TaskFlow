package rocks.urzahil.taskflow.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import rocks.urzahil.taskflow.data.drive.DriveConnectStep
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.db.CategoryNameConflictException
import rocks.urzahil.taskflow.data.db.LastDefaultCategoryException
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.OccurrenceExclusions
import rocks.urzahil.taskflow.data.repository.TaskSchedule
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.model.DaySummaryUi
import rocks.urzahil.taskflow.ui.model.DayTasksGroupUi
import rocks.urzahil.taskflow.ui.model.SubtaskItemUi
import rocks.urzahil.taskflow.ui.model.TaskFilter
import rocks.urzahil.taskflow.ui.model.TaskItemUi
import rocks.urzahil.taskflow.ui.model.ViewMode
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val KEY_IS_DARK_MODE = "is_dark_mode"
        private const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
        private const val KEY_SHOW_MONTHLY_TASK_LIST = "show_monthly_task_list"
        private const val KEY_SHOW_MONTHLY_VIEW = "show_monthly_view"
        private const val KEY_SHOW_LIST_VIEW = "show_list_view"
        // Older builds stored inverted "hide_*" flags; they are read only when no "show_*" value exists.
        private const val KEY_LEGACY_HIDE_MONTHLY_TASK_LIST = "hide_monthly_task_list"
        private const val KEY_LEGACY_HIDE_MONTHLY_VIEW = "hide_monthly_view"
        private const val KEY_LEGACY_HIDE_LIST_VIEW = "hide_list_view"
        private const val KEY_ROLLOVER_CLEANUP_ENABLED = "rollover_cleanup_enabled"
        private const val KEY_LAST_ROLLOVER_DATE = "last_rollover_date"
        private const val KEY_LAST_ROLLOVER_TIMESTAMP = "last_rollover_timestamp"
        private const val KEY_LAST_AUTOMATIC_ROLLOVER_DATE = "last_automatic_rollover_date"
        private const val KEY_START_VIEW = "start_view_mode"
    }

    private val userPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastAutomaticRolloverDate: String? =
        userPrefs?.getString(KEY_LAST_AUTOMATIC_ROLLOVER_DATE, null)

    private val _isDarkMode = MutableStateFlow(
        userPrefs?.getBoolean(KEY_IS_DARK_MODE, true) ?: true
    )
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        userPrefs?.edit()?.putBoolean(KEY_IS_DARK_MODE, enabled)?.apply()
    }

    private val _useDynamicColors = MutableStateFlow(
        userPrefs?.getBoolean(KEY_USE_DYNAMIC_COLORS, false) ?: false
    )
    val useDynamicColors: StateFlow<Boolean> = _useDynamicColors.asStateFlow()

    fun setUseDynamicColors(enabled: Boolean) {
        _useDynamicColors.value = enabled
        userPrefs?.edit()?.putBoolean(KEY_USE_DYNAMIC_COLORS, enabled)?.apply()
    }

    private fun readShowPreference(showKey: String, legacyHideKey: String): Boolean {
        val prefs = userPrefs ?: return true
        return when {
            prefs.contains(showKey) -> prefs.getBoolean(showKey, true)
            prefs.contains(legacyHideKey) -> !prefs.getBoolean(legacyHideKey, false)
            else -> true
        }
    }

    private val _showMonthlyTaskList = MutableStateFlow(
        readShowPreference(KEY_SHOW_MONTHLY_TASK_LIST, KEY_LEGACY_HIDE_MONTHLY_TASK_LIST)
    )
    val showMonthlyTaskList: StateFlow<Boolean> = _showMonthlyTaskList.asStateFlow()

    fun setShowMonthlyTaskList(show: Boolean) {
        _showMonthlyTaskList.value = show
        userPrefs?.edit()?.putBoolean(KEY_SHOW_MONTHLY_TASK_LIST, show)?.apply()
    }

    private val _showMonthlyView = MutableStateFlow(
        readShowPreference(KEY_SHOW_MONTHLY_VIEW, KEY_LEGACY_HIDE_MONTHLY_VIEW)
    )
    val showMonthlyView: StateFlow<Boolean> = _showMonthlyView.asStateFlow()

    fun setShowMonthlyView(show: Boolean) {
        _showMonthlyView.value = show
        userPrefs?.edit()?.putBoolean(KEY_SHOW_MONTHLY_VIEW, show)?.apply()
        if (!show && _viewMode.value == ViewMode.MONTHLY) {
            _viewMode.value = ViewMode.DAILY
        }
    }

    private val _showListView = MutableStateFlow(
        readShowPreference(KEY_SHOW_LIST_VIEW, KEY_LEGACY_HIDE_LIST_VIEW)
    )
    val showListView: StateFlow<Boolean> = _showListView.asStateFlow()

    fun setShowListView(show: Boolean) {
        _showListView.value = show
        userPrefs?.edit()?.putBoolean(KEY_SHOW_LIST_VIEW, show)?.apply()
        if (!show && _viewMode.value == ViewMode.LIST) {
            _viewMode.value = ViewMode.DAILY
        }
    }

    private val _rolloverCleanupEnabled = MutableStateFlow(
        userPrefs?.getBoolean(KEY_ROLLOVER_CLEANUP_ENABLED, true) ?: true
    )
    val rolloverCleanupEnabled: StateFlow<Boolean> = _rolloverCleanupEnabled.asStateFlow()

    fun setRolloverCleanupEnabled(enabled: Boolean) {
        _rolloverCleanupEnabled.value = enabled
        userPrefs?.edit()?.putBoolean(KEY_ROLLOVER_CLEANUP_ENABLED, enabled)?.apply()
    }

    private val _startViewMode = MutableStateFlow(
        userPrefs?.getString(KEY_START_VIEW, ViewMode.DAILY.name)?.let { name ->
            try {
                val mode = ViewMode.valueOf(name)
                if (mode == ViewMode.SETTINGS) ViewMode.DAILY else mode
            } catch (_: Exception) {
                ViewMode.DAILY
            }
        } ?: ViewMode.DAILY
    )
    val startViewMode: StateFlow<ViewMode> = _startViewMode.asStateFlow()

    fun setStartViewMode(mode: ViewMode) {
        val validMode = if (mode == ViewMode.SETTINGS) ViewMode.DAILY else mode
        _startViewMode.value = validMode
        userPrefs?.edit()?.putString(KEY_START_VIEW, validMode.name)?.apply()
    }

    private fun getInitialViewMode(): ViewMode {
        val preferred = _startViewMode.value
        return when (preferred) {
            ViewMode.MONTHLY -> if (_showMonthlyView.value) ViewMode.MONTHLY else ViewMode.DAILY
            ViewMode.LIST -> if (_showListView.value) ViewMode.LIST else ViewMode.DAILY
            else -> ViewMode.DAILY
        }
    }

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
    private val rolloverMutex = Mutex()
    private val autoBackupChannel = Channel<Unit>(Channel.CONFLATED)
    private var debounceJob: Job? = null

    @Volatile
    private var isAutoBackupPending = false

    private val _selectedDate = MutableStateFlow(_currentToday.value)
    val selectedDate: StateFlow<AppDate> = _selectedDate.asStateFlow()

    private val _selectedYearMonth =
        MutableStateFlow(Pair(_currentToday.value.year, _currentToday.value.month))
    val selectedYearMonth: StateFlow<Pair<Int, Int>> = _selectedYearMonth.asStateFlow()

    private val _viewMode = MutableStateFlow(getInitialViewMode())
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()
    private var viewModeBeforeSettings = _viewMode.value

    private val _taskFilter = MutableStateFlow(TaskFilter.ALL)
    val taskFilter: StateFlow<TaskFilter> = _taskFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Dialog state for adding/editing tasks
    private val _editingTask = MutableStateFlow<TaskEntity?>(null)
    val editingTask: StateFlow<TaskEntity?> = _editingTask.asStateFlow()

    /** The shared subtasks of the task being edited. */
    val editingSubtasks: StateFlow<List<SubtaskEntity>> = combine(
        _editingTask,
        repository.allSubtasks
    ) { task, subtasks ->
        if (task == null) emptyList() else subtasks.filter { it.taskId == task.id && it.date == null }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

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
            isSignedIn = driveBackupManager.isDriveConnected(),
            userEmail = driveBackupManager.getConnectedAccountEmail(),
            lastBackupTimeFormatted = driveBackupManager.getLastBackupTimeFormatted(),
            lastBackupCount = driveBackupManager.getLastBackupCount(),
            lastBackupCategoriesCount = driveBackupManager.getLastBackupCategoriesCount(),
            autoBackupEnabled = driveBackupManager.isAutoBackupEnabled(),
            syncConflict = driveBackupManager.isSyncConflictPending(),
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
            // First, so the starter categories are there before anything else can be slow.
            repository.seedInitialCategoriesIfEmpty()

            // Keep users connected across the move off the removed GoogleSignIn API.
            driveBackupManager.migrateLegacySignIn()

            // On app installation: auto-restore from Google Drive if a backup exists
            val restoredResult = driveBackupManager.checkAndAutoRestoreOnInstall()
            val restored = restoredResult.getOrNull()
            if (restored != null && (restored.taskCount > 0 || restored.categoryCount > 0)) {
                _maintenanceMessage.value =
                    "Restored ${restored.taskCount} tasks & ${restored.categoryCount} categories from Google Drive backup 🎉"
            } else {
                val currentTasks = repository.allTasks.first()
                if (currentTasks.isEmpty()) {
                    repository.seedInitialTasksIfEmpty()
                }
            }
            refreshDriveState()

            // Resume any backup that was durably marked pending before the process was killed or the last attempt failed.
            if (driveBackupManager.isBackupPendingDurable() &&
                driveBackupManager.isAutoBackupEnabled() &&
                driveBackupManager.isDriveConnected()
            ) {
                isAutoBackupPending = true
                autoBackupChannel.trySend(Unit)
            }

            // Run startup check unconditionally to clean old completed tasks and roll forward tasks to today
            val today = AppDate.today()
            _currentToday.value = today
            runCleanupAndRollover(targetDate = today, isForegroundCheck = true)
        }

        // Serialized auto-backup worker loop
        viewModelScope.launch {
            for (event in autoBackupChannel) {
                executeAutoBackupLoop()
            }
        }
    }

    /**
     * Called whenever the app is brought into foreground or when the system reports date change.
     * Updates current today, auto-advances selected date if needed, and executes the cleanup and rollover job.
     */
    fun onAppForegrounded() {
        val now = AppDate.today()
        val previousToday = _currentToday.value
        _currentToday.value = now

        // Auto-advance selected date if the user was on yesterday's 'today'
        if (_selectedDate.value == previousToday && previousToday != now) {
            _selectedDate.value = now
            _selectedYearMonth.value = Pair(now.year, now.month)
        }

        // Retry any durable backup that is still pending when the app returns to the foreground.
        if (driveBackupManager.isBackupPendingDurable() &&
            driveBackupManager.isAutoBackupEnabled() &&
            driveBackupManager.isDriveConnected()
        ) {
            isAutoBackupPending = true
            autoBackupChannel.trySend(Unit)
        }

        // Always execute cleanup and rollover on foreground transition and date changes
        runCleanupAndRollover(targetDate = now, isForegroundCheck = true)
    }

    fun onDateChanged() {
        onAppForegrounded()
    }

    fun runCleanupAndRollover(
        targetDate: AppDate = AppDate.today(),
        isForegroundCheck: Boolean = false,
        onComplete: ((Int, Int, Int) -> Unit)? = null
    ) {
        if (isForegroundCheck && !_rolloverCleanupEnabled.value) {
            return
        }
        viewModelScope.launch {
            rolloverMutex.withLock {
                val targetDateIso = targetDate.toIsoString()
                // Foreground/startup checks are automatic: allow at most one successful run per calendar day.
                // Keep manual runs outside this throttle so the user can always run maintenance on demand.
                if (isForegroundCheck && lastAutomaticRolloverDate == targetDateIso) {
                    return@withLock
                }

                val result = repository.cleanupAndRolloverTasks(targetDate)
                if (isForegroundCheck) {
                    lastAutomaticRolloverDate = targetDateIso
                    userPrefs?.edit()
                        ?.putString(KEY_LAST_AUTOMATIC_ROLLOVER_DATE, targetDateIso)
                        ?.apply()
                }
                val nowTimeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                userPrefs?.edit()
                    ?.putString(KEY_LAST_ROLLOVER_DATE, targetDate.toIsoString())
                    ?.putString(KEY_LAST_ROLLOVER_TIMESTAMP, nowTimeFormatted)
                    ?.apply()
                _lastRolloverInfo.value = Pair(targetDate.toIsoString(), nowTimeFormatted)

                val movedCount = result.movedCount
                val cleanedCount = result.cleanedCount

                if (movedCount > 0 || cleanedCount > 0) {
                    val details = mutableListOf<String>()
                    if (movedCount > 0) {
                        val taskWord = if (movedCount == 1) "task" else "tasks"
                        details.add("moved $movedCount uncompleted $taskWord to today")
                    }
                    if (cleanedCount > 0) {
                        val taskWord = if (cleanedCount == 1) "task" else "tasks"
                        details.add("cleaned $cleanedCount completed $taskWord")
                    }
                    _maintenanceMessage.value =
                        details.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
                } else if (!isForegroundCheck) {
                    _maintenanceMessage.value =
                        "Tasks are up to date for today. No rollover needed."
                }
                onComplete?.invoke(
                    result.cleanedCount,
                    result.movedCount,
                    result.cleanedRecurringOccurrences
                )
            }
        }
    }

    /**
     * Tasks, completions and exclusions as of the latest database change, with every task's
     * recurrence rules parsed once. All task lists and summaries below derive from this, so a
     * change costs three queries and one parse instead of one per list.
     */
    private class ScheduleSnapshot(
        tasks: List<TaskEntity>,
        completions: List<TaskCompletionEntity>,
        private val exclusions: OccurrenceExclusions,
        subtasks: List<SubtaskEntity>,
        subtaskCompletions: List<SubtaskCompletionEntity>
    ) {
        /** Tasks in display order with their schedules; tasks with an unparseable start are never shown. */
        private val scheduled: List<Pair<TaskEntity, TaskSchedule>> =
            tasks.mapNotNull { task -> TaskSchedule.from(task)?.let { task to it } }
        private val completedIdsByDate: Map<String, Set<Long>> =
            completions.groupBy({ it.date }, { it.taskId }).mapValues { (_, ids) -> ids.toHashSet() }

        private val sharedSubtasks: Map<Long, List<SubtaskEntity>> =
            subtasks.filter { it.date == null }.groupBy { it.taskId }
        private val occurrenceSubtasks: Map<Pair<Long, String>, List<SubtaskEntity>> =
            subtasks.filter { it.date != null }.groupBy { it.taskId to it.date!! }
        private val checkedSubtaskIdsByDate: Map<String, Set<Long>> =
            subtaskCompletions.groupBy({ it.date }, { it.subtaskId })
                .mapValues { (_, ids) -> ids.toHashSet() }

        private fun subtasksOf(task: TaskEntity, dateIso: String): List<SubtaskItemUi> {
            val items = (
                if (task.isRecurring && !task.sharedSubtasks) {
                    occurrenceSubtasks[task.id to dateIso]
                } else {
                    sharedSubtasks[task.id]
                }
                ) ?: return emptyList()
            val checked = checkedSubtaskIdsByDate[dateIso].orEmpty()
            return items.map { SubtaskItemUi(it.id, it.title, it.id in checked) }
        }

        private fun scheduledOn(date: AppDate, dateIso: String) = scheduled.filter { (_, schedule) ->
            schedule.isScheduledOn(date) && !exclusions.contains(schedule.taskId, dateIso)
        }

        /** Tasks scheduled on [date], minus individually deleted occurrences, with completion state. */
        fun tasksOn(date: AppDate): List<TaskItemUi> {
            val dateIso = date.toIsoString()
            val completedIds = completedIdsByDate[dateIso].orEmpty()
            return scheduledOn(date, dateIso).map { (task, _) ->
                TaskItemUi(
                    task = task,
                    date = date,
                    isCompleted = task.id in completedIds,
                    subtasks = subtasksOf(task, dateIso)
                )
            }
        }

        /** Per-day summaries for [dates], leaving out days with nothing scheduled. */
        fun summarize(dates: List<AppDate>): Map<String, DaySummaryUi> {
            val summaryMap = mutableMapOf<String, DaySummaryUi>()
            for (date in dates) {
                val dateIso = date.toIsoString()
                val scheduledSchedules = scheduledOn(date, dateIso)
                if (scheduledSchedules.isEmpty()) continue
                val completedIds = completedIdsByDate[dateIso].orEmpty()
                summaryMap[dateIso] = DaySummaryUi(
                    date = date,
                    totalTasks = scheduledSchedules.size,
                    completedTasks = scheduledSchedules.count { (task, _) -> task.id in completedIds },
                    taskColors = scheduledSchedules.take(3).map { (_, schedule) -> schedule.colorHex }
                )
            }
            return summaryMap
        }
    }

    private val snapshot = combine(
        repository.allTasks,
        repository.allCompletions,
        repository.allOccurrenceExclusions,
        repository.allSubtasks,
        repository.allSubtaskCompletions
    ) { tasks, completions, exclusions, subtasks, subtaskCompletions ->
        ScheduleSnapshot(
            tasks, completions, OccurrenceExclusions(exclusions), subtasks, subtaskCompletions
        )
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private fun TaskItemUi.matches(query: String): Boolean =
        task.title.contains(query, ignoreCase = true) ||
                task.description.contains(query, ignoreCase = true) ||
                task.category.contains(query, ignoreCase = true)

    /**
     * Tasks for the currently selected date.
     */
    val dailyTasks: StateFlow<List<TaskItemUi>> = combine(
        snapshot,
        _selectedDate,
        _taskFilter,
        _searchQuery
    ) { snap, date, filter, query ->
        val items = snap.tasksOn(date)
        val queryFiltered = if (query.isBlank()) items else items.filter { it.matches(query) }
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
     * In the selected day overview of the monthly view, always show all scheduled tasks
     * for the selected date regardless of the completion filter (All, pending, done) on the daily page.
     */
    val monthlySelectedDayTasks: StateFlow<List<TaskItemUi>> = combine(
        snapshot,
        _selectedDate
    ) { snap, date ->
        snap.tasksOn(date)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Tasks for the next 7 days (today and 6 more) for the 7-day List view.
     */
    val next7DaysTasks: StateFlow<List<DayTasksGroupUi>> = combine(
        snapshot,
        _currentToday,
        _searchQuery
    ) { snap, today, query ->
        (0L..6L).map { offset ->
            val date = today.plusDays(offset)
            val items = snap.tasksOn(date)
            DayTasksGroupUi(
                date = date,
                dayName = AppDate.dayOfWeekName(date.dayOfWeek()),
                tasks = if (query.isBlank()) items else items.filter { it.matches(query) }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Statistics for the currently selected date (total, completed). Derived from the unfiltered
     * day list, so individually deleted recurring occurrences do not count.
     */
    val dailyStats: StateFlow<DailyStats> = monthlySelectedDayTasks
        .map { items -> DailyStats(total = items.size, completed = items.count { it.isCompleted }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DailyStats()
        )

    /**
     * Map of Date ISO string to DaySummaryUi for all days in the currently selected month.
     */
    val monthDaysSummary: StateFlow<Map<String, DaySummaryUi>> = combine(
        snapshot,
        _selectedYearMonth
    ) { snap, (year, month) ->
        snap.summarize((1..AppDate.daysInMonth(year, month)).map { AppDate(year, month, it) })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    /**
     * Map of Date ISO string to DaySummaryUi for the 7 days visible in the Daily view's week list strip.
     */
    val weekDaysSummary: StateFlow<Map<String, DaySummaryUi>> = combine(
        snapshot,
        _selectedDate
    ) { snap, selectedDate ->
        snap.summarize((-3..3).map { selectedDate.plusDays(it.toLong()) })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
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
        if (mode == ViewMode.SETTINGS && _viewMode.value != ViewMode.SETTINGS) {
            viewModeBeforeSettings = _viewMode.value
        }
        _viewMode.value = mode
    }

    fun closeSettings() {
        if (_viewMode.value != ViewMode.SETTINGS) return
        _viewMode.value = when (viewModeBeforeSettings) {
            ViewMode.MONTHLY -> if (_showMonthlyView.value) ViewMode.MONTHLY else ViewMode.DAILY
            ViewMode.LIST -> if (_showListView.value) ViewMode.LIST else ViewMode.DAILY
            else -> ViewMode.DAILY
        }
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

    fun toggleSubtask(taskItem: TaskItemUi, subtask: SubtaskItemUi) {
        viewModelScope.launch {
            repository.toggleSubtask(taskItem.task.id, subtask.id, taskItem.date.toIsoString())
            triggerAutoBackup()
        }
    }

    fun addOccurrenceSubtask(taskItem: TaskItemUi, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.addOccurrenceSubtask(
                taskId = taskItem.task.id,
                dateIso = taskItem.date.toIsoString(),
                title = title.trim(),
                position = taskItem.subtasks.size
            )
            triggerAutoBackup()
        }
    }

    fun removeOccurrenceSubtask(subtask: SubtaskItemUi) {
        viewModelScope.launch {
            repository.removeOccurrenceSubtask(subtask.id)
            triggerAutoBackup()
        }
    }

    fun saveTask(task: TaskEntity, subtasks: List<SubtaskEntity>) {
        viewModelScope.launch {
            val taskId = if (task.id == 0L) {
                repository.insertTask(task)
            } else {
                repository.updateTask(task)
                task.id
            }
            repository.replaceSharedSubtasks(taskId, subtasks)
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

    fun deleteRecurringOccurrence(taskItem: TaskItemUi) {
        if (!taskItem.task.isRecurring) return
        viewModelScope.launch {
            repository.deleteRecurringOccurrence(taskItem.task.id, taskItem.date.toIsoString())
            triggerAutoBackup()
        }
    }

    /** Runs a category mutation, surfacing validation failures instead of crashing. */
    private fun launchCategoryChange(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                triggerAutoBackup()
            } catch (e: CategoryNameConflictException) {
                _maintenanceMessage.value = e.message
            } catch (e: LastDefaultCategoryException) {
                _maintenanceMessage.value = e.message
            }
        }
    }

    fun addCategory(name: String, colorHex: Long, iconName: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        launchCategoryChange {
            repository.addCategory(
                CategoryEntity(
                    name = trimmed,
                    colorHex = colorHex,
                    iconName = iconName,
                    isDefault = false
                )
            )
        }
    }

    fun deleteCategory(categoryName: String) {
        launchCategoryChange { repository.deleteCategory(categoryName) }
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
        launchCategoryChange {
            repository.updateCategory(oldName, trimmed, colorHex, iconName, isDefault)
        }
    }

    fun triggerAutoBackup(debounceMs: Long = DEFAULT_AUTO_BACKUP_DEBOUNCE_MS) {
        if (!driveBackupManager.isAutoBackupEnabled()) return
        if (!driveBackupManager.isDriveConnected()) return

        // Stay quiet (but remember the change) until the user resolves a Drive/local conflict.
        if (driveBackupManager.isSyncConflictPending()) {
            driveBackupManager.setBackupPendingDurable(true)
            return
        }

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
                isAutoBackupPending = false
                if (driveBackupManager.isSyncConflictPending()) break
                if (!driveBackupManager.isAutoBackupEnabled() || !driveBackupManager.isDriveConnected()) {
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
                    val subtasks = repository.allSubtasks.first()
                    val subtaskCompletions = repository.allSubtaskCompletions.first()
                    val result = driveBackupManager.backupToDrive(
                        tasks, completions, categories, exclusions, subtasks, subtaskCompletions
                    )
                    if (result.isSuccess) {
                        driveBackupManager.setBackupPendingDurable(false)
                        refreshDriveState()
                    } else {
                        isAutoBackupPending = true
                        driveBackupManager.setBackupPendingDurable(true)
                        val err = result.exceptionOrNull()?.message ?: "Auto-backup failed"
                        refreshDriveState(message = "Auto-backup error: $err", isError = true)
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    refreshDriveState(message = "Auto-backup failed: ${e.message}", isError = true)
                }
            }
        }
    }

    fun refreshDriveState(message: String? = null, isError: Boolean = false) {
        val accountEmail = driveBackupManager.getConnectedAccountEmail()
        _driveSyncState.value = DriveSyncState(
            isSignedIn = accountEmail != null,
            userEmail = accountEmail,
            isSyncing = false,
            lastBackupTimeFormatted = driveBackupManager.getLastBackupTimeFormatted(),
            lastBackupCount = driveBackupManager.getLastBackupCount(),
            lastBackupCategoriesCount = driveBackupManager.getLastBackupCategoriesCount(),
            autoBackupEnabled = driveBackupManager.isAutoBackupEnabled(),
            syncConflict = driveBackupManager.isSyncConflictPending(),
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
                    val exclusions = repository.allOccurrenceExclusions.first()
                    val subtasks = repository.allSubtasks.first()
                    val subtaskCompletions = repository.allSubtaskCompletions.first()
                    val result = driveBackupManager.backupToDrive(
                        tasks, completions, categories, exclusions, subtasks, subtaskCompletions
                    )
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
                val result = driveBackupMutex.withLock {
                    driveBackupManager.restoreFromDrive()
                }
                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    onLocalDataReplaced()
                    refreshDriveState(
                        message = "Restored ${res.taskCount} tasks & ${res.categoryCount} categories from Google Drive! 🎉",
                        isError = false
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Restore failed"
                    refreshDriveState(message = "Restore error: $err", isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refreshDriveState(message = "Restore failed: ${e.message}", isError = true)
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        driveBackupManager.setAutoBackupEnabled(enabled)
        refreshDriveState()
    }

    /** Consent screens for Drive access, for the UI to launch; results go to [onDriveConsentResult]. */
    private val _driveConsentRequests = Channel<PendingIntent>(Channel.CONFLATED)
    val driveConsentRequests: Flow<PendingIntent> = _driveConsentRequests.receiveAsFlow()

    /** Connects Google Drive, asking the UI to show Google's consent screen when needed. */
    fun connectGoogleDrive() {
        viewModelScope.launch {
            _driveSyncState.value = _driveSyncState.value.copy(isSyncing = true, syncMessage = null)
            try {
                when (val step = driveBackupManager.connect()) {
                    is DriveConnectStep.Connected -> onDriveConnected(step.accountEmail)
                    is DriveConnectStep.NeedsUserConsent -> {
                        refreshDriveState()
                        _driveConsentRequests.send(step.pendingIntent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDriveConnectFailed(e)
            }
        }
    }

    /** Handles the result of the consent screen requested through [driveConsentRequests]. */
    fun onDriveConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            refreshDriveState("Google Drive was not connected", isError = true)
            return
        }
        viewModelScope.launch {
            try {
                onDriveConnected(driveBackupManager.completeConnect(data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDriveConnectFailed(e)
            }
        }
    }

    private fun onDriveConnectFailed(e: Exception) {
        if ((e as? ApiException)?.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
            // The app's signing key is not registered with an OAuth client (sign-in error 10).
            setShowGoogleError10Dialog(true)
            refreshDriveState("Google sign-in error 10: OAuth client setup required", isError = true)
        } else {
            refreshDriveState("Could not connect Google Drive: ${e.message}", isError = true)
        }
    }

    private fun onDriveConnected(accountEmail: String) {
        refreshDriveState(message = "Connected to Google Drive as $accountEmail")
        // Check if fresh tasks can be auto-restored
        viewModelScope.launch {
            val currentTasks = repository.allTasks.first()
            if (currentTasks.isEmpty()) {
                val result = driveBackupMutex.withLock {
                    driveBackupManager.restoreFromDrive()
                }
                val res = result.getOrNull()
                if (res != null && (res.taskCount > 0 || res.categoryCount > 0)) {
                    onLocalDataReplaced()
                    refreshDriveState(
                        message = "Connected as $accountEmail and restored ${res.taskCount} tasks & ${res.categoryCount} categories from Drive! 🎉",
                        isError = false
                    )
                }
            } else if (driveBackupManager.detectSyncConflict()) {
                // Both this device and Drive hold data: ask before anything is overwritten.
                refreshDriveState(
                    message = "Connected as $accountEmail. Drive already has a backup, so automatic backup is paused until you choose which data to keep.",
                    isError = false
                )
            }
        }
    }

    /** Resolve a sync conflict by replacing this device's data with the Drive backup. */
    fun resolveSyncConflictKeepDrive() = restoreFromDrive()

    /** Resolve a sync conflict by replacing the Drive backup with this device's data. */
    fun resolveSyncConflictUseThisDevice() = backupToDrive()

    /**
     * After the whole local data set was swapped by a restore, lift the once-per-day automatic
     * rollover throttle and run it, so restored past-dated tasks are rolled over right away.
     */
    private fun onLocalDataReplaced() {
        lastAutomaticRolloverDate = null
        userPrefs?.edit()?.remove(KEY_LAST_AUTOMATIC_ROLLOVER_DATE)?.apply()
        runCleanupAndRollover(targetDate = AppDate.today(), isForegroundCheck = true)
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            driveBackupManager.disconnect()
            refreshDriveState(message = "Disconnected from Google Drive")
        }
    }

    suspend fun getBackupJsonString(): String {
        val tasks = repository.allTasks.first()
        val completions = repository.allCompletions.first()
        val categories = repository.allCategories.first()
        val exclusions = repository.allOccurrenceExclusions.first()
        val subtasks = repository.allSubtasks.first()
        val subtaskCompletions = repository.allSubtaskCompletions.first()
        return driveBackupManager.exportBackupJson(
            tasks, completions, categories, exclusions, subtasks, subtaskCompletions
        )
    }

    fun restoreFromJson(jsonString: String) {
        viewModelScope.launch {
            _driveSyncState.value = _driveSyncState.value.copy(isSyncing = true, syncMessage = null)
            try {
                val result = driveBackupMutex.withLock {
                    driveBackupManager.restoreFromJson(jsonString)
                }
                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    onLocalDataReplaced()
                    refreshDriveState(
                        message = "Restored ${res.taskCount} tasks & ${res.categoryCount} categories from backup file! 🎉",
                        isError = false
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Invalid backup file"
                    refreshDriveState(message = "Restore error: $err", isError = true)
                }
            } catch (e: CancellationException) {
                throw e
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

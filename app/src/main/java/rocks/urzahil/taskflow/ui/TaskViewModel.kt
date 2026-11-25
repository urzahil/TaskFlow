package rocks.urzahil.taskflow.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import rocks.urzahil.taskflow.data.drive.GoogleDriveBackupManager
import rocks.urzahil.taskflow.data.prefs.UserPreferencesRepository
import rocks.urzahil.taskflow.data.db.CategoryNameConflictException
import rocks.urzahil.taskflow.data.db.LastDefaultCategoryException
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.SubtaskExclusionEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity
import rocks.urzahil.taskflow.data.repository.OccurrenceExclusions
import rocks.urzahil.taskflow.data.repository.TaskSchedule
import rocks.urzahil.taskflow.data.repository.TaskRepository
import rocks.urzahil.taskflow.ui.model.DaySummaryUi
import rocks.urzahil.taskflow.ui.model.DayTasksGroupUi
import rocks.urzahil.taskflow.ui.model.SubtaskItemUi
import rocks.urzahil.taskflow.ui.model.TaskFilter
import rocks.urzahil.taskflow.ui.model.TaskItemUi
import rocks.urzahil.taskflow.ui.monthly.monthGridDates
import rocks.urzahil.taskflow.ui.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        const val DEFAULT_AUTO_BACKUP_DEBOUNCE_MS = DriveSyncCoordinator.DEFAULT_AUTO_BACKUP_DEBOUNCE_MS

        // Views that show tasks with collapsible subtasks; each keeps its own collapse state.
        val SUBTASK_COLLAPSE_VIEWS = UserPreferencesRepository.SUBTASK_COLLAPSE_VIEWS
    }

    private val preferences = UserPreferencesRepository(context)

    val isDarkMode: StateFlow<Boolean> = preferences.isDarkMode
    fun setDarkMode(enabled: Boolean) = preferences.setDarkMode(enabled)

    val useDynamicColors: StateFlow<Boolean> = preferences.useDynamicColors
    fun setUseDynamicColors(enabled: Boolean) = preferences.setUseDynamicColors(enabled)

    val showMonthlyTaskList: StateFlow<Boolean> = preferences.showMonthlyTaskList
    fun setShowMonthlyTaskList(show: Boolean) = preferences.setShowMonthlyTaskList(show)

    val showMonthlyView: StateFlow<Boolean> = preferences.showMonthlyView

    fun setShowMonthlyView(show: Boolean) {
        preferences.setShowMonthlyView(show)
        if (!show && _viewMode.value == ViewMode.MONTHLY) {
            _viewMode.value = ViewMode.DAILY
        }
    }

    val showListView: StateFlow<Boolean> = preferences.showListView

    fun setShowListView(show: Boolean) {
        preferences.setShowListView(show)
        if (!show && _viewMode.value == ViewMode.LIST) {
            _viewMode.value = ViewMode.DAILY
        }
    }

    val rolloverCleanupEnabled: StateFlow<Boolean> = preferences.rolloverCleanupEnabled
    fun setRolloverCleanupEnabled(enabled: Boolean) = preferences.setRolloverCleanupEnabled(enabled)

    val startViewMode: StateFlow<ViewMode> = preferences.startViewMode
    fun setStartViewMode(mode: ViewMode) = preferences.setStartViewMode(mode)

    private fun getInitialViewMode(): ViewMode {
        return when (preferences.startViewMode.value) {
            ViewMode.MONTHLY -> if (preferences.showMonthlyView.value) ViewMode.MONTHLY else ViewMode.DAILY
            ViewMode.LIST -> if (preferences.showListView.value) ViewMode.LIST else ViewMode.DAILY
            else -> ViewMode.DAILY
        }
    }

    val showDailyProgress: StateFlow<Boolean> = preferences.showDailyProgress
    fun setShowDailyProgress(show: Boolean) = preferences.setShowDailyProgress(show)

    private val _currentToday = MutableStateFlow(AppDate.today())
    val currentToday: StateFlow<AppDate> = _currentToday.asStateFlow()

    val lastRolloverInfo: StateFlow<Pair<String, String>?> = preferences.lastRolloverInfo

    val hideCompletedInList: StateFlow<Boolean> = preferences.hideCompletedInList
    fun setHideCompletedInList(hide: Boolean) = preferences.setHideCompletedInList(hide)

    val collapsibleDaysInList: StateFlow<Boolean> = preferences.collapsibleDaysInList
    fun setCollapsibleDaysInList(enabled: Boolean) = preferences.setCollapsibleDaysInList(enabled)

    val collapsedListDays: StateFlow<Set<AppDate>> = preferences.collapsedListDays
    fun toggleListDayCollapsed(date: AppDate) = preferences.toggleListDayCollapsed(date)

    val subtasksCollapsedByDefault: StateFlow<Boolean> = preferences.subtasksCollapsedByDefault
    fun setSubtasksCollapsedByDefault(collapsed: Boolean) =
        preferences.setSubtasksCollapsedByDefault(collapsed)

    val subtaskCollapseOverrides: StateFlow<Map<ViewMode, Set<Long>>> =
        preferences.subtaskCollapseOverrides

    fun isSubtasksCollapsed(view: ViewMode, taskId: Long): Boolean =
        preferences.isSubtasksCollapsed(view, taskId)

    fun toggleSubtasksCollapsed(view: ViewMode, taskId: Long) =
        preferences.toggleSubtasksCollapsed(view, taskId)

    private fun expandSubtasks(view: ViewMode, taskId: Long) =
        preferences.expandSubtasks(view, taskId)

    private fun clearSubtaskCollapseOverride(taskId: Long) =
        preferences.clearSubtaskCollapseOverride(taskId)

    private val rolloverMutex = Mutex()

    private val drive = DriveSyncCoordinator(
        repository = repository,
        backupManager = driveBackupManager,
        scope = viewModelScope,
        context = context,
        onLocalDataReplaced = { onLocalDataReplaced() }
    )

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

    val driveSyncState: StateFlow<DriveSyncState> = drive.state

    fun dismissSyncMessage() = drive.dismissSyncMessage()

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
            } else if (restoredResult.isSuccess) {
                val currentTasks = repository.allTasks.first()
                if (currentTasks.isEmpty()) {
                    repository.seedInitialTasksIfEmpty()
                }
            }
            // On failure (e.g. Drive unreachable) nothing is seeded: starter tasks would count as
            // local data and the Drive backup lookup would never be retried.
            drive.refreshState()

            // Resume any backup that was durably marked pending before the process was killed or the last attempt failed.
            drive.resumePendingBackup()

            // Run startup check unconditionally to clean old completed tasks and roll forward tasks to today
            val today = AppDate.today()
            _currentToday.value = today
            runCleanupAndRollover(targetDate = today, isForegroundCheck = true)
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
        drive.resumePendingBackup()

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
        if (isForegroundCheck && !preferences.rolloverCleanupEnabled.value) {
            return
        }
        viewModelScope.launch {
            rolloverMutex.withLock {
                val targetDateIso = targetDate.toIsoString()
                // Foreground/startup checks are automatic: allow at most one successful run per calendar day.
                // Keep manual runs outside this throttle so the user can always run maintenance on demand.
                if (isForegroundCheck && preferences.lastAutomaticRolloverDate == targetDateIso) {
                    return@withLock
                }

                val result = repository.cleanupAndRolloverTasks(targetDate)
                if (isForegroundCheck) {
                    preferences.recordAutomaticRollover(targetDateIso)
                }
                val nowTimeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                preferences.recordRollover(targetDate.toIsoString(), nowTimeFormatted)

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
                    // Rollover rewrote or deleted tasks: back that up like any other change.
                    triggerAutoBackup()
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
        subtaskCompletions: List<SubtaskCompletionEntity>,
        subtaskExclusions: List<SubtaskExclusionEntity>
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

        private val removedSubtaskIdsByDate: Map<String, Set<Long>> =
            subtaskExclusions.groupBy({ it.date }, { it.subtaskId })
                .mapValues { (_, ids) -> ids.toHashSet() }

        private fun subtasksOf(task: TaskEntity, dateIso: String): List<SubtaskItemUi> {
            val own = occurrenceSubtasks[task.id to dateIso].orEmpty()
            val removed = removedSubtaskIdsByDate[dateIso].orEmpty()
            val shared = if (task.sharedSubtasks) {
                sharedSubtasks[task.id].orEmpty().filter { it.id !in removed }
            } else {
                emptyList()
            }
            val checked = checkedSubtaskIdsByDate[dateIso].orEmpty()
            return (shared + own).map { SubtaskItemUi(it.id, it.title, it.id in checked, it.date == null) }
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
        fun summarize(dates: List<AppDate>, query: String = ""): Map<String, DaySummaryUi> {
            val summaryMap = mutableMapOf<String, DaySummaryUi>()
            for (date in dates) {
                if (query.isNotBlank()) {
                    // Only the tasks matching the search count towards the day's indicator.
                    val matching = tasksOn(date).filter { it.matches(query) }
                    if (matching.isNotEmpty()) {
                        summaryMap[date.toIsoString()] = DaySummaryUi(
                            date = date,
                            totalTasks = matching.size,
                            completedTasks = matching.count { it.isCompleted },
                            taskColors = matching.take(3).map { it.task.colorHex }
                        )
                    }
                    continue
                }
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

    @Suppress("UNCHECKED_CAST")
    private val snapshot = combine(
        repository.allTasks,
        repository.allCompletions,
        repository.allOccurrenceExclusions,
        repository.allSubtasks,
        repository.allSubtaskCompletions,
        repository.allSubtaskExclusions
    ) { values ->
        ScheduleSnapshot(
            values[0] as List<TaskEntity>,
            values[1] as List<TaskCompletionEntity>,
            OccurrenceExclusions(values[2] as List<TaskOccurrenceExclusionEntity>),
            values[3] as List<SubtaskEntity>,
            values[4] as List<SubtaskCompletionEntity>,
            values[5] as List<SubtaskExclusionEntity>
        )
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

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
    private val selectedDayTasksUnfiltered: StateFlow<List<TaskItemUi>> = combine(
        snapshot,
        _selectedDate
    ) { snap, date ->
        snap.tasksOn(date)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** The monthly view's selected-day list; narrowed by the search query like the other views. */
    val monthlySelectedDayTasks: StateFlow<List<TaskItemUi>> = combine(
        selectedDayTasksUnfiltered,
        _searchQuery
    ) { items, query ->
        if (query.isBlank()) items else items.filter { it.matches(query) }
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
                dayName = when (offset) {
                    0L -> "Today"
                    1L -> "Tomorrow"
                    else -> AppDate.dayOfWeekName(date.dayOfWeek())
                },
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
    val dailyStats: StateFlow<DailyStats> = selectedDayTasksUnfiltered
        .map { items -> DailyStats(total = items.size, completed = items.count { it.isCompleted }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DailyStats()
        )

    /**
     * Map of Date ISO string to DaySummaryUi for every day shown in the selected month's grid.
     */
    val monthDaysSummary: StateFlow<Map<String, DaySummaryUi>> = combine(
        snapshot,
        _selectedYearMonth,
        _searchQuery
    ) { snap, (year, month), query ->
        // The whole grid, so the leading/trailing days of adjacent months show their tasks too.
        snap.summarize(monthGridDates(year, month), query)
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
            ViewMode.MONTHLY -> if (preferences.showMonthlyView.value) ViewMode.MONTHLY else ViewMode.DAILY
            ViewMode.LIST -> if (preferences.showListView.value) ViewMode.LIST else ViewMode.DAILY
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
        // Subtasks are only added from the Daily view.
        expandSubtasks(ViewMode.DAILY, taskItem.task.id)
        viewModelScope.launch {
            repository.addOccurrenceSubtask(
                taskId = taskItem.task.id,
                dateIso = taskItem.date.toIsoString(),
                title = title.trim(),
                position = taskItem.subtasks.size,
                isRecurring = taskItem.task.isRecurring
            )
            triggerAutoBackup()
        }
    }

    fun removeSubtask(taskItem: TaskItemUi, subtask: SubtaskItemUi) {
        viewModelScope.launch {
            repository.removeSubtaskOn(
                taskItem.task.id, subtask.id, taskItem.date.toIsoString(),
                subtask.shared && taskItem.task.isRecurring
            )
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
        // Cleared up front, not after the suspending delete, so the state is gone as soon as
        // deleteTask returns.
        clearSubtaskCollapseOverride(task.id)
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

    fun triggerAutoBackup(debounceMs: Long = DEFAULT_AUTO_BACKUP_DEBOUNCE_MS) =
        drive.triggerAutoBackup(debounceMs)

    suspend fun flushAutoBackup() = drive.flushAutoBackup()

    fun refreshDriveState(message: String? = null, isError: Boolean = false) =
        drive.refreshState(message, isError)

    fun backupToDrive() = drive.backupToDrive()

    fun restoreFromDrive() = drive.restoreFromDrive()

    fun setAutoBackupEnabled(enabled: Boolean) = drive.setAutoBackupEnabled(enabled)

    /** Consent screens for Drive access, for the UI to launch; results go to [onDriveConsentResult]. */
    val driveConsentRequests: Flow<PendingIntent> = drive.consentRequests

    /** Connects Google Drive, asking the UI to show Google's consent screen when needed. */
    fun connectGoogleDrive() = drive.connect()

    /** Handles the result of the consent screen requested through [driveConsentRequests]. */
    fun onDriveConsentResult(resultCode: Int, data: Intent?) = drive.onConsentResult(resultCode, data)

    /** Resolve a sync conflict by replacing this device's data with the Drive backup. */
    fun resolveSyncConflictKeepDrive() = drive.resolveSyncConflictKeepDrive()

    /** Resolve a sync conflict by replacing the Drive backup with this device's data. */
    fun resolveSyncConflictUseThisDevice() = drive.resolveSyncConflictUseThisDevice()

    /**
     * After the whole local data set was swapped by a restore, lift the once-per-day automatic
     * rollover throttle and run it, so restored past-dated tasks are rolled over right away.
     */
    private fun onLocalDataReplaced() {
        preferences.clearAutomaticRolloverThrottle()
        runCleanupAndRollover(targetDate = AppDate.today(), isForegroundCheck = true)
    }

    fun disconnectGoogleDrive() = drive.disconnect()

    suspend fun getBackupJsonString(): String = drive.getBackupJsonString()

    fun restoreFromJson(jsonString: String) = drive.restoreFromJson(jsonString)

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

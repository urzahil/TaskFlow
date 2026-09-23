package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import com.example.ui.model.DaySummaryUi
import com.example.ui.model.TaskFilter
import com.example.ui.model.TaskItemUi
import com.example.ui.model.ViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DailyStats(
    val total: Int = 0,
    val completed: Int = 0
) {
    val progress: Float
        get() = if (total > 0) completed.toFloat() / total.toFloat() else 0f
}

class TaskViewModel(
    private val repository: TaskRepository
) : ViewModel() {

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
            val currentTasks = repository.allTasks.first()
            if (currentTasks.isEmpty()) {
                repository.seedInitialTasksIfEmpty()
            }
            // Run startup task to clean old completed tasks and move uncompleted tasks to today
            runCleanupAndRollover()
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

    /**
     * Map of Date ISO string to DaySummaryUi for all days in the currently selected month.
     * Highlighting days with something scheduled!
     */
    val monthDaysSummary: StateFlow<Map<String, DaySummaryUi>> = combine(
        repository.allTasks,
        repository.allCompletions,
        _selectedYearMonth
    ) { tasks, completions, (year, month) ->
        val daysInMonth = AppDate.daysInMonth(year, month)
        val summaryMap = mutableMapOf<String, DaySummaryUi>()

        // Pre-group completions by date
        val completionsByDate = completions.groupBy { it.date }

        for (day in 1..daysInMonth) {
            val date = AppDate(year, month, day)
            val dateIso = date.toIsoString()

            val completedIds = completionsByDate[dateIso]?.map { it.taskId }?.toSet() ?: emptySet()

            val scheduledTasks = tasks.filter { task ->
                repository.isTaskScheduledOnDate(task, date)
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
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
            if (_editingTask.value?.id == task.id) {
                closeAddEditDialog()
            }
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
        }
    }

    fun deleteCategory(categoryName: String) {
        viewModelScope.launch {
            repository.deleteCategory(categoryName)
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
        }
    }

    class Factory(private val repository: TaskRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
                return TaskViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

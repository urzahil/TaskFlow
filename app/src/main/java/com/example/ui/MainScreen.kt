package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppDate
import com.example.ui.components.TaskAddEditSheet
import com.example.ui.daily.DailyView
import com.example.ui.model.ViewMode
import com.example.ui.monthly.MonthlyView
import com.example.ui.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: TaskViewModel
) {
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val selectedYearMonth by viewModel.selectedYearMonth.collectAsStateWithLifecycle()
    val dailyTasks by viewModel.dailyTasks.collectAsStateWithLifecycle()
    val dailyStats by viewModel.dailyStats.collectAsStateWithLifecycle()
    val taskFilter by viewModel.taskFilter.collectAsStateWithLifecycle()
    val monthSummaries by viewModel.monthDaysSummary.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    val isAddEditSheetOpen by viewModel.isAddEditSheetOpen.collectAsStateWithLifecycle()
    val editingTask by viewModel.editingTask.collectAsStateWithLifecycle()
    val maintenanceMessage by viewModel.maintenanceMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var isSearchActive by remember { mutableStateOf(false) }

    LaunchedEffect(maintenanceMessage) {
        maintenanceMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMaintenanceMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (viewMode == ViewMode.SETTINGS) {
                            IconButton(
                                onClick = { viewModel.setViewMode(ViewMode.DAILY) },
                                modifier = Modifier.testTag("back_from_settings_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to tasks"
                                )
                            }
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (viewMode == ViewMode.SETTINGS) "Settings" else "TaskFlow",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        if (viewMode == ViewMode.DAILY && selectedDate != AppDate.today()) {
                            IconButton(
                                onClick = { viewModel.jumpToToday() },
                                modifier = Modifier.testTag("jump_today_top_button")
                            ) {
                                Icon(
                                    Icons.Default.Today,
                                    contentDescription = "Jump to Today",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (viewMode != ViewMode.SETTINGS) {
                            IconButton(
                                onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) viewModel.setSearchQuery("")
                                },
                                modifier = Modifier.testTag("search_toggle_button")
                            ) {
                                Icon(
                                    if (isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                                    contentDescription = "Search"
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.setViewMode(
                                    if (viewMode == ViewMode.SETTINGS) ViewMode.DAILY else ViewMode.SETTINGS
                                )
                            },
                            modifier = Modifier.testTag("settings_top_button")
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (viewMode == ViewMode.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Search field row when active (only in task views)
                if (viewMode != ViewMode.SETTINGS) {
                    AnimatedVisibility(visible = isSearchActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text("Filter tasks by title, note, or tag...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("search_input_field"),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    // View Mode Tabs: Daily View vs Monthly Calendar only
                    TabRow(
                        selectedTabIndex = if (viewMode == ViewMode.MONTHLY) 1 else 0,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("view_mode_tab_row")
                    ) {
                        Tab(
                            selected = viewMode == ViewMode.DAILY,
                            onClick = { viewModel.setViewMode(ViewMode.DAILY) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.FormatListBulleted,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Daily", fontWeight = FontWeight.SemiBold)
                                }
                            },
                            modifier = Modifier.testTag("tab_daily_view")
                        )
                        Tab(
                            selected = viewMode == ViewMode.MONTHLY,
                            onClick = { viewModel.setViewMode(ViewMode.MONTHLY) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CalendarMonth,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Monthly", fontWeight = FontWeight.SemiBold)
                                }
                            },
                            modifier = Modifier.testTag("tab_monthly_view")
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (viewMode != ViewMode.SETTINGS) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openAddTaskDialog() },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Task") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("add_task_fab")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (viewMode) {
                ViewMode.DAILY -> {
                    DailyView(
                        selectedDate = selectedDate,
                        tasks = dailyTasks,
                        stats = dailyStats,
                        currentFilter = taskFilter,
                        onFilterChange = { viewModel.setFilter(it) },
                        onDateSelect = { viewModel.selectDate(it) },
                        onPrevDay = { viewModel.prevDay() },
                        onNextDay = { viewModel.nextDay() },
                        onJumpToToday = { viewModel.jumpToToday() },
                        onToggleTask = { viewModel.toggleTaskCompletion(it) },
                        onEditTask = { viewModel.openEditTaskDialog(it) },
                        onDeleteTask = { viewModel.deleteTask(it) },
                        onAddTask = { viewModel.openAddTaskDialog() }
                    )
                }
                ViewMode.MONTHLY -> {
                    MonthlyView(
                        yearMonth = selectedYearMonth,
                        selectedDate = selectedDate,
                        monthSummaries = monthSummaries,
                        selectedDayTasks = dailyTasks,
                        onSelectDate = { viewModel.selectDate(it) },
                        onPrevMonth = { viewModel.prevMonth() },
                        onNextMonth = { viewModel.nextMonth() },
                        onJumpToCurrentMonth = { viewModel.jumpToCurrentMonth() },
                        onToggleTask = { viewModel.toggleTaskCompletion(it) },
                        onEditTask = { viewModel.openEditTaskDialog(it) },
                        onDeleteTask = { viewModel.deleteTask(it) },
                        onSwitchToDailyView = { viewModel.setViewMode(ViewMode.DAILY) },
                        onAddTaskForDay = { viewModel.openAddTaskDialog() }
                    )
                }
                ViewMode.SETTINGS -> {
                    SettingsScreen(
                        categories = categories,
                        onAddCategory = { name, colorHex, iconName ->
                            viewModel.addCategory(name, colorHex, iconName)
                        },
                        onEditCategory = { oldName, newName, colorHex, iconName, isDefault ->
                            viewModel.updateCategory(oldName, newName, colorHex, iconName, isDefault)
                        },
                        onDeleteCategory = { name ->
                            viewModel.deleteCategory(name)
                        },
                        onRunMaintenance = {
                            viewModel.runCleanupAndRollover()
                        }
                    )
                }
            }
        }
    }

    // Task Add / Edit Modal Bottom Sheet
    if (isAddEditSheetOpen) {
        TaskAddEditSheet(
            initialDate = selectedDate,
            existingTask = editingTask,
            onDismiss = { viewModel.closeAddEditDialog() },
            onSave = { task -> viewModel.saveTask(task) },
            availableCategories = categories,
            onDelete = { task -> viewModel.deleteTask(task) }
        )
    }
}

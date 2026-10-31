package rocks.urzahil.taskflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rocks.urzahil.taskflow.data.drive.BackupFileReader
import rocks.urzahil.taskflow.data.drive.GoogleOAuthHelper
import rocks.urzahil.taskflow.ui.components.TaskAddEditSheet
import rocks.urzahil.taskflow.ui.daily.DailyView
import rocks.urzahil.taskflow.ui.list.ListView
import rocks.urzahil.taskflow.ui.model.ViewMode
import rocks.urzahil.taskflow.ui.monthly.MonthlyView
import rocks.urzahil.taskflow.ui.settings.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: TaskViewModel
) {
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val selectedYearMonth by viewModel.selectedYearMonth.collectAsStateWithLifecycle()
    val taskFilter by viewModel.taskFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    val isAddEditSheetOpen by viewModel.isAddEditSheetOpen.collectAsStateWithLifecycle()
    val editingTask by viewModel.editingTask.collectAsStateWithLifecycle()
    val editingSubtasks by viewModel.editingSubtasks.collectAsStateWithLifecycle()
    val maintenanceMessage by viewModel.maintenanceMessage.collectAsStateWithLifecycle()
    val driveSyncState by viewModel.driveSyncState.collectAsStateWithLifecycle()
    val showDailyProgress by viewModel.showDailyProgress.collectAsStateWithLifecycle()
    val subtaskCollapseOverrides by viewModel.subtaskCollapseOverrides.collectAsStateWithLifecycle()
    val subtasksCollapsedByDefault by viewModel.subtasksCollapsedByDefault.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val useDynamicColors by viewModel.useDynamicColors.collectAsStateWithLifecycle()
    val showMonthlyTaskList by viewModel.showMonthlyTaskList.collectAsStateWithLifecycle()
    val showMonthlyView by viewModel.showMonthlyView.collectAsStateWithLifecycle()
    val showListView by viewModel.showListView.collectAsStateWithLifecycle()
    val startViewMode by viewModel.startViewMode.collectAsStateWithLifecycle()
    val currentToday by viewModel.currentToday.collectAsStateWithLifecycle()
    val lastRolloverInfo by viewModel.lastRolloverInfo.collectAsStateWithLifecycle()
    val rolloverCleanupEnabled by viewModel.rolloverCleanupEnabled.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var isSearchActive by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val json = viewModel.getBackupJsonString()
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray(Charsets.UTF_8))
                    }
                    snackbarHostState.showSnackbar("Backup file saved successfully! 📁")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to export backup: ${e.message}")
                }
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BackupFileReader.readJson(inputStream)
                    }
                    if (json != null) {
                        viewModel.restoreFromJson(json)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to read backup file: ${e.message}")
                }
            }
        }
    }

    // Google's account picker / Drive consent screen, shown when connecting needs the user.
    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDriveConsentResult(result.resultCode, result.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.driveConsentRequests.collect { pendingIntent ->
            driveConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        }
    }

    LaunchedEffect(maintenanceMessage) {
        maintenanceMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMaintenanceMessage()
        }
    }

    LaunchedEffect(driveSyncState.syncMessage) {
        driveSyncState.syncMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSyncMessage()
        }
    }

    // Handle back gestures / system back button
    BackHandler(enabled = isSearchActive && viewMode != ViewMode.SETTINGS) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    PredictiveBackHandler(enabled = viewMode == ViewMode.SETTINGS) { progress ->
        try {
            progress.collect { }
            viewModel.closeSettings()
        } catch (e: CancellationException) {
            // The back gesture was cancelled; keep Settings open.
        }
    }

    BackHandler(enabled = !isSearchActive && viewMode == ViewMode.MONTHLY) {
        viewModel.setViewMode(ViewMode.DAILY)
    }

    BackHandler(enabled = !isSearchActive && viewMode == ViewMode.LIST) {
        viewModel.setViewMode(ViewMode.DAILY)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Column {
                    // Header row: Title + Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (viewMode == ViewMode.SETTINGS) 4.dp else 16.dp,
                                end = 8.dp,
                                top = 4.dp,
                                bottom = 2.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (viewMode == ViewMode.SETTINGS) {
                            IconButton(
                                onClick = { viewModel.closeSettings() },
                                modifier = Modifier
                                    .testTag("back_from_settings_button")
                                    .size(36.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to tasks"
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            text = if (viewMode == ViewMode.SETTINGS) "Settings" else "TaskFlow",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        if (viewMode == ViewMode.DAILY && selectedDate != currentToday) {
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
                        if (viewMode == ViewMode.MONTHLY && (selectedYearMonth.first != currentToday.year || selectedYearMonth.second != currentToday.month)) {
                            IconButton(
                                onClick = { viewModel.jumpToCurrentMonth() },
                                modifier = Modifier.testTag("jump_current_month_top_button")
                            ) {
                                Icon(
                                    Icons.Default.Today,
                                    contentDescription = "Jump to Current Month",
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
                                if (viewMode == ViewMode.SETTINGS) {
                                    viewModel.closeSettings()
                                } else {
                                    viewModel.setViewMode(ViewMode.SETTINGS)
                                }
                            },
                            modifier = Modifier.testTag("settings_top_button")
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (viewMode == ViewMode.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Search field row when active (only in task views)
                    if (viewMode != ViewMode.SETTINGS) {
                        AnimatedVisibility(visible = isSearchActive) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
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
                                    shape = MaterialTheme.shapes.medium
                                )
                            }
                        }

                        // View Mode Tabs: Daily, Monthly, and List views with visibility controlled by user settings
                        val availableTabs = remember(showMonthlyView, showListView) {
                            buildList {
                                add(
                                    Triple(
                                        ViewMode.DAILY,
                                        "Daily",
                                        Icons.AutoMirrored.Filled.FormatListBulleted to "tab_daily_view"
                                    )
                                )
                                if (showMonthlyView) {
                                    add(
                                        Triple(
                                            ViewMode.MONTHLY,
                                            "Monthly",
                                            Icons.Default.CalendarMonth to "tab_monthly_view"
                                        )
                                    )
                                }
                                if (showListView) {
                                    add(
                                        Triple(
                                            ViewMode.LIST,
                                            "List",
                                            Icons.Default.ViewAgenda to "tab_list_view"
                                        )
                                    )
                                }
                            }
                        }

                        if (availableTabs.size > 1) {
                            val selectedTabIndex =
                                availableTabs.indexOfFirst { it.first == viewMode }.coerceAtLeast(0)
                            SecondaryTabRow(
                                selectedTabIndex = selectedTabIndex,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("view_mode_tab_row")
                            ) {
                                availableTabs.forEach { (mode, title, iconAndTag) ->
                                    val (icon, tag) = iconAndTag
                                    Tab(
                                        selected = viewMode == mode,
                                        onClick = { viewModel.setViewMode(mode) },
                                        modifier = Modifier
                                            .testTag(tag)
                                            .height(40.dp),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(title, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    )
                                }
                            }
                        }
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
                    shape = MaterialTheme.shapes.large,
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
            // Each view collects only its own lists, so hidden views stop computing them.
            when (viewMode) {
                ViewMode.DAILY -> {
                    val dailyTasks by viewModel.dailyTasks.collectAsStateWithLifecycle()
                    val dailyStats by viewModel.dailyStats.collectAsStateWithLifecycle()
                    val weekDaysSummary by viewModel.weekDaysSummary.collectAsStateWithLifecycle()
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
                        onDeleteOccurrence = { viewModel.deleteRecurringOccurrence(it) },
                        onToggleSubtask = { task, subtask -> viewModel.toggleSubtask(task, subtask) },
                        onAddSubtask = { task, title -> viewModel.addOccurrenceSubtask(task, title) },
                        onRemoveSubtask = { task, subtask -> viewModel.removeSubtask(task, subtask) },
                        onAddTask = { viewModel.openAddTaskDialog() },
                        showDailyProgress = showDailyProgress,
                        currentToday = currentToday,
                        weekSummaries = weekDaysSummary,
                        subtaskCollapseOverrides = subtaskCollapseOverrides,
                        subtasksCollapsedByDefault = subtasksCollapsedByDefault,
                        onToggleSubtasksCollapsed = { viewModel.toggleSubtasksCollapsed(it) }
                    )
                }

                ViewMode.MONTHLY -> {
                    val monthSummaries by viewModel.monthDaysSummary.collectAsStateWithLifecycle()
                    val monthlySelectedDayTasks by
                        viewModel.monthlySelectedDayTasks.collectAsStateWithLifecycle()
                    MonthlyView(
                        yearMonth = selectedYearMonth,
                        selectedDate = selectedDate,
                        monthSummaries = monthSummaries,
                        selectedDayTasks = monthlySelectedDayTasks,
                        onSelectDate = { viewModel.selectDate(it) },
                        onPrevMonth = { viewModel.prevMonth() },
                        onNextMonth = { viewModel.nextMonth() },
                        onToggleTask = { viewModel.toggleTaskCompletion(it) },
                        onToggleSubtask = { task, subtask -> viewModel.toggleSubtask(task, subtask) },
                        onSwitchToDailyView = { viewModel.setViewMode(ViewMode.DAILY) },
                        currentToday = currentToday,
                        showMonthlyTaskList = showMonthlyTaskList
                    )
                }

                ViewMode.LIST -> {
                    val next7DaysTasks by viewModel.next7DaysTasks.collectAsStateWithLifecycle()
                    ListView(
                        days = next7DaysTasks,
                        onToggleTask = { viewModel.toggleTaskCompletion(it) },
                        onToggleSubtask = { task, subtask -> viewModel.toggleSubtask(task, subtask) }
                    )
                }

                ViewMode.SETTINGS -> {
                    SettingsScreen(
                        categories = categories,
                        onAddCategory = { name, colorHex, iconName ->
                            viewModel.addCategory(name, colorHex, iconName)
                        },
                        onEditCategory = { oldName, newName, colorHex, iconName, isDefault ->
                            viewModel.updateCategory(
                                oldName,
                                newName,
                                colorHex,
                                iconName,
                                isDefault
                            )
                        },
                        onDeleteCategory = { name ->
                            viewModel.deleteCategory(name)
                        },
                        onRunMaintenance = {
                            viewModel.runCleanupAndRollover()
                        },
                        rolloverCleanupEnabled = rolloverCleanupEnabled,
                        onToggleRolloverCleanupEnabled = { viewModel.setRolloverCleanupEnabled(it) },
                        lastRolloverInfo = lastRolloverInfo,
                        driveSyncState = driveSyncState,
                        onConnectDrive = { viewModel.connectGoogleDrive() },
                        onDisconnectDrive = {
                            viewModel.disconnectGoogleDrive()
                        },
                        onBackupToDrive = {
                            viewModel.backupToDrive()
                        },
                        onRestoreFromDrive = {
                            viewModel.restoreFromDrive()
                        },
                        onSetAutoBackup = { enabled ->
                            viewModel.setAutoBackupEnabled(enabled)
                        },
                        onExportJsonBackup = {
                            exportJsonLauncher.launch(generateBackupFileName())
                        },
                        onImportJsonBackup = {
                            importJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        onShareJsonBackup = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val json = viewModel.getBackupJsonString()
                                    val fileName = generateBackupFileName()
                                    if (!GoogleOAuthHelper.shareBackupFile(context, json, fileName)) {
                                        snackbarHostState.showSnackbar("Could not prepare the backup file for sharing")
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Failed to share backup: ${e.message}")
                                }
                            }
                        },
                        showDailyProgress = showDailyProgress,
                        onToggleDailyProgress = { viewModel.setShowDailyProgress(it) },
                        subtasksCollapsedByDefault = subtasksCollapsedByDefault,
                        onToggleSubtasksCollapsedByDefault = { viewModel.setSubtasksCollapsedByDefault(it) },
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { viewModel.setDarkMode(it) },
                        useDynamicColors = useDynamicColors,
                        onToggleDynamicColors = { viewModel.setUseDynamicColors(it) },
                        showMonthlyTaskList = showMonthlyTaskList,
                        onToggleShowMonthlyTaskList = { viewModel.setShowMonthlyTaskList(it) },
                        showMonthlyView = showMonthlyView,
                        onToggleShowMonthlyView = { viewModel.setShowMonthlyView(it) },
                        showListView = showListView,
                        onToggleShowListView = { viewModel.setShowListView(it) },
                        startViewMode = startViewMode,
                        onSelectStartViewMode = { viewModel.setStartViewMode(it) }
                    )
                }
            }
        }
    }

    // Drive already holds a backup and this device has its own data: let the user choose.
    if (driveSyncState.syncConflict && !driveSyncState.isSyncing) {
        var dismissed by remember { mutableStateOf(false) }
        if (!dismissed) {
            AlertDialog(
                onDismissRequest = { dismissed = true },
                title = { Text("Backup found in Google Drive") },
                text = {
                    Text(
                        "This device already has tasks, and your Drive account has a backup. Automatic backup is paused so neither side is overwritten by accident.\n\nChoose which data to keep. You can also decide later in Settings."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resolveSyncConflictKeepDrive() }) {
                        Text("Use Drive backup")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { dismissed = true }) { Text("Decide later") }
                        TextButton(onClick = { viewModel.resolveSyncConflictUseThisDevice() }) {
                            Text("Keep this device")
                        }
                    }
                }
            )
        }
    }

    // Task Add / Edit Modal Bottom Sheet
    if (isAddEditSheetOpen) {
        TaskAddEditSheet(
            initialDate = selectedDate,
            existingTask = editingTask,
            existingSubtasks = editingSubtasks,
            onDismiss = { viewModel.closeAddEditDialog() },
            onSave = { task, subtasks -> viewModel.saveTask(task, subtasks) },
            availableCategories = categories,
            onDelete = { task -> viewModel.deleteTask(task) }
        )
    }
}

private fun generateBackupFileName(): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
    return "taskflow_backup_$timestamp.json"
}

package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
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
import androidx.compose.material3.Surface
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
import com.example.data.drive.GoogleOAuthHelper
import com.example.data.model.AppDate
import com.example.ui.components.GoogleError10Dialog
import com.example.ui.components.TaskAddEditSheet
import com.example.ui.daily.DailyView
import com.example.ui.model.ViewMode
import com.example.ui.monthly.MonthlyView
import com.example.ui.settings.SettingsScreen
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
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
    val dailyTasks by viewModel.dailyTasks.collectAsStateWithLifecycle()
    val monthlySelectedDayTasks by viewModel.monthlySelectedDayTasks.collectAsStateWithLifecycle()
    val dailyStats by viewModel.dailyStats.collectAsStateWithLifecycle()
    val taskFilter by viewModel.taskFilter.collectAsStateWithLifecycle()
    val monthSummaries by viewModel.monthDaysSummary.collectAsStateWithLifecycle()
    val weekDaysSummary by viewModel.weekDaysSummary.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    val isAddEditSheetOpen by viewModel.isAddEditSheetOpen.collectAsStateWithLifecycle()
    val editingTask by viewModel.editingTask.collectAsStateWithLifecycle()
    val maintenanceMessage by viewModel.maintenanceMessage.collectAsStateWithLifecycle()
    val driveSyncState by viewModel.driveSyncState.collectAsStateWithLifecycle()
    val showDailyProgress by viewModel.showDailyProgress.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val useDynamicColors by viewModel.useDynamicColors.collectAsStateWithLifecycle()
    val hideMonthlyTaskList by viewModel.hideMonthlyTaskList.collectAsStateWithLifecycle()
    val currentToday by viewModel.currentToday.collectAsStateWithLifecycle()
    val lastRolloverInfo by viewModel.lastRolloverInfo.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var isSearchActive by remember { mutableStateOf(false) }

    val showGoogleError10Dialog by viewModel.showGoogleError10Dialog.collectAsStateWithLifecycle()
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
                    val json = context.contentResolver.openInputStream(uri)?.use { isStream ->
                        isStream.bufferedReader().use { it.readText() }
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

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                viewModel.onGoogleSignInSuccess(account)
            } else {
                viewModel.refreshDriveState("Google Sign-In was not completed", isError = true)
            }
        } catch (e: Exception) {
            val statusCode = (e as? ApiException)?.statusCode
            if (statusCode == 10 || statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
                viewModel.setShowGoogleError10Dialog(true)
                viewModel.refreshDriveState("Google Sign-In Error 10: OAuth Client ID setup required", isError = true)
            } else {
                viewModel.refreshDriveState("Sign-In error: ${e.message}", isError = true)
            }
        }
    }

    if (showGoogleError10Dialog) {
        GoogleError10Dialog(
            onDismiss = { viewModel.setShowGoogleError10Dialog(false) }
        )
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
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    BackHandler(enabled = !isSearchActive && viewMode == ViewMode.SETTINGS) {
        viewModel.setViewMode(ViewMode.DAILY)
    }

    BackHandler(enabled = !isSearchActive && viewMode == ViewMode.MONTHLY) {
        viewModel.setViewMode(ViewMode.DAILY)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Column {
                    // Header row: Title + Actions (with minimized padding)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (viewMode == ViewMode.SETTINGS) 4.dp else 16.dp,
                                end = 8.dp,
                                top = 2.dp,
                                bottom = 0.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (viewMode == ViewMode.SETTINGS) {
                            IconButton(
                                onClick = { viewModel.setViewMode(ViewMode.DAILY) },
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
                                modifier = Modifier
                                    .testTag("jump_today_top_button")
                                    .size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Today,
                                    contentDescription = "Jump to Today",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (viewMode == ViewMode.MONTHLY && (selectedYearMonth.first != currentToday.year || selectedYearMonth.second != currentToday.month)) {
                            IconButton(
                                onClick = { viewModel.jumpToCurrentMonth() },
                                modifier = Modifier
                                    .testTag("jump_current_month_top_button")
                                    .size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Today,
                                    contentDescription = "Jump to Current Month",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (viewMode != ViewMode.SETTINGS) {
                            IconButton(
                                onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) viewModel.setSearchQuery("")
                                },
                                modifier = Modifier
                                    .testTag("search_toggle_button")
                                    .size(36.dp)
                            ) {
                                Icon(
                                    if (isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.setViewMode(
                                    if (viewMode == ViewMode.SETTINGS) ViewMode.DAILY else ViewMode.SETTINGS
                                )
                            },
                            modifier = Modifier
                                .testTag("settings_top_button")
                                .size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (viewMode == ViewMode.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
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
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        // View Mode Tabs: Daily View vs Monthly Calendar with reduced padding
                        TabRow(
                            selectedTabIndex = if (viewMode == ViewMode.MONTHLY) 1 else 0,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("view_mode_tab_row")
                        ) {
                            Tab(
                                selected = viewMode == ViewMode.DAILY,
                                onClick = { viewModel.setViewMode(ViewMode.DAILY) },
                                modifier = Modifier
                                    .testTag("tab_daily_view")
                                    .height(36.dp),
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.FormatListBulleted,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Daily", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            )
                            Tab(
                                selected = viewMode == ViewMode.MONTHLY,
                                onClick = { viewModel.setViewMode(ViewMode.MONTHLY) },
                                modifier = Modifier
                                    .testTag("tab_monthly_view")
                                    .height(36.dp),
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Monthly", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            )
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
                        onAddTask = { viewModel.openAddTaskDialog() },
                        showDailyProgress = showDailyProgress,
                        currentToday = currentToday,
                        weekSummaries = weekDaysSummary
                    )
                }
                ViewMode.MONTHLY -> {
                    MonthlyView(
                        yearMonth = selectedYearMonth,
                        selectedDate = selectedDate,
                        monthSummaries = monthSummaries,
                        selectedDayTasks = monthlySelectedDayTasks,
                        onSelectDate = { viewModel.selectDate(it) },
                        onPrevMonth = { viewModel.prevMonth() },
                        onNextMonth = { viewModel.nextMonth() },
                        onJumpToCurrentMonth = { viewModel.jumpToCurrentMonth() },
                        onToggleTask = { viewModel.toggleTaskCompletion(it) },
                        onEditTask = { viewModel.openEditTaskDialog(it) },
                        onDeleteTask = { viewModel.deleteTask(it) },
                        onSwitchToDailyView = { viewModel.setViewMode(ViewMode.DAILY) },
                        onAddTaskForDay = { viewModel.openAddTaskDialog() },
                        currentToday = currentToday,
                        hideMonthlyTaskList = hideMonthlyTaskList
                    )
                }
                ViewMode.SETTINGS -> {
                    SettingsScreen(
                        categories = categories,
                        onBack = { viewModel.setViewMode(ViewMode.DAILY) },
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
                        },
                        lastRolloverInfo = lastRolloverInfo,
                        driveSyncState = driveSyncState,
                        onConnectDrive = {
                            googleSignInLauncher.launch(
                                viewModel.driveBackupManager.getGoogleSignInClient().signInIntent
                            )
                        },
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
                        onShowError10Info = {
                            viewModel.setShowGoogleError10Dialog(true)
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
                                    GoogleOAuthHelper.shareBackupFile(context, json, fileName)
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Failed to share backup: ${e.message}")
                                }
                            }
                        },
                        showDailyProgress = showDailyProgress,
                        onToggleDailyProgress = { viewModel.setShowDailyProgress(it) },
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { viewModel.setDarkMode(it) },
                        useDynamicColors = useDynamicColors,
                        onToggleDynamicColors = { viewModel.setUseDynamicColors(it) },
                        hideMonthlyTaskList = hideMonthlyTaskList,
                        onToggleHideMonthlyTaskList = { viewModel.setHideMonthlyTaskList(it) }
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

private fun generateBackupFileName(): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
    return "taskflow_backup_$timestamp.json"
}

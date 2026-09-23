package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AppDate
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskEntity
import com.example.ui.model.CategoryIcons
import java.util.Calendar

data class CategoryOption(
    val name: String,
    val icon: ImageVector,
    val color: Long
)

val CATEGORIES = listOf(
    CategoryOption("General", Icons.Default.CalendarMonth, 0xFF3B82F6),
    CategoryOption("Work", Icons.Default.Work, 0xFF2563EB),
    CategoryOption("Personal", Icons.Default.Person, 0xFF8B5CF6),
    CategoryOption("Health", Icons.Default.Spa, 0xFF10B981),
    CategoryOption("Fitness", Icons.Default.FitnessCenter, 0xFFF59E0B),
    CategoryOption("Home", Icons.Default.Home, 0xFF059669),
    CategoryOption("Study", Icons.Default.School, 0xFF6366F1)
)

val RECURRENCE_PRESETS = listOf(
    1 to "Every Day",
    2 to "Every 2 Days",
    3 to "Every 3 Days",
    7 to "Weekly (7d)",
    14 to "Every 2 Wks",
    30 to "Monthly (30d)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskAddEditSheet(
    initialDate: AppDate,
    existingTask: TaskEntity?,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit,
    availableCategories: List<CategoryEntity> = emptyList(),
    onDelete: ((TaskEntity) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Derived category options from availableCategories or fallback
    val categoryOptions = remember(availableCategories) {
        if (availableCategories.isNotEmpty()) {
            availableCategories.map {
                CategoryOption(
                    name = it.name,
                    icon = CategoryIcons.getIcon(it.iconName),
                    color = it.colorHex
                )
            }
        } else {
            CATEGORIES
        }
    }

    var title by remember { mutableStateOf(existingTask?.title ?: "") }
    var description by remember { mutableStateOf(existingTask?.description ?: "") }
    var selectedCategory by remember { mutableStateOf(existingTask?.category ?: "General") }
    var selectedColor by remember {
        mutableStateOf(
            existingTask?.colorHex ?: categoryOptions.firstOrNull { it.name == selectedCategory }?.color ?: 0xFF3B82F6
        )
    }
    var priority by remember { mutableStateOf(existingTask?.priority ?: "Medium") }

    var isRecurring by remember { mutableStateOf(existingTask?.isRecurring ?: false) }
    var recurrenceDays by remember { mutableIntStateOf(existingTask?.recurrenceDays ?: 1) }
    var customDaysText by remember {
        mutableStateOf(
            if (existingTask != null && RECURRENCE_PRESETS.none { it.first == existingTask.recurrenceDays }) {
                existingTask.recurrenceDays.toString()
            } else ""
        )
    }

    var startDate by remember {
        mutableStateOf(
            existingTask?.let {
                try { AppDate.parseIso(it.startDate) } catch (_: Exception) { initialDate }
            } ?: initialDate
        )
    }

    var hasEndDate by remember { mutableStateOf(existingTask?.endDate != null) }
    var endDate by remember {
        mutableStateOf(
            existingTask?.endDate?.let {
                try { AppDate.parseIso(it) } catch (_: Exception) { initialDate.plusDays(30) }
            } ?: initialDate.plusDays(30)
        )
    }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val categoryObj = CATEGORIES.firstOrNull { it.name == selectedCategory } ?: CATEGORIES[0]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("task_add_edit_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (existingTask == null) "New Task" else "Edit Task",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Title input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Task Title *") },
                placeholder = { Text("e.g. Morning Walk, Water Plants, Report") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("task_title_input"),
                shape = RoundedCornerShape(12.dp)
            )

            // Description input
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Notes / Description (Optional)") },
                placeholder = { Text("Additional details, steps, or reminders") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("task_description_input"),
                shape = RoundedCornerShape(12.dp)
            )

            // Task Type: Simple vs Recurring
            Text(
                text = "Task Schedule Type",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isRecurring,
                    onClick = { isRecurring = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {}
                ) {
                    Text("Simple (One-time)")
                }
                SegmentedButton(
                    selected = isRecurring,
                    onClick = { isRecurring = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {}
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Recurring")
                    }
                }
            }

            // Date configuration based on type
            if (!isRecurring) {
                // One-time task date selector
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showStartDatePicker = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Scheduled Date",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${AppDate.dayOfWeekName(startDate.dayOfWeek())}, ${startDate.formatEuropean(includeYear = true)}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = "Pick Date",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                // Recurring task interval configuration
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Recurrence Interval",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Quick presets chips
                        Text(
                            text = "Repeat every:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RECURRENCE_PRESETS.forEach { (days, label) ->
                                val selected = recurrenceDays == days && customDaysText.isEmpty()
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        recurrenceDays = days
                                        customDaysText = ""
                                    },
                                    label = { Text(label) },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }

                        // Custom n days input
                        OutlinedTextField(
                            value = customDaysText,
                            onValueChange = { input ->
                                val cleaned = input.filter { it.isDigit() }
                                customDaysText = cleaned
                                cleaned.toIntOrNull()?.let { days ->
                                    if (days > 0) recurrenceDays = days
                                }
                            },
                            label = { Text("Or custom interval (every N days)") },
                            placeholder = { Text("e.g. 4, 5, 10") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Text(
                            text = "⚡ Will occur every $recurrenceDays day${if (recurrenceDays > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        // Start date for recurring
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                            .clickable { showStartDatePicker = true }
                            .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Start Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = startDate.formatEuropean(includeYear = true),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = "Pick Start Date",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Optional End date toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Set End Date",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (hasEndDate) "Repeats until set date" else "Repeats indefinitely",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = hasEndDate,
                                onCheckedChange = { hasEndDate = it }
                            )
                        }

                        if (hasEndDate) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                .clickable { showEndDatePicker = true }
                                .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "End Date",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = endDate.formatEuropean(includeYear = true),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Icon(
                                    Icons.Default.CalendarMonth,
                                    contentDescription = "Pick End Date",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Category Selection
            Text(
                text = "Category",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categoryOptions.forEach { cat ->
                    val selected = selectedCategory == cat.name
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedCategory = cat.name
                            selectedColor = cat.color
                        },
                        label = { Text(cat.name) },
                        leadingIcon = {
                            Icon(
                                cat.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selected) MaterialTheme.colorScheme.primary else Color(cat.color)
                            )
                        }
                    )
                }
            }

            // Priority Selection
            Text(
                text = "Priority",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Low", "Medium", "High").forEachIndexed { index, p ->
                    SegmentedButton(
                        selected = priority == p,
                        onClick = { priority = p },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        icon = {}
                    ) {
                        Text(p)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (existingTask != null && onDelete != null) {
                    OutlinedButton(
                        onClick = { onDelete(existingTask) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delete_task_button")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete")
                    }
                }

                Button(
                    onClick = {
                        val taskToSave = (existingTask ?: TaskEntity(
                            title = title.trim(),
                            startDate = startDate.toIsoString()
                        )).copy(
                            title = title.trim(),
                            description = description.trim(),
                            category = selectedCategory,
                            priority = priority,
                            colorHex = selectedColor,
                            isRecurring = isRecurring,
                            recurrenceDays = if (isRecurring) recurrenceDays.coerceAtLeast(1) else 1,
                            startDate = startDate.toIsoString(),
                            endDate = if (isRecurring && hasEndDate) endDate.toIsoString() else null
                        )
                        onSave(taskToSave)
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier
                        .weight(2f)
                        .testTag("save_task_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (existingTask == null) "Create Task" else "Save Changes",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Material 3 Date Picker Dialogs
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = calendarMillisFromAppDate(startDate)
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            startDate = appDateFromMillis(millis)
                        }
                        showStartDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = calendarMillisFromAppDate(endDate)
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            endDate = appDateFromMillis(millis)
                        }
                        showEndDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun calendarMillisFromAppDate(date: AppDate): Long {
    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(date.year, date.month - 1, date.day, 12, 0, 0)
    return cal.timeInMillis
}

private fun appDateFromMillis(millis: Long): AppDate {
    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = millis
    return AppDate(
        year = cal.get(Calendar.YEAR),
        month = cal.get(Calendar.MONTH) + 1,
        day = cal.get(Calendar.DAY_OF_MONTH)
    )
}

package com.example.ui.monthly

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppDate
import com.example.data.model.TaskEntity
import com.example.ui.daily.TaskCardItem
import com.example.ui.model.DaySummaryUi
import com.example.ui.model.TaskItemUi
import com.example.ui.theme.CompletedGreen

@Composable
fun MonthlyView(
    yearMonth: Pair<Int, Int>,
    selectedDate: AppDate,
    monthSummaries: Map<String, DaySummaryUi>,
    selectedDayTasks: List<TaskItemUi>,
    onSelectDate: (AppDate) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onJumpToCurrentMonth: () -> Unit = {},
    onToggleTask: (TaskItemUi) -> Unit,
    onEditTask: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onSwitchToDailyView: () -> Unit,
    onAddTaskForDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (year, month) = yearMonth
    val today = remember { AppDate.today() }

    // Calculate calendar grid days: starting from Monday before 1st of month
    val calendarDays = remember(year, month) {
        val firstDay = AppDate(year, month, 1)
        val firstDow = firstDay.dayOfWeek() // 1 = Mon, 7 = Sun
        val leadingDaysCount = firstDow - 1

        val days = mutableListOf<AppDate>()
        // Add leading days from previous month
        for (i in leadingDaysCount downTo 1) {
            days.add(firstDay.minusDays(i.toLong()))
        }

        // Add days of current month
        val daysInMonth = AppDate.daysInMonth(year, month)
        for (d in 1..daysInMonth) {
            days.add(AppDate(year, month, d))
        }

        // Add trailing days to complete full 7-day rows (up to 35 or 42 cells)
        val totalCells = if (days.size <= 35) 35 else 42
        var trailing = 1
        while (days.size < totalCells) {
            val lastDay = AppDate(year, month, daysInMonth)
            days.add(lastDay.plusDays(trailing.toLong()))
            trailing++
        }
        days
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("monthly_view_container"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Month Navigation Header Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onPrevMonth,
                            modifier = Modifier.testTag("prev_month_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous Month"
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${AppDate.monthName(month)} $year",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = onNextMonth,
                            modifier = Modifier.testTag("next_month_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next Month"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Days of week header (Mon - Sun)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { dow ->
                            Text(
                                text = dow,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Calendar Grid (rows of 7 days)
                    val rows = calendarDays.chunked(7)
                    rows.forEach { week ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            week.forEach { date ->
                                val isCurrentMonthDay = date.month == month
                                val isToday = date == today
                                val isSelected = date == selectedDate
                                val summary = monthSummaries[date.toIsoString()]

                                CalendarDayCell(
                                    date = date,
                                    isCurrentMonth = isCurrentMonthDay,
                                    isToday = isToday,
                                    isSelected = isSelected,
                                    summary = summary,
                                    onClick = { onSelectDate(date) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Legend highlighting explanation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Blue dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pending tasks",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(16.dp))

                        // Green check
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(CompletedGreen)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "All completed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Selected Day Details Header & Task List
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Selected Day Overview",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${AppDate.dayOfWeekShort(selectedDate.dayOfWeek())}, ${selectedDate.day} ${AppDate.monthName(selectedDate.month)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onSwitchToDailyView,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("open_daily_view_button")
                            ) {
                                Icon(
                                    Icons.Default.EditCalendar,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open Day", fontSize = 12.sp)
                            }

                            Button(
                                onClick = onAddTaskForDay,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("monthly_add_task_button")
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Preview of tasks on the selected day
        if (selectedDayTasks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No tasks for ${selectedDate.day} ${AppDate.monthName(selectedDate.month)}. Tap + Add to schedule a task or recurring routine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(selectedDayTasks, key = { "${it.task.id}_monthly_${it.date.toIsoString()}" }) { taskItem ->
                TaskCardItem(
                    taskItem = taskItem,
                    onToggle = { onToggleTask(taskItem) },
                    onEdit = { onEditTask(taskItem.task) },
                    onDelete = { onDeleteTask(taskItem.task) }
                )
            }
        }
    }
}

@Composable
fun CalendarDayCell(
    date: AppDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    summary: DaySummaryUi?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTasks = summary?.hasTasks == true
    val allCompleted = summary?.allCompleted == true

    // Cell background styling
    val cellBackground = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        hasTasks && isCurrentMonth -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    val cellBorder = when {
        isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        isToday -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary)
        hasTasks -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        else -> null
    }

    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        isToday -> MaterialTheme.colorScheme.tertiary
        isCurrentMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }

    val cellShape = RoundedCornerShape(8.dp)
    var cellModifier = modifier
        .padding(1.5.dp)
        .aspectRatio(0.95f)
        .clip(cellShape)
        .background(cellBackground)

    if (cellBorder != null) {
        cellModifier = cellModifier.border(cellBorder, cellShape)
    }

    Box(
        modifier = cellModifier
            .clickable { onClick() }
            .testTag("calendar_day_${date.toIsoString()}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected || isToday || hasTasks) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )

            // Highlighting days with something on!
            if (hasTasks) {
                Spacer(modifier = Modifier.height(2.dp))
                if (allCompleted) {
                    // Small green check badge
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(CompletedGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(8.dp)
                        )
                    }
                } else {
                    // Task Count Pill or dot
                    val count = summary.totalTasks
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = count.toString(),
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            } else {
                // Keep layout balanced
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

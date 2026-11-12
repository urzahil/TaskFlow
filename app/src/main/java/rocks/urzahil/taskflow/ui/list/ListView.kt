package rocks.urzahil.taskflow.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.ui.components.CompactTaskRow
import rocks.urzahil.taskflow.ui.model.DayTasksGroupUi
import rocks.urzahil.taskflow.ui.model.SubtaskItemUi
import rocks.urzahil.taskflow.ui.model.TaskItemUi

@Composable
fun ListView(
    days: List<DayTasksGroupUi>,
    onToggleTask: (TaskItemUi) -> Unit,
    onToggleSubtask: (TaskItemUi, SubtaskItemUi) -> Unit = { _, _ -> },
    hideCompleted: Boolean = false,
    subtaskCollapseOverrides: Set<Long> = emptySet(),
    subtasksCollapsedByDefault: Boolean = false,
    onToggleSubtasksCollapsed: (Long) -> Unit = {},
    collapsibleDays: Boolean = false,
    collapsedDays: Set<AppDate> = emptySet(),
    onToggleDayCollapsed: (AppDate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val daysWithTasks = remember(days, hideCompleted) {
        val visibleDays = if (hideCompleted) {
            // Completed tasks and checked subtasks drop out as soon as they are completed.
            days.map { day ->
                day.copy(
                    tasks = day.tasks
                        .filterNot { it.isCompleted }
                        .map { task -> task.copy(subtasks = task.subtasks.filterNot { it.isChecked }) }
                )
            }
        } else {
            days
        }
        visibleDays.filter { it.tasks.isNotEmpty() }
    }
    val allCompletedHidden = hideCompleted && days.any { it.tasks.isNotEmpty() }

    if (daysWithTasks.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp)
                .testTag("list_view_empty_state"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (allCompletedHidden) "All caught up" else "No upcoming tasks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (allCompletedHidden) {
                        "Every task for the next 7 days is completed."
                    } else {
                        "No tasks scheduled for the next 7 days."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("list_view_container"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                daysWithTasks,
                key = { _, dayGroup -> dayGroup.date.toIsoString() }) { index, dayGroup ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("list_day_group_$index")
                ) {
                    val collapsed = collapsibleDays && dayGroup.date in collapsedDays

                    // Day name header: Only day name, no date. Tappable when days are collapsible;
                    // a chevron at the end marks a collapsed day.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("list_day_header_$index")
                            .then(
                                if (collapsibleDays) {
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = if (collapsed) "Show tasks" else "Hide tasks"
                                        ) { onToggleDayCollapsed(dayGroup.date) }
                                } else {
                                    Modifier
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dayGroup.dayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
                        )
                        if (collapsed) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapsed",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(20.dp)
                                    .testTag("list_day_chevron_$index")
                            )
                        }
                    }

                    // Tasks for this day, indented with respect to the day name
                    AnimatedVisibility(visible = !collapsed) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            dayGroup.tasks.forEach { taskItem ->
                                CompactTaskRow(
                                    taskItem = taskItem,
                                    onToggle = { onToggleTask(taskItem) },
                                    tagPrefix = "list",
                                    onToggleSubtask = { onToggleSubtask(taskItem, it) },
                                    isSubtasksCollapsed =
                                        (taskItem.task.id in subtaskCollapseOverrides) != subtasksCollapsedByDefault,
                                    onToggleSubtasksCollapsed = { onToggleSubtasksCollapsed(taskItem.task.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package rocks.urzahil.taskflow.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.urzahil.taskflow.ui.components.CompactTaskRow
import rocks.urzahil.taskflow.ui.model.DayTasksGroupUi
import rocks.urzahil.taskflow.ui.model.SubtaskItemUi
import rocks.urzahil.taskflow.ui.model.TaskItemUi

@Composable
fun ListView(
    days: List<DayTasksGroupUi>,
    onToggleTask: (TaskItemUi) -> Unit,
    onToggleSubtask: (TaskItemUi, SubtaskItemUi) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val daysWithTasks = remember(days) { days.filter { it.tasks.isNotEmpty() } }

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
                    text = "No upcoming tasks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "No tasks scheduled for the next 7 days.",
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
                    // Day name header: Only day name, no date
                    Text(
                        text = dayGroup.dayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
                    )

                    // Tasks for this day, indented with respect to the day name
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
                                onToggleSubtask = { onToggleSubtask(taskItem, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

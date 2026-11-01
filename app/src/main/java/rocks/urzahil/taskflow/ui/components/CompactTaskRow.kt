package rocks.urzahil.taskflow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rocks.urzahil.taskflow.ui.model.SubtaskItemUi
import rocks.urzahil.taskflow.ui.model.TaskItemUi

/**
 * One-line task row with a completion toggle, used by the Monthly and List views.
 * Test tags are "<tagPrefix>_task_item_<id>", "toggle_<tagPrefix>_task_<id>" and, when the task
 * has subtasks, "<tagPrefix>_subtask_toggle_<id>" for the collapse chevron.
 */
@Composable
fun CompactTaskRow(
    taskItem: TaskItemUi,
    onToggle: () -> Unit,
    tagPrefix: String,
    onToggleSubtask: (SubtaskItemUi) -> Unit = {},
    isSubtasksCollapsed: Boolean = false,
    onToggleSubtasksCollapsed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${tagPrefix}_task_item_${taskItem.task.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (taskItem.isCompleted) {
                MaterialTheme.colorScheme.surfaceContainerLowest
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(26.dp)
                    .testTag("toggle_${tagPrefix}_task_${taskItem.task.id}")
            ) {
                if (taskItem.isCompleted) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                } else {
                    Icon(
                        Icons.Default.RadioButtonUnchecked,
                        contentDescription = "Mark Complete",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = taskItem.task.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                fontWeight = if (taskItem.isCompleted) FontWeight.Normal else FontWeight.Medium,
                textDecoration = if (taskItem.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                color = if (taskItem.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (taskItem.subtasks.isNotEmpty()) {
                // Collapse/expand chevron: points up while expanded, down while collapsed
                val chevronRotation by animateFloatAsState(
                    targetValue = if (isSubtasksCollapsed) 180f else 0f,
                    label = "compact_subtask_chevron"
                )
                IconButton(
                    onClick = onToggleSubtasksCollapsed,
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("${tagPrefix}_subtask_toggle_${taskItem.task.id}")
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = if (isSubtasksCollapsed) "Show subtasks" else "Hide subtasks",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !isSubtasksCollapsed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                taskItem.subtasks.forEach { subtask ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleSubtask(subtask) }
                            .padding(start = 40.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (subtask.isChecked) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Completed",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.RadioButtonUnchecked,
                                contentDescription = "Mark Complete",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = subtask.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            textDecoration = if (subtask.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (subtask.isChecked) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

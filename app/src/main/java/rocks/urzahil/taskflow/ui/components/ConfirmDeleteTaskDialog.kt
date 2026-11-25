package rocks.urzahil.taskflow.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import rocks.urzahil.taskflow.data.model.TaskEntity

/** Deleting a task also removes its subtasks and completion history, so ask first. */
@Composable
fun ConfirmDeleteTaskDialog(
    task: TaskEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("confirm_delete_task_dialog"),
        title = { Text("Delete task?") },
        text = {
            Text(
                if (task.isRecurring) {
                    "This permanently deletes \"${task.title}\" and every occurrence, including its history and subtasks. To remove a single day, delete just that occurrence instead."
                } else {
                    "This permanently deletes \"${task.title}\" and its subtasks."
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_delete_task_button")
            ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

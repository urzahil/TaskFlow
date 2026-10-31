package rocks.urzahil.taskflow.ui.model

import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity

data class TaskItemUi(
    val task: TaskEntity,
    val date: AppDate,
    val isCompleted: Boolean,
    val subtasks: List<SubtaskItemUi> = emptyList()
)

data class SubtaskItemUi(
    val id: Long,
    val title: String,
    val isChecked: Boolean,
    val shared: Boolean // true = from the task's shared list, false = added for one date
)

data class DaySummaryUi(
    val date: AppDate,
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    val taskColors: List<Long> = emptyList()
) {
    val pendingTasks: Int get() = (totalTasks - completedTasks).coerceAtLeast(0)
    val hasTasks: Boolean get() = totalTasks > 0
    val allCompleted: Boolean get() = hasTasks && completedTasks >= totalTasks
}

enum class TaskFilter(val label: String) {
    ALL("All"),
    PENDING("Pending"),
    COMPLETED("Completed")
}

enum class ViewMode(val title: String) {
    DAILY("Daily View"),
    MONTHLY("Monthly Calendar"),
    LIST("List View"),
    SETTINGS("Settings")
}

data class DayTasksGroupUi(
    val date: AppDate,
    val dayName: String,
    val tasks: List<TaskItemUi>
)

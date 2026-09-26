package com.example.ui.model

import com.example.data.model.AppDate
import com.example.data.model.TaskEntity

data class TaskItemUi(
    val task: TaskEntity,
    val date: AppDate,
    val isCompleted: Boolean
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
    SETTINGS("Settings")
}

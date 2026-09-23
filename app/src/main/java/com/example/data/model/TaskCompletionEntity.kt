package com.example.data.model

import androidx.room.Entity

@Entity(
    tableName = "task_completions",
    primaryKeys = ["taskId", "date"]
)
data class TaskCompletionEntity(
    val taskId: Long,
    val date: String, // ISO YYYY-MM-DD
    val completedAt: Long = System.currentTimeMillis()
)

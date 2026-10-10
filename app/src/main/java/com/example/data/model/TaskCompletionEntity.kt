package com.example.data.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "task_completions",
    primaryKeys = ["taskId", "date"],
    indices = [
        Index(value = ["date"], name = "index_task_completions_date")
    ]
)
data class TaskCompletionEntity(
    val taskId: Long,
    val date: String, // ISO YYYY-MM-DD
    val completedAt: Long = System.currentTimeMillis()
)


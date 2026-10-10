package com.example.data.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "task_occurrence_exclusions",
    primaryKeys = ["taskId", "date"],
    indices = [
        Index(value = ["date"], name = "index_task_occurrence_exclusions_date")
    ]
)
data class TaskOccurrenceExclusionEntity(
    val taskId: Long,
    val date: String
)

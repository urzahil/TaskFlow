package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val category: String = "General",
    val priority: String = "Medium",
    val colorHex: Long = 0xFF3B82F6,
    val isRecurring: Boolean = false,
    val recurrenceDays: Int = 1, // repeats every n days
    val startDate: String, // ISO YYYY-MM-DD
    val endDate: String? = null, // Optional ISO YYYY-MM-DD
    val createdAt: Long = System.currentTimeMillis()
)

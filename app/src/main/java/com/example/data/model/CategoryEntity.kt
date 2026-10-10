package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    val name: String,
    val colorHex: Long = 0xFF3B82F6,
    val iconName: String = "general",
    val isDefault: Boolean = false
)

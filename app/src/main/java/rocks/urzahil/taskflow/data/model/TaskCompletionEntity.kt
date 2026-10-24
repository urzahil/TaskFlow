package rocks.urzahil.taskflow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "task_completions",
    primaryKeys = ["taskId", "date"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["date"], name = "index_task_completions_date")
    ]
)
data class TaskCompletionEntity(
    val taskId: Long,
    val date: String, // ISO YYYY-MM-DD
    val completedAt: Long = System.currentTimeMillis()
)

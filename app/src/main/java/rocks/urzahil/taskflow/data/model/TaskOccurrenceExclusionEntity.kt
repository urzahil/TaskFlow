package rocks.urzahil.taskflow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "task_occurrence_exclusions",
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
        Index(value = ["date"], name = "index_task_occurrence_exclusions_date")
    ]
)
data class TaskOccurrenceExclusionEntity(
    val taskId: Long,
    val date: String
)

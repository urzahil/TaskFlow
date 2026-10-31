package rocks.urzahil.taskflow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "subtask_completions",
    primaryKeys = ["subtaskId", "date"],
    foreignKeys = [
        ForeignKey(
            entity = SubtaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["subtaskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SubtaskCompletionEntity(
    val subtaskId: Long,
    val date: String, // ISO YYYY-MM-DD
    val completedAt: Long = System.currentTimeMillis()
)

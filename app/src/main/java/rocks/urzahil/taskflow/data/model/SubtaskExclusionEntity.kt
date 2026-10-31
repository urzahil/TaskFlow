package rocks.urzahil.taskflow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "subtask_exclusions",
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
data class SubtaskExclusionEntity(
    val subtaskId: Long,
    val date: String // ISO YYYY-MM-DD; the shared subtask is hidden on this date
)

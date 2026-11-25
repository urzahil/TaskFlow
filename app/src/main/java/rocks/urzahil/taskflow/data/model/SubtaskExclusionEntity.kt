package rocks.urzahil.taskflow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

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
    ],
    // Lets the daily cleanup prune past dates without scanning the whole table.
    indices = [Index(value = ["date"], name = "index_subtask_exclusions_date")]
)
data class SubtaskExclusionEntity(
    val subtaskId: Long,
    val date: String // ISO YYYY-MM-DD; the shared subtask is hidden on this date
)

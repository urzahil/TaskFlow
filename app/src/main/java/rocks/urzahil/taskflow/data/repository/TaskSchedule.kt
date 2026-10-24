package rocks.urzahil.taskflow.data.repository

import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity

/**
 * A task's recurrence rules parsed once, so callers evaluating many dates (month grids,
 * week strips, 7-day lists) do not re-parse ISO strings per date.
 *
 * This is the single source of truth for "is this task on this date"; the scheduler,
 * repository and view model all go through it.
 */
class TaskSchedule private constructor(
    val taskId: Long,
    val colorHex: Long,
    val isRecurring: Boolean,
    private val start: AppDate,
    private val end: AppDate?,
    private val interval: Int,
    private val weekdays: Set<Int>
) {
    fun isScheduledOn(date: AppDate): Boolean {
        if (!isRecurring) return date == start
        if (date < start) return false
        if (end != null && date > end) return false
        if (weekdays.isNotEmpty()) return date.dayOfWeek() in weekdays
        return date.daysBetween(start) % interval == 0L
    }

    companion object {
        /** Returns null when the task's start date is unparseable (such a task is never shown). */
        fun from(task: TaskEntity): TaskSchedule? {
            val start = try {
                AppDate.parseIso(task.startDate)
            } catch (_: Exception) {
                return null
            }
            val end = task.endDate?.takeIf { it.isNotBlank() }?.let {
                try {
                    AppDate.parseIso(it)
                } catch (_: Exception) {
                    null
                }
            }
            return TaskSchedule(
                taskId = task.id,
                colorHex = task.colorHex,
                isRecurring = task.isRecurring,
                start = start,
                end = end,
                interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1,
                weekdays = task.parsedDaysOfWeek()
            )
        }
    }
}

/** Fast membership lookup for individually deleted recurring occurrences. */
class OccurrenceExclusions(exclusions: List<TaskOccurrenceExclusionEntity>) {
    private val keys: Set<String> = exclusions.mapTo(HashSet()) { key(it.taskId, it.date) }

    fun contains(taskId: Long, dateIso: String): Boolean = key(taskId, dateIso) in keys

    private fun key(taskId: Long, dateIso: String) = "${taskId}_$dateIso"
}

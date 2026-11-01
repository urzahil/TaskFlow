package rocks.urzahil.taskflow.data.repository

import rocks.urzahil.taskflow.data.db.TaskDao
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity

object TaskScheduler {

    suspend fun cleanupAndRolloverTasks(
        taskDao: TaskDao,
        today: AppDate = AppDate.today()
    ): TaskRepository.RolloverResult {
        val todayIso = today.toIsoString()
        val completedNonRecurringTasks = taskDao.getCompletedNonRecurringTasks()
        val completedNonRecurringIds = completedNonRecurringTasks.map { it.id }.toSet()
        val pastTasks = taskDao.getPastNonRecurringTasks(todayIso)
            .filterNot { it.id in completedNonRecurringIds }
        val completionKeys = taskDao.getAllCompletionsList().mapTo(HashSet()) { key(it.taskId, it.date) }
        val exclusionKeys =
            taskDao.getAllOccurrenceExclusionsList().mapTo(HashSet()) { key(it.taskId, it.date) }

        var cleanedCount = 0
        var movedCount = 0
        var cleanedRecurringOccurrences = 0

        val tasksToDelete = mutableListOf<TaskEntity>()
        val tasksToUpdate = mutableListOf<TaskEntity>()

        // A completed non-recurring task is finished regardless of its scheduled date.
        for (task in completedNonRecurringTasks) {
            tasksToDelete.add(task)
            cleanedCount++
        }

        for (task in pastTasks) {
            tasksToUpdate.add(task.copy(startDate = todayIso))
            movedCount++
        }

        val recurringTasks = taskDao.getAllRecurringTasks()
        for (task in recurringTasks) {
            val schedule = TaskSchedule.from(task) ?: continue
            val start = schedule.start
            val end = schedule.end
            val weekdays = schedule.weekdays

            val lastPastDate = minOf(today.minusDays(1), end ?: today.minusDays(1))
            if (start <= lastPastDate) {
                val span = lastPastDate.daysBetween(start) + 1
                val count = if (schedule.isMonthly) {
                    (0L until span).count { schedule.isScheduledOn(start.plusDays(it)) }.toLong()
                } else if (weekdays.isEmpty()) {
                    (span - 1) / schedule.interval + 1
                } else {
                    (span / 7) * weekdays.size + (0L until span % 7).count {
                        start.plusDays(it).dayOfWeek() in weekdays
                    }
                }
                val excludedPastOccurrences = (0L until span).count { offset ->
                    key(task.id, start.plusDays(offset).toIsoString()) in exclusionKeys
                }
                cleanedRecurringOccurrences =
                    (cleanedRecurringOccurrences.toLong() + (count - excludedPastOccurrences).coerceAtLeast(0))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }

            if (end != null && end < today) {
                tasksToDelete.add(task)
                cleanedCount++
                continue
            }

            if (start < today) {
                val nextOccurrence: AppDate? = if (schedule.isMonthly) {
                    // Null when the end date comes before the next monthly occurrence.
                    schedule.firstOccurrenceOnOrAfter(today)
                } else if (weekdays.isNotEmpty()) {
                    // Weekdays are always within 1..7, so one of the next seven days matches.
                    (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek() in weekdays }
                } else {
                    val remainder = today.daysBetween(start) % schedule.interval
                    today.plusDays(if (remainder == 0L) 0L else schedule.interval - remainder)
                }

                if (nextOccurrence == null || (end != null && nextOccurrence > end)) {
                    tasksToDelete.add(task)
                    cleanedCount++
                    continue
                }

                var hadUncompletedPastOccurrence = false
                val pastDaysToCheck = today.minusDays(1).daysBetween(start)
                if (pastDaysToCheck >= 0) {
                    val checkDays = minOf(pastDaysToCheck, 30L)
                    for (offset in 0L..checkDays) {
                        val d = today.minusDays(1 + offset)
                        if (d >= start && schedule.isScheduledOn(d)) {
                            val dayKey = key(task.id, d.toIsoString())
                            if (dayKey !in completionKeys && dayKey !in exclusionKeys) {
                                hadUncompletedPastOccurrence = true
                                break
                            }
                        }
                    }
                }

                if (nextOccurrence == today && hadUncompletedPastOccurrence) {
                    movedCount++
                }

                tasksToUpdate.add(task.copy(startDate = nextOccurrence.toIsoString()))
            }
        }

        taskDao.performCleanupAndRolloverBatch(
            tasksToDelete = tasksToDelete,
            tasksToUpdate = tasksToUpdate,
            todayIso = todayIso
        )

        return TaskRepository.RolloverResult(
            cleanedCount = cleanedCount,
            movedCount = movedCount,
            cleanedRecurringOccurrences = cleanedRecurringOccurrences
        )
    }

    fun isTaskScheduledOnDate(task: TaskEntity, targetDate: AppDate): Boolean =
        TaskSchedule.from(task)?.isScheduledOn(targetDate) ?: false

    private fun key(taskId: Long, dateIso: String) = "${taskId}_$dateIso"
}

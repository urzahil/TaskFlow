package com.example.data.repository

import com.example.data.db.TaskDao
import com.example.data.model.AppDate
import com.example.data.model.TaskEntity

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
        val allCompletions = taskDao.getAllCompletionsList()
        val completionKeys = allCompletions.map { "${it.taskId}_${it.date}" }.toSet()
        val exclusionKeys = taskDao.getAllOccurrenceExclusionsList()
            .map { "${it.taskId}_${it.date}" }.toSet()

        var cleanedCount = 0
        var movedCount = 0
        var cleanedRecurringOccurrences = 0

        val tasksToDelete = mutableListOf<TaskEntity>()
        val tasksToUpdate = mutableListOf<TaskEntity>()
        val taskIdsForCompletionDeletion = mutableListOf<Long>()

        // A completed non-recurring task is finished regardless of its scheduled date.
        for (task in completedNonRecurringTasks) {
            tasksToDelete.add(task)
            taskIdsForCompletionDeletion.add(task.id)
            cleanedCount++
        }

        for (task in pastTasks) {
            tasksToUpdate.add(task.copy(startDate = todayIso))
            movedCount++
        }

        val recurringTasks = taskDao.getAllRecurringTasks()
        for (task in recurringTasks) {
            val start = try {
                AppDate.parseIso(task.startDate)
            } catch (_: Exception) {
                null
            } ?: continue

            val end = task.endDate?.takeIf { it.isNotBlank() }?.let {
                try {
                    AppDate.parseIso(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            val lastPastDate = minOf(today.minusDays(1), end ?: today.minusDays(1))
            if (start <= lastPastDate) {
                val span = lastPastDate.daysBetween(start) + 1
                val weekdays = task.parsedDaysOfWeek()
                val count = if (weekdays.isEmpty()) {
                    (span - 1) / task.recurrenceDays.coerceAtLeast(1) + 1
                } else {
                    (span / 7) * weekdays.size + (0L until span % 7).count {
                        start.plusDays(it).dayOfWeek() in weekdays
                    }
                }
                val excludedPastOccurrences = (0L until span).count { offset ->
                    "${task.id}_${start.plusDays(offset).toIsoString()}" in exclusionKeys
                }
                cleanedRecurringOccurrences =
                    (cleanedRecurringOccurrences.toLong() + (count - excludedPastOccurrences).coerceAtLeast(
                        0
                    ))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }

            if (!task.endDate.isNullOrBlank()) {
                val expiredEnd = try {
                    AppDate.parseIso(task.endDate)
                } catch (_: Exception) {
                    null
                }
                if (expiredEnd != null && expiredEnd < today) {
                    tasksToDelete.add(task)
                    taskIdsForCompletionDeletion.add(task.id)
                    cleanedCount++
                    continue
                }
            }

            if (start < today) {
                val nextOccurrence: AppDate
                val validDaysOfWeek = task.parsedDaysOfWeek()
                if (validDaysOfWeek.isNotEmpty()) {
                    var candidate = today
                    var found = false
                    for (step in 0..7) {
                        if (candidate.dayOfWeek() in validDaysOfWeek) {
                            found = true
                            break
                        }
                        candidate = candidate.plusDays(1)
                    }
                    nextOccurrence = if (found) candidate else today
                } else {
                    val interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1
                    val diffDays = today.daysBetween(start)
                    val remainder = diffDays % interval
                    val daysToNext = if (remainder == 0L) 0L else (interval - remainder)
                    nextOccurrence = today.plusDays(daysToNext)
                }

                if (!task.endDate.isNullOrBlank()) {
                    val expiredEnd = try {
                        AppDate.parseIso(task.endDate)
                    } catch (_: Exception) {
                        null
                    }
                    if (expiredEnd != null && nextOccurrence > expiredEnd) {
                        tasksToDelete.add(task)
                        taskIdsForCompletionDeletion.add(task.id)
                        cleanedCount++
                        continue
                    }
                }

                var hadUncompletedPastOccurrence = false
                val pastDaysToCheck = today.minusDays(1).daysBetween(start)
                if (pastDaysToCheck >= 0) {
                    val checkDays = minOf(pastDaysToCheck, 30L)
                    for (offset in 0L..checkDays) {
                        val d = today.minusDays(1 + offset)
                        if (d >= start && isTaskScheduledOnDate(task, d)) {
                            if ("${task.id}_${d.toIsoString()}" !in completionKeys &&
                                "${task.id}_${d.toIsoString()}" !in exclusionKeys
                            ) {
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
            taskIdsForCompletionDeletion = taskIdsForCompletionDeletion,
            todayIso = todayIso
        )

        return TaskRepository.RolloverResult(
            cleanedCount = cleanedCount,
            movedCount = movedCount,
            cleanedRecurringOccurrences = cleanedRecurringOccurrences
        )
    }

    fun isTaskScheduledOnDate(task: TaskEntity, targetDate: AppDate): Boolean {
        val start = try {
            AppDate.parseIso(task.startDate)
        } catch (_: Exception) {
            return false
        }

        if (!task.isRecurring) {
            return task.startDate == targetDate.toIsoString()
        }

        if (targetDate < start) {
            return false
        }

        if (!task.endDate.isNullOrBlank()) {
            val end = try {
                AppDate.parseIso(task.endDate)
            } catch (_: Exception) {
                null
            }
            if (end != null && targetDate > end) {
                return false
            }
        }

        val validDaysOfWeek = task.parsedDaysOfWeek()
        if (validDaysOfWeek.isNotEmpty()) {
            return targetDate.dayOfWeek() in validDaysOfWeek
        }

        val interval = if (task.recurrenceDays > 0) task.recurrenceDays else 1
        val diffDays = targetDate.daysBetween(start)
        return (diffDays % interval) == 0L
    }
}

package rocks.urzahil.taskflow.data.repository

import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.MonthWeekday
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
    val start: AppDate,
    /** Null when the task repeats indefinitely (or its end date is unparseable). */
    val end: AppDate?,
    /** Days between occurrences when [weekdays] is empty; always at least 1. */
    val interval: Int,
    /** ISO weekdays (1 = Monday) the task repeats on; empty for every-N-days tasks. */
    val weekdays: Set<Int>,
    /** Days of the month (1..31) for monthly tasks; a day a month lacks falls on its last day. */
    val monthDays: Set<Int> = emptySet(),
    /** "Nth weekday of the month" rules for monthly tasks. */
    val monthWeekdays: Set<MonthWeekday> = emptySet(),
    /** Months between active months for monthly tasks, counted from [start]'s month; at least 1. */
    val monthInterval: Int = 1
) {
    /** True when the task repeats monthly; monthly rules take precedence over the others. */
    val isMonthly: Boolean get() = monthDays.isNotEmpty() || monthWeekdays.isNotEmpty()

    fun isScheduledOn(date: AppDate): Boolean {
        if (!isRecurring) return date == start
        if (date < start) return false
        if (end != null && date > end) return false
        if (isMonthly) return isMonthlyOccurrence(date)
        if (weekdays.isNotEmpty()) return date.dayOfWeek() in weekdays
        return date.daysBetween(start) % interval == 0L
    }

    private fun isMonthlyOccurrence(date: AppDate): Boolean {
        val monthsSinceStart = (date.year - start.year) * 12 + (date.month - start.month)
        if (monthsSinceStart % monthInterval != 0) return false
        if (monthWeekdays.isNotEmpty()) return monthWeekdays.any { it.matches(date) }
        val lastDay = AppDate.daysInMonth(date.year, date.month)
        // A day past the end of the month (e.g. the 31st in April) falls on the last day.
        return date.day in monthDays || (date.day == lastDay && monthDays.any { it > lastDay })
    }

    /**
     * The first scheduled date on or after [from], or null when there is none (the task has
     * ended). Recurring tasks always repeat within [interval] days, a week, or [monthInterval]
     * months, so the search is bounded.
     */
    fun firstOccurrenceOnOrAfter(from: AppDate): AppDate? {
        val first = maxOf(from, start)
        if (!isRecurring) return start.takeIf { it >= from }
        val searchDays = when {
            isMonthly -> (monthInterval + 1) * 31L
            weekdays.isNotEmpty() -> 7L
            else -> interval.toLong()
        }
        for (offset in 0L until searchDays) {
            val date = first.plusDays(offset)
            if (end != null && date > end) return null
            if (isScheduledOn(date)) return date
        }
        return null
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
                weekdays = task.parsedDaysOfWeek(),
                monthDays = task.parsedMonthDays(),
                monthWeekdays = task.parsedMonthWeekdays(),
                monthInterval = task.recurrenceMonths.coerceIn(1, TaskEntity.MAX_RECURRENCE_MONTHS)
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

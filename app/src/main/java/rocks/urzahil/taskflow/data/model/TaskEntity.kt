package rocks.urzahil.taskflow.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val category: String = "General",
    val priority: String = "Medium",
    val colorHex: Long = 0xFF3B82F6,
    val isRecurring: Boolean = false,
    val recurrenceDays: Int = 1, // repeats every n days
    val recurrenceDaysOfWeek: String? = null, // comma-separated ISO day of week 1..7 (1=Monday, 7=Sunday)
    // Monthly recurrence (takes precedence over the fields above when set). Days of the month
    // 1..31, comma-separated; a day past the end of a month falls on that month's last day.
    val recurrenceMonthDays: String? = null,
    // Monthly recurrence on weekdays: comma-separated "ordinal:weekday" pairs, ordinal 1..4 or -1
    // for the last one, weekday 1..7 (1 = Monday), e.g. "1:1,-1:5" = 1st Monday and last Friday.
    val recurrenceMonthWeekdays: String? = null,
    val recurrenceMonths: Int = 1, // monthly recurrence: repeats every n months (1..12)
    val startDate: String, // ISO YYYY-MM-DD
    val endDate: String? = null, // Optional ISO YYYY-MM-DD
    val sharedSubtasks: Boolean = true, // recurring: one subtask list for every occurrence, or per-occurrence lists
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Parses and returns the valid set of ISO weekdays (1 = Monday, 7 = Sunday).
     * Any invalid values (e.g. outside 1..7, unparseable) are discarded.
     */
    fun parsedDaysOfWeek(): Set<Int> {
        if (recurrenceDaysOfWeek.isNullOrBlank()) return emptySet()
        return recurrenceDaysOfWeek.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
    }

    /** Parsed days of the month (1..31) for monthly recurrence; invalid values are discarded. */
    fun parsedMonthDays(): Set<Int> = parseMonthDays(recurrenceMonthDays)

    /** Parsed "Nth weekday" rules for monthly recurrence; invalid values are discarded. */
    fun parsedMonthWeekdays(): Set<MonthWeekday> = parseMonthWeekdays(recurrenceMonthWeekdays)

    /** True when the task repeats monthly (on days of the month or on weekdays). */
    fun isMonthlyRecurrence(): Boolean =
        parsedMonthDays().isNotEmpty() || parsedMonthWeekdays().isNotEmpty()

    companion object {
        const val MAX_RECURRENCE_MONTHS = 12

        fun parseMonthDays(raw: String?): Set<Int> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..31 }
                .toSet()
        }

        fun parseMonthWeekdays(raw: String?): Set<MonthWeekday> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",").mapNotNull { MonthWeekday.parse(it.trim()) }.toSet()
        }

        fun formatMonthDays(days: Collection<Int>): String? =
            days.filter { it in 1..31 }.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(",")

        fun formatMonthWeekdays(rules: Collection<MonthWeekday>): String? =
            rules.distinct()
                .sortedWith(compareBy<MonthWeekday>({ if (it.ordinal == MonthWeekday.LAST) 5 else it.ordinal }, { it.dayOfWeek }))
                .takeIf { it.isNotEmpty() }
                ?.joinToString(",") { "${it.ordinal}:${it.dayOfWeek}" }
    }
}

/**
 * "The [ordinal] [dayOfWeek] of the month": ordinal 1..4, or [LAST] for the last such weekday;
 * dayOfWeek is ISO (1 = Monday, 7 = Sunday).
 */
data class MonthWeekday(val ordinal: Int, val dayOfWeek: Int) {
    init {
        require(ordinal in ORDINALS) { "Invalid ordinal: $ordinal" }
        require(dayOfWeek in 1..7) { "Invalid weekday: $dayOfWeek" }
    }

    /** Whether [date] is this weekday of its month. */
    fun matches(date: AppDate): Boolean {
        if (date.dayOfWeek() != dayOfWeek) return false
        return if (ordinal == LAST) {
            date.day + 7 > AppDate.daysInMonth(date.year, date.month)
        } else {
            (date.day - 1) / 7 + 1 == ordinal
        }
    }

    companion object {
        const val LAST = -1
        val ORDINALS = listOf(1, 2, 3, 4, LAST)

        fun parse(raw: String): MonthWeekday? {
            val parts = raw.split(":")
            if (parts.size != 2) return null
            val ordinal = parts[0].trim().toIntOrNull() ?: return null
            val dow = parts[1].trim().toIntOrNull() ?: return null
            if (ordinal !in ORDINALS || dow !in 1..7) return null
            return MonthWeekday(ordinal, dow)
        }
    }
}

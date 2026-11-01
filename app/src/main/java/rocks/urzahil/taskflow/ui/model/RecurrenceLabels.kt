package rocks.urzahil.taskflow.ui.model

import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.MonthWeekday

/** Text shown for monthly recurrence rules on task cards and in the add/edit sheet. */
object RecurrenceLabels {

    fun ordinal(ordinal: Int): String = when (ordinal) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        4 -> "4th"
        MonthWeekday.LAST -> "Last"
        else -> ""
    }

    /** "Monthly" or "Every 3 months". */
    fun monthInterval(months: Int): String =
        if (months <= 1) "Monthly" else "Every $months months"

    /** "1, 15, 31". */
    fun monthDays(days: Set<Int>): String = days.sorted().joinToString(", ")

    /**
     * "1st Mon", "1st & 3rd Mon, Wed" when the rules are every ordinal paired with every
     * weekday (what the picker produces), otherwise each rule in turn ("1st Mon, Last Fri").
     */
    fun monthWeekdays(rules: Set<MonthWeekday>): String {
        if (rules.isEmpty()) return ""
        val ordinals = sortedOrdinals(rules.map { it.ordinal }.toSet())
        val weekdays = rules.map { it.dayOfWeek }.toSet().sorted()
        val isGrid = rules.size == ordinals.size * weekdays.size
        if (isGrid) {
            return ordinals.joinToString(" & ") { ordinal(it) } + " " +
                weekdays.joinToString(", ") { AppDate.dayOfWeekShort(it) }
        }
        return rules.sortedWith(compareBy({ ordinalSortKey(it.ordinal) }, { it.dayOfWeek }))
            .joinToString(", ") { "${ordinal(it.ordinal)} ${AppDate.dayOfWeekShort(it.dayOfWeek)}" }
    }

    /** "Monthly · 1, 15" or "Every 3 months · 1st Mon"; empty when there are no monthly rules. */
    fun monthly(months: Int, days: Set<Int>, rules: Set<MonthWeekday>): String {
        val detail = when {
            rules.isNotEmpty() -> monthWeekdays(rules)
            days.isNotEmpty() -> monthDays(days)
            else -> return ""
        }
        return "${monthInterval(months)} · $detail"
    }

    fun sortedOrdinals(ordinals: Set<Int>): List<Int> = ordinals.sortedBy { ordinalSortKey(it) }

    private fun ordinalSortKey(ordinal: Int) = if (ordinal == MonthWeekday.LAST) 5 else ordinal
}

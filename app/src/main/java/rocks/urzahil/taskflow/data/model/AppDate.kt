package rocks.urzahil.taskflow.data.model

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * A lightweight, self-contained date representation (year, month 1-12, day 1-31)
 * with Gregorian civil arithmetic. Independent of Android API level.
 */
data class AppDate(
    val year: Int,
    val month: Int,
    val day: Int
) : Comparable<AppDate> {

    init {
        require(month in 1..12) { "Month must be between 1 and 12: $month" }
        require(day in 1..daysInMonth(year, month)) { "Invalid day for $year-$month: $day" }
    }

    /**
     * Converts date to ISO string "YYYY-MM-DD"
     */
    fun toIsoString(): String {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
    }

    /**
     * Calculates the number of days since Jan 1, 1970 (Unix Epoch)
     */
    fun toEpochDay(): Long {
        var y = year.toLong()
        var m = month.toLong()
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val era = if (y >= 0) y / 400 else (y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m - 3) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /**
     * Day of week: 1 = Monday, 7 = Sunday
     */
    fun dayOfWeek(): Int {
        val epochDay = toEpochDay()
        // Jan 1 1970 was Thursday (4)
        val dow = ((epochDay + 3) % 7 + 7) % 7 + 1
        return dow.toInt()
    }

    fun plusDays(days: Long): AppDate {
        if (days == 0L) return this
        return fromEpochDay(toEpochDay() + days)
    }

    fun minusDays(days: Long): AppDate {
        return plusDays(-days)
    }

    fun daysBetween(other: AppDate): Long {
        return this.toEpochDay() - other.toEpochDay()
    }

    /**
     * Localized long date, e.g. "16 October 2026" (en-GB), "October 16, 2026" (en-US) or
     * "16. Oktober 2026" (de). Without the year only day and month are shown, in the order
     * the locale uses. (Name kept for existing callers; the output is no longer European-only.)
     */
    fun formatEuropean(includeYear: Boolean = true, locale: Locale = Locale.getDefault()): String {
        if (includeYear) {
            return DateFormat.getDateInstance(DateFormat.LONG, locale).format(toJavaDate())
        }
        return format(if (dayBeforeMonth(locale)) "d MMMM" else "MMMM d", locale)
    }

    /** Localized date with an abbreviated month, e.g. "16 Oct 2026" or "Oct 16, 2026". */
    fun formatEuropeanShort(
        includeYear: Boolean = true,
        locale: Locale = Locale.getDefault()
    ): String {
        val dayFirst = dayBeforeMonth(locale)
        val pattern = when {
            !includeYear -> if (dayFirst) "d MMM" else "MMM d"
            yearFirst(locale) -> "y MMM d"
            dayFirst -> "d MMM y"
            else -> "MMM d, y"
        }
        return format(pattern, locale)
    }

    private fun toJavaDate(): java.util.Date =
        GregorianCalendar(year, month - 1, day).time

    private fun format(pattern: String, locale: Locale): String =
        SimpleDateFormat(pattern, locale).format(toJavaDate())

    override fun compareTo(other: AppDate): Int {
        val yCmp = year.compareTo(other.year)
        if (yCmp != 0) return yCmp
        val mCmp = month.compareTo(other.month)
        if (mCmp != 0) return mCmp
        return day.compareTo(other.day)
    }

    companion object {
        fun today(): AppDate {
            val cal = Calendar.getInstance()
            return AppDate(
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                day = cal.get(Calendar.DAY_OF_MONTH)
            )
        }

        fun parseIso(iso: String): AppDate {
            val parts = iso.split("-")
            require(parts.size == 3) { "Invalid ISO date string: $iso" }
            return AppDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

        fun fromEpochDay(epochDay: Long): AppDate {
            val zeroDay = epochDay + 719468
            var era = if (zeroDay >= 0) zeroDay / 146097 else (zeroDay - 146096) / 146097
            val doe = zeroDay - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            var y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            if (m <= 2) y += 1
            return AppDate(y.toInt(), m.toInt(), d.toInt())
        }

        fun isLeapYear(year: Int): Boolean {
            return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
        }

        fun daysInMonth(year: Int, month: Int): Int {
            return when (month) {
                2 -> if (isLeapYear(year)) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
        }

        private fun longPattern(locale: Locale): String =
            (DateFormat.getDateInstance(DateFormat.LONG, locale) as? SimpleDateFormat)
                ?.toPattern()
                .orEmpty()

        private fun indexOfAny(pattern: String, vararg letters: Char): Int =
            pattern.indexOfFirst { it in letters }.let { if (it < 0) Int.MAX_VALUE else it }

        private fun dayBeforeMonth(locale: Locale): Boolean {
            val pattern = longPattern(locale)
            return indexOfAny(pattern, 'd') < indexOfAny(pattern, 'M', 'L')
        }

        private fun yearFirst(locale: Locale): Boolean {
            val pattern = longPattern(locale)
            val y = indexOfAny(pattern, 'y', 'u')
            return y < indexOfAny(pattern, 'd') && y < indexOfAny(pattern, 'M', 'L')
        }

        private fun standalone(pattern: String, date: AppDate, locale: Locale): String =
            SimpleDateFormat(pattern, locale).format(date.toJavaDate())
                .replaceFirstChar { it.titlecase(locale) }

        /** Standalone, localized month name (1-12), e.g. "October". */
        fun monthName(month: Int, locale: Locale = Locale.getDefault()): String =
            if (month in 1..12) standalone("LLLL", AppDate(2024, month, 1), locale) else ""

        fun monthNameShort(month: Int, locale: Locale = Locale.getDefault()): String =
            if (month in 1..12) standalone("LLL", AppDate(2024, month, 1), locale) else ""

        /** Localized weekday name for an ISO day of week (1 = Monday ... 7 = Sunday). */
        fun dayOfWeekName(dow: Int, locale: Locale = Locale.getDefault()): String =
            if (dow in 1..7) {
                standalone("EEEE", AppDate(2024, 1, 1).plusDays(dow - 1L), locale)
            } else ""

        fun dayOfWeekShort(dow: Int, locale: Locale = Locale.getDefault()): String =
            if (dow in 1..7) {
                standalone("EEE", AppDate(2024, 1, 1).plusDays(dow - 1L), locale)
            } else ""
    }
}

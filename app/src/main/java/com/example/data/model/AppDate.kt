package com.example.data.model

import java.util.Calendar

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
        require(day in 1..31) { "Day must be between 1 and 31: $day" }
    }

    /**
     * Converts date to ISO string "YYYY-MM-DD"
     */
    fun toIsoString(): String {
        return "%04d-%02d-%02d".format(year, month, day)
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

        fun monthName(month: Int): String {
            return when (month) {
                1 -> "January"
                2 -> "February"
                3 -> "March"
                4 -> "April"
                5 -> "May"
                6 -> "June"
                7 -> "July"
                8 -> "August"
                9 -> "September"
                10 -> "October"
                11 -> "November"
                12 -> "December"
                else -> ""
            }
        }

        fun monthNameShort(month: Int): String {
            return when (month) {
                1 -> "Jan"
                2 -> "Feb"
                3 -> "Mar"
                4 -> "Apr"
                5 -> "May"
                6 -> "Jun"
                7 -> "Jul"
                8 -> "Aug"
                9 -> "Sep"
                10 -> "Oct"
                11 -> "Nov"
                12 -> "Dec"
                else -> ""
            }
        }

        fun dayOfWeekName(dow: Int): String {
            return when (dow) {
                1 -> "Monday"
                2 -> "Tuesday"
                3 -> "Wednesday"
                4 -> "Thursday"
                5 -> "Friday"
                6 -> "Saturday"
                7 -> "Sunday"
                else -> ""
            }
        }

        fun dayOfWeekShort(dow: Int): String {
            return when (dow) {
                1 -> "Mon"
                2 -> "Tue"
                3 -> "Wed"
                4 -> "Thu"
                5 -> "Fri"
                6 -> "Sat"
                7 -> "Sun"
                else -> ""
            }
        }
    }
}

package rocks.urzahil.taskflow.ui.monthly

import rocks.urzahil.taskflow.data.model.AppDate

/**
 * The dates shown by the monthly calendar grid: the month's days, led by the end of the previous
 * month (the grid starts on Monday) and padded with the start of the next to 5 or 6 full weeks.
 */
fun monthGridDates(year: Int, month: Int): List<AppDate> {
    val firstDay = AppDate(year, month, 1)
    val leadingDaysCount = firstDay.dayOfWeek() - 1 // 1 = Mon, 7 = Sun

    val days = mutableListOf<AppDate>()
    for (i in leadingDaysCount downTo 1) {
        days.add(firstDay.minusDays(i.toLong()))
    }

    val daysInMonth = AppDate.daysInMonth(year, month)
    for (d in 1..daysInMonth) {
        days.add(AppDate(year, month, d))
    }

    val totalCells = if (days.size <= 35) 35 else 42
    val lastDay = AppDate(year, month, daysInMonth)
    var trailing = 1
    while (days.size < totalCells) {
        days.add(lastDay.plusDays(trailing.toLong()))
        trailing++
    }
    return days
}

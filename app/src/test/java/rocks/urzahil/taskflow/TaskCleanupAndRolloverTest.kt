package rocks.urzahil.taskflow

import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCleanupAndRolloverTest {

    @Test
    fun testUncompletedTasksRolloverToToday() {
        val today = AppDate(2026, 9, 23)
        val todayIso = today.toIsoString()

        val pastUncompletedTask = TaskEntity(
            id = 10,
            title = "Unfinished report",
            isRecurring = false,
            startDate = "2026-09-21" // 2 days ago
        )

        val completedTaskIds = setOf<Long>() // Not completed

        // Verify it was scheduled in the past
        assertTrue(pastUncompletedTask.startDate < todayIso)
        assertFalse(pastUncompletedTask.id in completedTaskIds)

        // Rollover action moves start date to today
        val rolledOverTask = pastUncompletedTask.copy(startDate = todayIso)
        assertEquals(todayIso, rolledOverTask.startDate)
        assertEquals(10L, rolledOverTask.id)
    }

    @Test
    fun testCompletedTasksIdentifiedForCleanup() {
        val today = AppDate(2026, 9, 23)
        val todayIso = today.toIsoString()

        val pastCompletedTask = TaskEntity(
            id = 11,
            title = "Old done task",
            isRecurring = false,
            startDate = "2026-09-20"
        )

        val completedTaskIds = setOf(11L)

        // Verify it was in the past AND completed
        assertTrue(pastCompletedTask.startDate < todayIso)
        assertTrue(pastCompletedTask.id in completedTaskIds)

        // Simulate cleanup logic
        val tasks = mutableListOf(pastCompletedTask)
        tasks.removeAll { it.id in completedTaskIds && it.startDate < todayIso }
        assertEquals(0, tasks.size)
    }

    @Test
    fun testRecurringTaskAdvancesStartDateDeletingPastOccurrences() {
        val today = AppDate(2026, 9, 23)

        // Daily recurring task started on Sep 15
        val dailyTask = TaskEntity(
            id = 12,
            title = "Daily morning jog",
            isRecurring = true,
            recurrenceDays = 1,
            startDate = "2026-09-15"
        )

        val start = AppDate.parseIso(dailyTask.startDate)
        assertTrue(start < today)

        val interval = dailyTask.recurrenceDays
        val diffDays = today.daysBetween(start)
        val remainder = diffDays % interval
        val daysToNext = if (remainder == 0L) 0L else (interval - remainder)
        val nextOccurrence = today.plusDays(daysToNext)

        // Next occurrence for daily task is today itself
        assertEquals("2026-09-23", nextOccurrence.toIsoString())

        val updatedTask = dailyTask.copy(startDate = nextOccurrence.toIsoString())
        // Start date is now today: so any date before today (e.g. Sep 22) is now before startDate, hence no occurrence
        val pastDate = AppDate(2026, 9, 22)
        assertTrue(pastDate < AppDate.parseIso(updatedTask.startDate))
    }

    @Test
    fun testWeekdayShortFormat() {
        val wednesday = AppDate(2026, 9, 23)
        assertEquals(3, wednesday.dayOfWeek())
        assertEquals("Wed", AppDate.dayOfWeekShort(wednesday.dayOfWeek()))
        assertEquals("Wednesday", AppDate.dayOfWeekName(wednesday.dayOfWeek()))
        assertEquals(3, AppDate.dayOfWeekShort(wednesday.dayOfWeek()).length)
    }

    @Test
    fun testLocalizedDateFormatUk() {
        val date = AppDate(2026, 9, 23)
        val uk = java.util.Locale.UK
        assertEquals("23 September 2026", date.formatLong(includeYear = true, locale = uk))
        assertEquals("23 September", date.formatLong(includeYear = false, locale = uk))
        // CLDR abbreviates September as "Sept" in en-GB on newer JDKs, so only check the shape.
        assertTrue(date.formatShort(includeYear = true, locale = uk).matches(Regex("23 Sept? 2026")))
        assertTrue(date.formatShort(includeYear = false, locale = uk).matches(Regex("23 Sept?")))
    }

    @Test
    fun testLocalizedDateFormatUsPutsMonthFirst() {
        val date = AppDate(2026, 9, 23)
        val us = java.util.Locale.US
        assertEquals("September 23, 2026", date.formatLong(includeYear = true, locale = us))
        assertEquals("September 23", date.formatLong(includeYear = false, locale = us))
        assertEquals("Sep 23, 2026", date.formatShort(includeYear = true, locale = us))
    }

    @Test
    fun testLocalizedNamesFollowLocale() {
        assertEquals("Wednesday", AppDate.dayOfWeekName(3, java.util.Locale.ENGLISH))
        assertEquals("Mittwoch", AppDate.dayOfWeekName(3, java.util.Locale.GERMAN))
        assertEquals("September", AppDate.monthName(9, java.util.Locale.ENGLISH))
        assertEquals("", AppDate.monthName(13))
    }
}

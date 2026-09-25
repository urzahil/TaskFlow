package com.example

import com.example.data.model.AppDate
import com.example.data.model.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecurrenceTest {

    @Test
    fun testAppDateArithmetic() {
        val date1 = AppDate(2026, 9, 23)
        assertEquals("2026-09-23", date1.toIsoString())

        val date2 = date1.plusDays(3)
        assertEquals("2026-09-26", date2.toIsoString())
        assertEquals(3L, date2.daysBetween(date1))

        val endOfMonth = AppDate(2026, 9, 30)
        val nextMonth = endOfMonth.plusDays(1)
        assertEquals("2026-10-01", nextMonth.toIsoString())

        val prevMonth = nextMonth.minusDays(1)
        assertEquals("2026-09-30", prevMonth.toIsoString())
    }

    @Test
    fun testRecurrenceInterval() {
        // Starts on Sep 20, repeats every 3 days
        val start = AppDate(2026, 9, 20)
        val task = TaskEntity(
            id = 1,
            title = "Water plants",
            isRecurring = true,
            recurrenceDays = 3,
            startDate = start.toIsoString()
        )

        // Sep 20: 0 days diff -> 0 % 3 == 0 -> should occur
        assertEquals(0L, AppDate(2026, 9, 20).daysBetween(start) % 3)
        // Sep 21: 1 days diff -> not on
        assertEquals(1L, AppDate(2026, 9, 21).daysBetween(start) % 3)
        // Sep 22: 2 days diff -> not on
        assertEquals(2L, AppDate(2026, 9, 22).daysBetween(start) % 3)
        // Sep 23: 3 days diff -> 3 % 3 == 0 -> should occur
        assertEquals(0L, AppDate(2026, 9, 23).daysBetween(start) % 3)
        // Sep 26: 6 days diff -> 6 % 3 == 0 -> should occur
        assertEquals(0L, AppDate(2026, 9, 26).daysBetween(start) % 3)
    }

    @Test
    fun testSimpleOneTimeTask() {
        val task = TaskEntity(
            id = 2,
            title = "Doctor appointment",
            isRecurring = false,
            startDate = "2026-09-24"
        )

        assertTrue(task.startDate == AppDate(2026, 9, 24).toIsoString())
        assertFalse(task.startDate == AppDate(2026, 9, 25).toIsoString())
    }

    @Test
    fun testDaysOfWeekRecurrence() {
        // Monday (1), Wednesday (3), Friday (5)
        val selectedDays = setOf(1, 3, 5)
        val task = TaskEntity(
            id = 3,
            title = "Gym workout",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "1,3,5",
            startDate = "2026-09-21" // Sep 21 2026 is Monday (1)
        )

        val mon = AppDate(2026, 9, 21) // Monday -> 1
        val tue = AppDate(2026, 9, 22) // Tuesday -> 2
        val wed = AppDate(2026, 9, 23) // Wednesday -> 3
        val thu = AppDate(2026, 9, 24) // Thursday -> 4
        val fri = AppDate(2026, 9, 25) // Friday -> 5
        val sat = AppDate(2026, 9, 26) // Saturday -> 6
        val sun = AppDate(2026, 9, 27) // Sunday -> 7
        val nextMon = AppDate(2026, 9, 28) // Next Monday -> 1

        assertEquals(1, mon.dayOfWeek())
        assertEquals(2, tue.dayOfWeek())
        assertEquals(3, wed.dayOfWeek())
        assertEquals(4, thu.dayOfWeek())
        assertEquals(5, fri.dayOfWeek())
        assertEquals(6, sat.dayOfWeek())
        assertEquals(7, sun.dayOfWeek())
        assertEquals(1, nextMon.dayOfWeek())

        val taskDays = task.parsedDaysOfWeek()
        assertTrue(mon.dayOfWeek() in taskDays)
        assertFalse(tue.dayOfWeek() in taskDays)
        assertTrue(wed.dayOfWeek() in taskDays)
        assertFalse(thu.dayOfWeek() in taskDays)
        assertTrue(fri.dayOfWeek() in taskDays)
        assertFalse(sat.dayOfWeek() in taskDays)
        assertFalse(sun.dayOfWeek() in taskDays)
        assertTrue(nextMon.dayOfWeek() in taskDays)
    }

    @Test
    fun testInvalidRecurrenceDaysOfWeekDiscarded() {
        val taskWith8 = TaskEntity(
            id = 4,
            title = "Invalid dow",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "8",
            startDate = "2026-09-21"
        )
        assertTrue(taskWith8.parsedDaysOfWeek().isEmpty())

        val taskWithMixed = TaskEntity(
            id = 5,
            title = "Mixed dow",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "1,8,hello,5,-1",
            startDate = "2026-09-21"
        )
        assertEquals(setOf(1, 5), taskWithMixed.parsedDaysOfWeek())
    }
}

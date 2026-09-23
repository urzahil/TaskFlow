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
}

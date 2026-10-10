package com.example.data.repository

import com.example.data.model.AppDate
import com.example.data.model.TaskEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSchedulerTest {

    @Test
    fun `non recurring task is scheduled only on its start date`() {
        val task = TaskEntity(
            title = "One-off task",
            startDate = "2024-01-15"
        )

        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 15)))
        assertFalse(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 16)))
    }

    @Test
    fun `recurring interval task runs on every configured number of days`() {
        val task = TaskEntity(
            title = "Every 2 days",
            isRecurring = true,
            recurrenceDays = 2,
            startDate = "2024-01-01"
        )

        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 1)))
        assertFalse(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 2)))
        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 3)))
        assertFalse(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 4)))
    }

    @Test
    fun `weekday recurring task only runs on selected weekdays`() {
        val task = TaskEntity(
            title = "Weekly weekdays",
            isRecurring = true,
            recurrenceDaysOfWeek = "1,3,5",
            startDate = "2024-01-01"
        )

        // 2024-01-01 is Monday (1), 2024-01-03 Wednesday (3), 2024-01-05 Friday (5)
        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 1)))
        assertFalse(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 2)))
        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 3)))
        assertFalse(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 4)))
        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 5)))
    }

    @Test
    fun `task is not scheduled after its end date`() {
        val task = TaskEntity(
            title = "Limited recurrence",
            isRecurring = true,
            recurrenceDays = 1,
            startDate = "2024-01-01",
            endDate = "2024-01-03"
        )

        assertTrue(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 3)))
        assertFalse(TaskScheduler.isTaskScheduledOnDate(task, AppDate(2024, 1, 4)))
    }
}

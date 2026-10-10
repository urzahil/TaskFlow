package com.example.ui

import com.example.data.model.AppDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDateTransitionTrackerTest {

    @Test
    fun `detects when selected date should advance to today after a day rollover`() {
        val tracker = TaskDateTransitionTracker()

        assertFalse(tracker.onTodayUpdated(AppDate(2024, 1, 1), AppDate(2024, 1, 1)))
        assertTrue(tracker.onTodayUpdated(AppDate(2024, 1, 2), AppDate(2024, 1, 1)))
    }

    @Test
    fun `does not trigger for unrelated date changes`() {
        val tracker = TaskDateTransitionTracker()

        assertFalse(tracker.onTodayUpdated(AppDate(2024, 1, 1), AppDate(2024, 1, 1)))
        assertFalse(tracker.onTodayUpdated(AppDate(2024, 1, 2), AppDate(2024, 1, 5)))
    }
}

package com.example.ui

import com.example.data.model.AppDate

class TaskDateTransitionTracker {
    private var previousToday: AppDate? = null

    fun onTodayUpdated(currentToday: AppDate, selectedDate: AppDate): Boolean {
        val previous = previousToday ?: currentToday
        val shouldAdvance = selectedDate == previous && previous != currentToday
        previousToday = currentToday
        return shouldAdvance
    }
}

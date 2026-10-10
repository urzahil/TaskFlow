package com.example

import com.example.data.model.AppDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class AppDateTest {
    @Test
    fun storedDatesUseAsciiDigitsInEveryLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val date = AppDate(2026, 10, 1)
            assertEquals("2026-10-01", date.toIsoString())
            assertEquals(date, AppDate.parseIso(date.toIsoString()))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun rejectsImpossibleDatesAndPreservesLeapDayArithmetic() {
        for (date in listOf("2025-02-29", "2026-02-31", "2026-04-31")) {
            assertThrows(IllegalArgumentException::class.java) { AppDate.parseIso(date) }
        }
        assertEquals(AppDate(2024, 3, 1), AppDate.parseIso("2024-02-29").plusDays(1))
        assertEquals(AppDate(2026, 1, 1), AppDate(2025, 12, 31).plusDays(1))
    }
}

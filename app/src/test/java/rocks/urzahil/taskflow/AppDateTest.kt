package rocks.urzahil.taskflow

import rocks.urzahil.taskflow.data.model.AppDate
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

    @Test
    fun isoStringIsZeroPaddedLikeStringFormat() {
        val years = listOf(1, 9, 10, 99, 100, 999, 1000, 1999, 2026, 9999)
        for (year in years) {
            for (month in 1..12) {
                for (day in listOf(1, 9, 10, AppDate.daysInMonth(year, month))) {
                    val expected = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
                    assertEquals(expected, AppDate(year, month, day).toIsoString())
                }
            }
        }
    }
}

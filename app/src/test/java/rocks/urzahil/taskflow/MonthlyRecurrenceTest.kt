package rocks.urzahil.taskflow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.MonthWeekday
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.repository.TaskSchedule
import rocks.urzahil.taskflow.ui.components.monthWeekdayOrdinalOf
import rocks.urzahil.taskflow.ui.components.monthWeekdayRules
import rocks.urzahil.taskflow.ui.model.RecurrenceLabels
import java.util.Locale

class MonthlyRecurrenceTest {

    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    private fun schedule(
        startDate: String,
        days: String? = null,
        weekdays: String? = null,
        months: Int = 1,
        endDate: String? = null
    ): TaskSchedule = TaskSchedule.from(
        TaskEntity(
            id = 1,
            title = "Monthly",
            isRecurring = true,
            recurrenceMonthDays = days,
            recurrenceMonthWeekdays = weekdays,
            recurrenceMonths = months,
            startDate = startDate,
            endDate = endDate
        )
    )!!

    private fun date(iso: String) = AppDate.parseIso(iso)

    private fun TaskSchedule.on(iso: String) = isScheduledOn(date(iso))

    @Test
    fun dayMissingFromMonth_fallsOnLastDay() {
        val s = schedule("2026-01-01", days = "31")
        assertTrue(s.on("2026-01-31"))
        assertTrue(s.on("2026-02-28"))
        assertFalse(s.on("2026-02-27"))
        assertTrue(s.on("2026-04-30"))
        assertFalse(s.on("2026-04-29"))
        assertFalse(s.on("2026-05-30"))
        assertTrue(s.on("2026-05-31"))
    }

    @Test
    fun february_followsLeapYears() {
        val s = schedule("2027-01-01", days = "29")
        assertTrue(s.on("2027-01-29"))
        assertTrue(s.on("2027-02-28"))
        assertFalse(s.on("2028-02-28"))
        assertTrue(s.on("2028-02-29"))
    }

    @Test
    fun severalDays_occurOnEach() {
        val s = schedule("2026-01-01", days = "1,15,30,31")
        assertTrue(s.on("2026-01-01"))
        assertTrue(s.on("2026-01-15"))
        assertTrue(s.on("2026-01-30"))
        assertTrue(s.on("2026-01-31"))
        assertFalse(s.on("2026-01-16"))
        // 30 and 31 both fall on 28 February, which is a single occurrence.
        assertTrue(s.on("2026-02-28"))
        assertFalse(s.on("2026-02-27"))
    }

    @Test
    fun firstMondayAndLastFriday() {
        val s = schedule("2026-11-01", weekdays = "1:1,-1:5")
        assertTrue(s.on("2026-11-02")) // 1st Monday
        assertFalse(s.on("2026-11-09"))
        assertTrue(s.on("2026-11-27")) // last Friday
        assertFalse(s.on("2026-11-20"))
        assertTrue(s.on("2026-12-07"))
        assertTrue(s.on("2026-12-25"))
    }

    @Test
    fun lastWeekday_isTheFifthWhenThereIsOne() {
        // January 2027 has five Fridays: 1, 8, 15, 22, 29.
        val fourth = schedule("2027-01-01", weekdays = "4:5")
        val last = schedule("2027-01-01", weekdays = "-1:5")
        assertTrue(fourth.on("2027-01-22"))
        assertFalse(fourth.on("2027-01-29"))
        assertTrue(last.on("2027-01-29"))
        assertFalse(last.on("2027-01-22"))
    }

    @Test
    fun everyThreeMonths_countsFromStartMonthAndSkipsPassedDays() {
        val s = schedule("2026-11-20", weekdays = "1:1", months = 3)
        assertFalse(s.on("2026-11-02")) // before the start date
        assertFalse(s.on("2026-12-07"))
        assertFalse(s.on("2027-01-04"))
        assertTrue(s.on("2027-02-01"))
        assertTrue(s.on("2027-05-03"))
        assertEquals(date("2027-02-01"), s.firstOccurrenceOnOrAfter(date("2026-11-20")))
    }

    @Test
    fun firstOccurrence_isNullWhenTheTaskEndsFirst() {
        val s = schedule("2026-10-15", days = "15", endDate = "2026-11-10")
        assertEquals(date("2026-10-15"), s.firstOccurrenceOnOrAfter(date("2026-10-01")))
        assertNull(s.firstOccurrenceOnOrAfter(date("2026-10-16")))
    }

    @Test
    fun monthlyRules_takePrecedenceOverWeekdays() {
        val task = TaskEntity(
            id = 1,
            title = "Mixed",
            isRecurring = true,
            recurrenceDays = 7,
            recurrenceDaysOfWeek = "1",
            recurrenceMonthDays = "15",
            startDate = "2026-11-01"
        )
        val s = TaskSchedule.from(task)!!
        assertTrue(s.isScheduledOn(date("2026-11-15")))
        assertFalse(s.isScheduledOn(date("2026-11-02"))) // a Monday
    }

    @Test
    fun parsing_discardsInvalidValues() {
        assertEquals(setOf(15, 31), TaskEntity.parseMonthDays("0,32,abc, 15,31"))
        assertEquals(
            setOf(MonthWeekday(1, 1), MonthWeekday(MonthWeekday.LAST, 5)),
            TaskEntity.parseMonthWeekdays("1:1,5:1,-1:8,x,-1:5,2")
        )
        assertEquals("1,15,31", TaskEntity.formatMonthDays(listOf(31, 1, 15, 15)))
        assertEquals(
            "1:1,3:1,-1:5",
            TaskEntity.formatMonthWeekdays(
                listOf(MonthWeekday(MonthWeekday.LAST, 5), MonthWeekday(3, 1), MonthWeekday(1, 1))
            )
        )
        assertNull(TaskEntity.formatMonthDays(emptyList()))
    }

    @Test
    fun pickerHelpers() {
        assertEquals(
            setOf(MonthWeekday(1, 1), MonthWeekday(1, 3), MonthWeekday(3, 1), MonthWeekday(3, 3)),
            monthWeekdayRules(setOf(1, 3), setOf(1, 3))
        )
        assertEquals(1, monthWeekdayOrdinalOf(date("2026-11-07")))
        assertEquals(4, monthWeekdayOrdinalOf(date("2026-11-28")))
        assertEquals(MonthWeekday.LAST, monthWeekdayOrdinalOf(date("2026-11-30")))
    }

    @Test
    fun labels() {
        assertEquals("Monthly · 1, 15, 31", RecurrenceLabels.monthly(1, setOf(31, 1, 15), emptySet()))
        assertEquals(
            "Every 3 months · 1st & 3rd Mon, Wed",
            RecurrenceLabels.monthly(3, emptySet(), monthWeekdayRules(setOf(3, 1), setOf(3, 1)))
        )
        assertEquals(
            "Monthly · 1st Mon, Last Fri",
            RecurrenceLabels.monthly(
                1,
                emptySet(),
                setOf(MonthWeekday(MonthWeekday.LAST, 5), MonthWeekday(1, 1))
            )
        )
        assertEquals("", RecurrenceLabels.monthly(1, emptySet(), emptySet()))
    }
}

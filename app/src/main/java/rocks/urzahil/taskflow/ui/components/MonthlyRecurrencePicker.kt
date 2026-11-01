package rocks.urzahil.taskflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.data.model.MonthWeekday
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.ui.model.RecurrenceLabels

private val CellSize = 34.dp

/**
 * Monthly recurrence settings: every N months, then either days of the month (1..31) or
 * "Nth weekday" rules built from the chosen ordinals and weekdays. Each selection keeps at
 * least one item, like the days-of-the-week picker.
 *
 * Test tags: "month_interval_decrease" / "month_interval_increase" / "month_interval_value",
 * "monthly_mode_dates" / "monthly_mode_weekdays", "month_day_<1..31>",
 * "month_ordinal_<1..4|-1>", "month_weekday_<1..7>".
 */
@Composable
fun MonthlyRecurrencePicker(
    monthInterval: Int,
    onMonthIntervalChange: (Int) -> Unit,
    byWeekday: Boolean,
    onByWeekdayChange: (Boolean) -> Unit,
    selectedDays: Set<Int>,
    onSelectedDaysChange: (Set<Int>) -> Unit,
    selectedOrdinals: Set<Int>,
    onSelectedOrdinalsChange: (Set<Int>) -> Unit,
    selectedWeekdays: Set<Int>,
    onSelectedWeekdaysChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MonthIntervalStepper(monthInterval, onMonthIntervalChange)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !byWeekday,
                onClick = { onByWeekdayChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {},
                modifier = Modifier.testTag("monthly_mode_dates")
            ) {
                Text("On dates")
            }
            SegmentedButton(
                selected = byWeekday,
                onClick = { onByWeekdayChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {},
                modifier = Modifier.testTag("monthly_mode_weekdays")
            ) {
                Text("On weekday")
            }
        }

        if (byWeekday) {
            Text(
                text = "Which:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MonthWeekday.ORDINALS.forEach { ordinal ->
                    SelectableCell(
                        label = RecurrenceLabels.ordinal(ordinal),
                        selected = ordinal in selectedOrdinals,
                        onClick = { onSelectedOrdinalsChange(toggleKeepingOne(selectedOrdinals, ordinal)) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("month_ordinal_$ordinal")
                    )
                }
            }
            Text(
                text = "Day of the week:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val initials = listOf("M", "T", "W", "T", "F", "S", "S")
                (1..7).forEach { dow ->
                    SelectableCell(
                        label = initials[dow - 1],
                        selected = dow in selectedWeekdays,
                        onClick = { onSelectedWeekdaysChange(toggleKeepingOne(selectedWeekdays, dow)) },
                        modifier = Modifier
                            .size(CellSize)
                            .testTag("month_weekday_$dow")
                    )
                }
            }
            val rules = monthWeekdayRules(selectedOrdinals, selectedWeekdays)
            Summary("${RecurrenceLabels.monthInterval(monthInterval)} on the ${RecurrenceLabels.monthWeekdays(rules)}")
        } else {
            Text(
                text = "Select days of the month:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..31).chunked(7).forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        week.forEach { day ->
                            SelectableCell(
                                label = day.toString(),
                                selected = day in selectedDays,
                                onClick = { onSelectedDaysChange(toggleKeepingOne(selectedDays, day)) },
                                modifier = Modifier
                                    .size(CellSize)
                                    .testTag("month_day_$day")
                            )
                        }
                        // Keep the last, shorter row aligned with the columns above.
                        repeat(7 - week.size) { Spacer(modifier = Modifier.size(CellSize)) }
                    }
                }
            }
            if (selectedDays.any { it > 28 }) {
                Text(
                    text = "In shorter months, days that don't exist fall on the last day of the month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val dayWord = if (selectedDays.size == 1) "day" else "days"
            Summary("${RecurrenceLabels.monthInterval(monthInterval)} on $dayWord ${RecurrenceLabels.monthDays(selectedDays)}")
        }
    }
}

/** Every chosen ordinal paired with every chosen weekday, e.g. 1st & 3rd × Mon = 1st Mon, 3rd Mon. */
fun monthWeekdayRules(ordinals: Set<Int>, weekdays: Set<Int>): Set<MonthWeekday> =
    ordinals.flatMap { ordinal -> weekdays.map { MonthWeekday(ordinal, it) } }.toSet()

/** The ordinal of [date]'s weekday within its month: 1..4, or last for the 5th one. */
fun monthWeekdayOrdinalOf(date: AppDate): Int {
    val ordinal = (date.day - 1) / 7 + 1
    return if (ordinal > 4) MonthWeekday.LAST else ordinal
}

private fun toggleKeepingOne(selected: Set<Int>, value: Int): Set<Int> = when {
    value !in selected -> selected + value
    selected.size > 1 -> selected - value
    else -> selected
}

@Composable
private fun MonthIntervalStepper(monthInterval: Int, onMonthIntervalChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Every",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = { onMonthIntervalChange(monthInterval - 1) },
            enabled = monthInterval > 1,
            modifier = Modifier.testTag("month_interval_decrease")
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Fewer months")
        }
        Text(
            text = monthInterval.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("month_interval_value")
        )
        IconButton(
            onClick = { onMonthIntervalChange(monthInterval + 1) },
            enabled = monthInterval < TaskEntity.MAX_RECURRENCE_MONTHS,
            modifier = Modifier.testTag("month_interval_increase")
        ) {
            Icon(Icons.Default.Add, contentDescription = "More months")
        }
        Text(
            text = if (monthInterval == 1) "month" else "months",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SelectableCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    height: Dp = CellSize
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Summary(text: String) {
    Text(
        text = "⚡ $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium
    )
}

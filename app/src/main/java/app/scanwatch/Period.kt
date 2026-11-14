package app.scanwatch

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

enum class Range(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

/** A calendar period: a day, a Monday-based week, a month or a year. */
data class Period(val range: Range, val start: LocalDate) {
    val end: LocalDate = when (range) {
        Range.DAY -> start.plusDays(1)
        Range.WEEK -> start.plusWeeks(1)
        Range.MONTH -> start.plusMonths(1)
        Range.YEAR -> start.plusYears(1)
    }

    /** Days in the period up to and including today. */
    val days: List<LocalDate>
        get() {
            val stop = minOf(end, LocalDate.now().plusDays(1))
            return generateSequence(start) { it.plusDays(1) }.takeWhile { it < stop }.toList()
        }

    fun shift(n: Long) = Period(range, when (range) {
        Range.DAY -> start.plusDays(n)
        Range.WEEK -> start.plusWeeks(n)
        Range.MONTH -> start.plusMonths(n)
        Range.YEAR -> start.plusYears(n)
    })

    val hasNext get() = end <= LocalDate.now()

    fun title(): String {
        val today = LocalDate.now()
        val yr = if (start.year != today.year) " ${start.year}" else ""
        return when (range) {
            Range.DAY -> when (start) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> start.format(DateTimeFormatter.ofPattern("EEE d MMM")) + yr
            }
            Range.WEEK -> {
                val last = end.minusDays(1)
                if (start.month == last.month) "${start.dayOfMonth} – ${last.format(DateTimeFormatter.ofPattern("d MMM"))}$yr"
                else "${start.format(DateTimeFormatter.ofPattern("d MMM"))} – ${last.format(DateTimeFormatter.ofPattern("d MMM"))}$yr"
            }
            Range.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM")) + yr
            Range.YEAR -> start.year.toString()
        }
    }

    companion object {
        fun of(range: Range, day: LocalDate) = Period(range, when (range) {
            Range.DAY -> day
            Range.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Range.MONTH -> day.withDayOfMonth(1)
            Range.YEAR -> day.withDayOfYear(1)
        })
    }
}

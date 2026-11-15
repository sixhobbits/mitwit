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

    val containsToday get() = LocalDate.now().let { it >= start && it < end }

    /** The most recent day of the period that isn't in the future. */
    val latestDay: LocalDate get() = minOf(end.minusDays(1), LocalDate.now())

    /** The same period one year earlier (for weeks: the week containing the same date). */
    fun yearAgo() = of(range, start.minusYears(1))

    val prevLabel get() = when (range) {
        Range.DAY -> "vs day before"; Range.WEEK -> "vs week before"; Range.MONTH -> "vs month before"; Range.YEAR -> "vs year before"
    }
    val yearAgoLabel get() = when (range) {
        Range.DAY -> "vs same day last year"; Range.WEEK -> "vs same week last year"; Range.MONTH -> "vs same month last year"; Range.YEAR -> ""
    }

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

package app.scanwatch

import android.view.Gravity
import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM")
private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
internal val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM")

internal fun hhmm(t: Long, zone: ZoneId) = Instant.ofEpochSecond(t).atZone(zone).format(HHMM)

/** Bar labels: every weekday for a week, every 5th day for a month, months for a year. */
private fun dayLabel(p: Period, d: LocalDate): String? = when (p.range) {
    Range.WEEK -> d.dayOfWeek.name.take(2).lowercase().replaceFirstChar { c -> c.uppercaseChar() }
    else -> if (d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0) d.dayOfMonth.toString() else null
}

/** All day buckets of a week/month, including future days (drawn empty). */
internal fun allDays(p: Period) = generateSequence(p.start) { it.plusDays(1) }.takeWhile { it < p.end }.toList()
internal fun months(p: Period) = (0 until 12).map { YearMonth.of(p.start.year, it + 1) }

/** Whether a rise is good (green), bad (red) or neither (grey). */
enum class Better { HIGHER, LOWER, NEITHER }

/**
 * Rows comparing [value] for [p] with the period before and the same period a year earlier,
 * e.g. "vs week before   ▲ 512 · 6%". Periods without data are skipped.
 */
fun MainActivity.comparison(p: Period, value: (Period) -> Double?, delta: (Double) -> String, better: Better): LinearLayout {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
    val cur = value(p) ?: return box
    val others = buildList {
        add(p.prevLabel to p.shift(-1))
        if (p.range != Range.YEAR) add(p.yearAgoLabel to p.yearAgo())
    }
    for ((name, other) in others) {
        val o = value(other)?.takeIf { it != 0.0 } ?: continue
        val d = cur - o
        val up = d > 0
        val color = when {
            abs(d) < 1e-9 || better == Better.NEITHER -> Pal.SUB
            (better == Better.HIGHER) == up -> Pal.STEPS
            else -> Pal.HR
        }
        val arrow = if (abs(d) < 1e-9) "=" else if (up) "▲" else "▼"
        box.addView(hrow().apply {
            setPadding(0, dp(3), 0, dp(3))
            addView(label(name, 14f, Pal.SUB), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("$arrow ${delta(abs(d))} · ${(abs(d) / abs(o) * 100).roundToInt()}%", 14f, color, MEDIUM))
        })
    }
    return box
}

class ActivityScreen(private val a: MainActivity, private val db: Db) {
    private val goal = 10_000

    /** Steps for a day, or the average per day (over days with data) for longer periods. */
    private fun steps(p: Period): Double? {
        val data = db.days(p.start, p.end).values.filter { it.steps > 0 && it.day <= LocalDate.now() }
        return if (data.isEmpty()) null else data.sumOf { it.steps }.toDouble() / data.size
    }

    private fun compare(p: Period) =
        a.comparison(p, ::steps, { "%,d".format(it.roundToInt()) }, Better.HIGHER)

    fun build(p: Period, out: LinearLayout) = when (p.range) {
        Range.DAY -> day(p.start, out)
        Range.YEAR -> year(p, out)
        else -> span(p, out)
    }

    private fun day(d: LocalDate, out: LinearLayout) {
        val day = db.days(d, d.plusDays(1))[d]
        val steps = day?.steps ?: 0
        out.addView(a.card().apply {
            addView(Ring(a, steps / goal.toFloat(), Pal.STEPS, steps.grouped(), "of ${goal.grouped()} steps"))
            addView(a.hrow().apply {
                setPadding(0, a.dp(8), 0, 0)
                addView(a.tile("%.2f km".format((day?.dist ?: 0.0) / 1000), "Distance"))
                addView(a.tile("${(day?.kcal ?: 0.0).roundToInt()} kcal", "Active calories"))
                addView(a.tile("${(steps * 100 / goal)}%", "Of goal", Pal.STEPS))
            })
            addView(compare(Period.of(Range.DAY, d)).apply { setPadding(0, a.dp(14), 0, 0) })
        })

        val hours = db.hourlySteps(d)
        out.addView(a.card("Steps by hour").apply {
            if (hours == null) addView(a.label("No minute-by-minute data for this day", 14f, Pal.SUB))
            else addView(BarChart(a, hours.mapIndexed { h, v ->
                Bar(listOf(v.toDouble() to Pal.STEPS), if (h % 6 == 0) "%02d".format(h) else null,
                    "%02d:00 · %s steps".format(h, v.grouped()))
            }, heightDp = 180))
        })

        val workouts = db.workouts(db.epoch(d), db.epoch(d.plusDays(1)))
        if (workouts.isNotEmpty()) out.addView(a.card("Workouts").apply {
            for (w in workouts) {
                val bits = buildList {
                    add("${hhmm(w.start, db.zone)}–${hhmm(w.end, db.zone)}")
                    add(hm((w.end - w.start).toInt()))
                    if (w.dist > 0) add("%.1f km".format(w.dist / 1000))
                    if (w.kcal > 0) add("${w.kcal.roundToInt()} kcal")
                }
                addView(a.label(w.type, 16f, Pal.TEXT, MEDIUM).apply { setPadding(0, a.dp(6), 0, 0) })
                addView(a.label(bits.joinToString(" · "), 14f, Pal.SUB).apply { setPadding(0, 0, 0, a.dp(6)) })
            }
        })
    }

    private fun span(p: Period, out: LinearLayout) {
        val data = db.days(p.start, p.end)
        val past = p.days.mapNotNull { data[it] }.filter { it.steps > 0 }
        val avg = if (past.isEmpty()) 0 else past.sumOf { it.steps } / past.size
        out.addView(a.card().apply {
            addView(a.label("Daily average", 13f, Pal.SUB, MEDIUM))
            addView(a.metric(avg.grouped(), "steps"))
            addView(a.label("${past.sumOf { it.steps }.grouped()} steps · %.1f km in total".format(past.sumOf { it.dist } / 1000), 14f, Pal.SUB))
            addView(compare(p))
            val days = allDays(p)
            addView(BarChart(a, days.map { d ->
                val s = data[d]?.steps ?: 0
                Bar(listOf(s.toDouble() to if (s >= goal) Pal.STEPS else Pal.alpha(Pal.STEPS, 0.6f)), dayLabel(p, d),
                    "${d.format(DAY_FMT)} · ${s.grouped()}")
            }, goal = goal.toDouble(), yLabel = ::short) { a.open(Period.of(Range.DAY, days[it])) })
        })
        out.addView(a.card("Summary").apply {
            addView(a.statRow("Goal reached", "${past.count { it.steps >= goal }} of ${p.days.size} days"))
            past.maxByOrNull { it.steps }?.let { addView(a.statRow("Best day", "${it.day.format(DAY_FMT)} · ${it.steps.grouped()}")) }
            addView(a.statRow("Distance", "%.1f km".format(past.sumOf { it.dist } / 1000)))
            addView(a.statRow("Active calories", "${past.sumOf { it.kcal }.roundToInt().grouped()} kcal"))
        })
    }

    private fun year(p: Period, out: LinearLayout) {
        val data = db.days(p.start, p.end).values.filter { it.steps > 0 }
        val byMonth = data.groupBy { YearMonth.from(it.day) }
        val avg = if (data.isEmpty()) 0 else data.sumOf { it.steps } / data.size
        out.addView(a.card().apply {
            addView(a.label("Daily average", 13f, Pal.SUB, MEDIUM))
            addView(a.metric(avg.grouped(), "steps"))
            addView(a.label("${data.sumOf { it.steps }.grouped()} steps · %,.0f km in total".format(data.sumOf { it.dist } / 1000), 14f, Pal.SUB))
            addView(compare(p))
            val months = months(p)
            addView(BarChart(a, months.map { m ->
                val days = byMonth[m].orEmpty()
                val v = if (days.isEmpty()) 0 else days.sumOf { it.steps } / days.size
                Bar(listOf(v.toDouble() to Pal.STEPS), m.format(MONTH_SHORT).take(1), "${m.format(MONTH_SHORT)} · ${v.grouped()}/day")
            }, goal = goal.toDouble(), yLabel = ::short) { a.open(Period.of(Range.MONTH, months[it].atDay(1))) })
        })
        out.addView(a.card("Summary").apply {
            addView(a.statRow("Goal reached", "${data.count { it.steps >= goal }} days"))
            byMonth.maxByOrNull { (_, v) -> v.sumOf { it.steps } / v.size }?.let { (m, v) ->
                addView(a.statRow("Best month", "${m.format(DateTimeFormatter.ofPattern("MMMM"))} · ${(v.sumOf { it.steps } / v.size).grouped()}/day"))
            }
            data.maxByOrNull { it.steps }?.let { addView(a.statRow("Best day", "${it.day.format(DAY_FMT)} · ${it.steps.grouped()}")) }
            addView(a.statRow("Active calories", "${data.sumOf { it.kcal }.roundToInt().grouped()} kcal"))
        })
    }
}

private fun short(v: Double) = if (v >= 1000) "%.0fk".format(v / 1000).replace(".0k", "k") else v.toLong().toString()

class SleepScreen(private val a: MainActivity, private val db: Db) {

    fun build(p: Period, out: LinearLayout) = when (p.range) {
        Range.DAY -> night(p.start, out)
        Range.YEAR -> year(p, out)
        else -> span(p, out)
    }

    private fun night(d: LocalDate, out: LinearLayout) {
        val n = db.mainNights(d, d.plusDays(1))[d]
        if (n == null) {
            out.addView(a.card().apply { addView(a.label("No sleep recorded for the night before ${d.format(DAY_FMT)}", 15f, Pal.SUB)) })
            return
        }
        val inBed = n.asleep + n.awake
        out.addView(a.card().apply {
            addView(a.label("Time asleep", 13f, Pal.SUB, MEDIUM))
            addView(a.metric(hm(n.asleep), ""))
            addView(a.label("${hhmm(n.start, db.zone)} → ${hhmm(n.end, db.zone)} · ${hm(inBed)} in bed", 14f, Pal.SUB))
            addView(compare(Period.of(Range.DAY, d)))
            val stages = db.stages(n.start - 1800, n.end + 60)
            if (stages.isNotEmpty()) addView(Hypnogram(a, stages, db.zone).apply { setPadding(0, a.dp(16), 0, 0) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(14) })
        })
        out.addView(a.card("Sleep stages").apply {
            for ((name, secs, color) in listOf(
                Triple("Deep", n.deep, Pal.DEEP), Triple("Light", n.light, Pal.LIGHT),
                Triple("REM", n.rem, Pal.REM), Triple("Awake", n.awake, Pal.AWAKE),
            )) {
                val frac = if (inBed > 0) secs.toFloat() / inBed else 0f
                addView(a.statRow(name, "${hm(secs)} · ${(frac * 100).roundToInt()}%", color))
                addView(Proportion(a, frac, color))
            }
        })
        out.addView(a.card("Details").apply {
            addView(a.statRow("Wake-ups", n.wakeups.toString()))
            if (n.latency > 0) addView(a.statRow("Time to fall asleep", hm(n.latency)))
            if (n.hrAvg > 0) addView(a.statRow("Heart rate", "${n.hrAvg} bpm avg · ${n.hrMin}–${n.hrMax}"))
        })
    }

    private fun stackOf(n: Night?) = if (n == null) emptyList() else listOf(
        n.deep / 3600.0 to Pal.DEEP, n.light / 3600.0 to Pal.LIGHT, n.rem / 3600.0 to Pal.REM, n.awake / 3600.0 to Pal.AWAKE,
    )

    private fun span(p: Period, out: LinearLayout) {
        val nights = db.mainNights(p.start, p.end)
        val list = nights.values.toList()
        summaryCard(out, p, list, "Average per night") {
            val days = allDays(p)
            addView(BarChart(a, days.map { d ->
                val n = nights[d]
                Bar(stackOf(n), dayLabel(p, d), "${d.format(DAY_FMT)} · ${if (n != null) hm(n.asleep) else "–"}")
            }, goal = 8.0, yLabel = { "${it.toInt()}h" }) { a.open(Period.of(Range.DAY, days[it])) })
        }
        stageCard(out, list)
    }

    private fun year(p: Period, out: LinearLayout) {
        val nights = db.mainNights(p.start, p.end)
        val byMonth = nights.entries.groupBy({ YearMonth.from(it.key) }, { it.value })
        val list = nights.values.toList()
        summaryCard(out, p, list, "Average per night") {
            val months = months(p)
            addView(BarChart(a, months.map { m ->
                val ns = byMonth[m].orEmpty()
                val avg = if (ns.isEmpty()) null else Night(0, 0, ns.sumOf { it.light } / ns.size, ns.sumOf { it.deep } / ns.size,
                    ns.sumOf { it.rem } / ns.size, ns.sumOf { it.awake } / ns.size, 0, 0, 0, 0, 0)
                Bar(stackOf(avg), m.format(MONTH_SHORT).take(1), "${m.format(MONTH_SHORT)} · ${if (avg != null) hm(avg.asleep) else "–"}")
            }, goal = 8.0, yLabel = { "${it.toInt()}h" }) { a.open(Period.of(Range.MONTH, months[it].atDay(1))) })
        }
        stageCard(out, list)
    }

    /** Average time asleep (seconds) of the main nights in a period. */
    private fun asleep(p: Period): Double? =
        db.mainNights(p.start, p.end).values.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.asleep }.toDouble() / l.size }

    private fun compare(p: Period) = a.comparison(p, ::asleep, { hm(it.roundToInt()) }, Better.HIGHER)

    private fun summaryCard(out: LinearLayout, p: Period, list: List<Night>, title: String, chart: LinearLayout.() -> Unit) {
        out.addView(a.card().apply {
            addView(a.label(title, 13f, Pal.SUB, MEDIUM))
            if (list.isEmpty()) {
                addView(a.metric("–", ""))
            } else {
                addView(a.metric(hm(list.sumOf { it.asleep } / list.size), ""))
                addView(a.label("Bedtime ${avgClock(list.map { it.start })} · Wake-up ${avgClock(list.map { it.end })} · ${list.size} nights",
                    14f, Pal.SUB))
                addView(compare(p))
            }
            chart()
            addView(legend())
        })
    }

    private fun stageCard(out: LinearLayout, list: List<Night>) {
        if (list.isEmpty()) return
        out.addView(a.card("Average stages").apply {
            val inBed = list.sumOf { it.asleep + it.awake }.toFloat()
            for ((name, secs, color) in listOf(
                Triple("Deep", list.sumOf { it.deep }, Pal.DEEP), Triple("Light", list.sumOf { it.light }, Pal.LIGHT),
                Triple("REM", list.sumOf { it.rem }, Pal.REM), Triple("Awake", list.sumOf { it.awake }, Pal.AWAKE),
            )) {
                val frac = if (inBed > 0) secs / inBed else 0f
                addView(a.statRow(name, "${hm(secs / list.size)} · ${(frac * 100).roundToInt()}%", color))
                addView(Proportion(a, frac, color))
            }
            addView(a.statRow("Wake-ups per night", "%.1f".format(list.sumOf { it.wakeups }.toDouble() / list.size)))
            list.filter { it.hrAvg > 0 }.takeIf { it.isNotEmpty() }?.let { l ->
                addView(a.statRow("Sleeping heart rate", "${l.sumOf { it.hrAvg } / l.size} bpm"))
            }
        })
    }

    private fun legend() = a.hrow().apply {
        gravity = Gravity.CENTER
        setPadding(0, a.dp(10), 0, 0)
        for ((name, c) in listOf("Deep" to Pal.DEEP, "Light" to Pal.LIGHT, "REM" to Pal.REM, "Awake" to Pal.AWAKE)) {
            addView(a.statRow(name, "", c).apply { setPadding(a.dp(6), 0, a.dp(6), 0) })
        }
    }

    /** Average clock time, treating times as angles so 23:30 and 00:30 average to 00:00. */
    private fun avgClock(ts: List<Long>): String {
        var x = 0.0; var y = 0.0
        for (t in ts) {
            val z = Instant.ofEpochSecond(t).atZone(db.zone)
            val ang = (z.hour * 60 + z.minute) / 1440.0 * 2 * Math.PI
            x += cos(ang); y += sin(ang)
        }
        var mins = (atan2(y, x) / (2 * Math.PI) * 1440).roundToInt()
        if (mins < 0) mins += 1440
        return "%02d:%02d".format(mins / 60, mins % 60)
    }
}


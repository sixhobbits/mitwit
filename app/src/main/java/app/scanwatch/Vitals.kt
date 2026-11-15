package app.scanwatch

import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate

class VitalsScreen(private val a: MainActivity, private val db: Db) {

    /** One vital: how to get a day's samples, per-day bands and a period average. */
    private abstract inner class Metric(val title: String, val unit: String, val color: Int, val decimals: Int, val better: Better) {
        /** Samples for a day view and the time window they're drawn in, or null if none. */
        abstract fun day(d: LocalDate): Triple<List<Point>, Long, Long>?
        abstract fun bands(p: Period): List<Band>
        abstract fun avg(p: Period): Double?
    }

    /** A plain time series table: the day view shows the whole day. */
    private inner class Series(title: String, val table: String, val col: String, unit: String, color: Int, decimals: Int, better: Better) :
        Metric(title, unit, color, decimals, better) {
        override fun day(d: LocalDate): Triple<List<Point>, Long, Long>? {
            val from = db.epoch(d)
            val to = db.epoch(d.plusDays(1))
            return db.points(table, col, from, to).takeIf { it.isNotEmpty() }?.let { Triple(it, from, to) }
        }
        override fun bands(p: Period) = db.bands(table, col, p.start, p.end)
        override fun avg(p: Period) = db.avg(table, col, p.start, p.end)
    }

    /**
     * Heart rate while asleep, as in the Withings app: the day view shows the night ending
     * that morning; longer periods show each night's average with its min–max range.
     */
    private inner class SleepingHr : Metric("Sleeping heart rate", "bpm", Pal.REM, 0, Better.LOWER) {
        override fun day(d: LocalDate): Triple<List<Point>, Long, Long>? {
            val n = db.mainNights(d, d.plusDays(1))[d] ?: return null
            return db.points("hr", "bpm", n.start, n.end).takeIf { it.isNotEmpty() }?.let { Triple(it, n.start, n.end) }
        }
        override fun bands(p: Period) = db.mainNights(p.start, p.end).entries.sortedBy { it.key }
            .filter { it.value.hrAvg > 0 }
            .map { (d, n) -> Band(d, n.hrAvg.toDouble(), n.hrMin.toDouble(), n.hrMax.toDouble()) }
        override fun avg(p: Period) = bands(p).takeIf { it.isNotEmpty() }?.let { b -> b.sumOf { it.avg } / b.size }
    }

    private val metrics = listOf(
        Series("Heart rate", "hr", "bpm", "bpm", Pal.HR, 0, Better.NEITHER),
        SleepingHr(),
        Series("Heart rate variability (RMSSD)", "hrv", "rmssd", "ms", Pal.HRV, 0, Better.HIGHER),
        Series("Body temperature", "temp", "c", "°C", Pal.TEMP, 2, Better.NEITHER),
        Series("Respiratory rate", "rr", "rr", "/min", Pal.RR, 0, Better.NEITHER),
        Series("Blood oxygen (SpO2)", "spo2", "pct", "%", Pal.SPO2, 0, Better.HIGHER),
    )

    fun build(p: Period, out: LinearLayout) {
        for (m in metrics) out.addView(a.card(m.title).apply { if (p.range == Range.DAY) day(m, p, this) else span(m, p, this) })
    }

    private fun fmt(m: Metric, v: Double) = "%.${m.decimals}f".format(v)

    private fun compare(m: Metric, p: Period) =
        a.comparison(p, m::avg, { "${fmt(m, it)} ${m.unit}" }, m.better)

    private fun day(m: Metric, p: Period, c: LinearLayout) {
        val (pts, from, to) = m.day(p.start) ?: run { c.addView(a.label("No data", 14f, Pal.SUB)); return }
        val last = pts.last()
        c.addView(a.metric(fmt(m, pts.sumOf { it.v } / pts.size), m.unit, 32f))
        c.addView(a.label("Average · range ${fmt(m, pts.minOf { it.v })}–${fmt(m, pts.maxOf { it.v })} · last ${fmt(m, last.v)} at ${hhmm(last.t, db.zone)}",
            14f, Pal.SUB))
        c.addView(compare(m, p))
        c.addView(LineChart(a, pts, m.color, from, to, hourTicks(from, to), { "${hhmm(it.t, db.zone)} · ${fmt(m, it.v)} ${m.unit}" }))
    }

    /** Every 6 hours across a whole day, every 2 hours across a night. */
    private fun hourTicks(from: Long, to: Long): List<Pair<Long, String>> {
        val every = if (to - from > 16 * 3600) 6 else 2
        val out = ArrayList<Pair<Long, String>>()
        var t = (from + 3599) / 3600 * 3600
        while (t <= to) {
            val h = Instant.ofEpochSecond(t).atZone(db.zone).hour
            if (h % every == 0) out.add(t to "%02d".format(h))
            t += 3600
        }
        return out
    }

    private fun span(m: Metric, p: Period, c: LinearLayout) {
        val bands = m.bands(p)
        if (bands.isEmpty()) { c.addView(a.label("No data", 14f, Pal.SUB)); return }
        val noon = { d: LocalDate -> db.epoch(d) + 43200 }
        c.addView(a.metric(fmt(m, bands.sumOf { it.avg } / bands.size), m.unit, 32f))
        c.addView(a.label("Average · range ${fmt(m, bands.minOf { it.min })}–${fmt(m, bands.maxOf { it.max })}", 14f, Pal.SUB))
        c.addView(compare(m, p))
        val ticks = when (p.range) {
            Range.WEEK -> allDays(p).map { noon(it) to it.dayOfWeek.name.take(2).lowercase().replaceFirstChar { ch -> ch.uppercaseChar() } }
            Range.MONTH -> allDays(p).filter { it.dayOfMonth == 1 || it.dayOfMonth % 5 == 0 }.map { noon(it) to it.dayOfMonth.toString() }
            else -> months(p).map { db.epoch(it.atDay(15)) to it.format(MONTH_SHORT).take(1) }
        }
        val byT = bands.associateBy { noon(it.day) }
        c.addView(LineChart(
            a, bands.map { Point(noon(it.day), it.avg) }, m.color, db.epoch(p.start), db.epoch(p.end), ticks,
            { pt -> byT[pt.t]!!.let { "${it.day.format(DAY_FMT)} · ${fmt(m, it.avg)} (${fmt(m, it.min)}–${fmt(m, it.max)})" } },
            bands = if (p.range == Range.YEAR) emptyList() else bands.map { Triple(noon(it.day), it.min, it.max) },
            gap = 3 * 86400L,
        ))
    }
}

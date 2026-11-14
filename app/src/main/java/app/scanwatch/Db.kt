package app.scanwatch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A sleep session. Times are Unix seconds, stage totals are seconds. */
data class Night(
    val start: Long, val end: Long,
    val light: Int, val deep: Int, val rem: Int, val awake: Int,
    val wakeups: Int, val latency: Int,
    val hrAvg: Int, val hrMin: Int, val hrMax: Int,
) {
    val asleep get() = light + deep + rem
    /** Nights belong to the day you wake up. */
    fun day(zone: ZoneId): LocalDate = Instant.ofEpochSecond(end).atZone(zone).toLocalDate()
}

data class Day(val day: LocalDate, val steps: Int, val dist: Double, val kcal: Double)
data class Workout(val start: Long, val end: Long, val type: String, val steps: Int, val dist: Double, val kcal: Double, val hrAvg: Int)
data class Stage(val t: Long, val d: Int, val state: Int)
data class Point(val t: Long, val v: Double)
/** Daily summary of a time series: average with min and max. */
data class Band(val day: LocalDate, val avg: Double, val min: Double, val max: Double)

/**
 * Everything lives in one SQLite database in the app's private storage.
 *
 * Minute-level tables (motion, sleep_state, hr, temp, hrv, rr, spo2) are keyed by Unix
 * second. `daily` holds per-day totals (Withings' own aggregates for imported days,
 * recomputed from minute data for synced days) and `night` one row per sleep session.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "scanwatch.db", null, 1) {

    val zone: ZoneId get() = ZoneId.systemDefault()

    override fun onCreate(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE motion(t INTEGER PRIMARY KEY, d INTEGER, steps INTEGER, dist REAL, kcal REAL)",
            "CREATE TABLE sleep_state(t INTEGER PRIMARY KEY, d INTEGER, state INTEGER)",
            "CREATE TABLE hr(t INTEGER PRIMARY KEY, bpm INTEGER)",
            "CREATE TABLE temp(t INTEGER PRIMARY KEY, c REAL)",
            "CREATE TABLE hrv(t INTEGER PRIMARY KEY, rmssd INTEGER, sdnn INTEGER)",
            "CREATE TABLE rr(t INTEGER PRIMARY KEY, rr INTEGER)",
            "CREATE TABLE spo2(t INTEGER PRIMARY KEY, pct REAL)",
            "CREATE TABLE daily(day TEXT PRIMARY KEY, steps INTEGER, dist REAL, kcal REAL)",
            "CREATE TABLE night(start INTEGER, `end` INTEGER, light INTEGER, deep INTEGER, rem INTEGER, awake INTEGER," +
                " wakeups INTEGER, latency INTEGER, hr_avg INTEGER, hr_min INTEGER, hr_max INTEGER, source TEXT," +
                " PRIMARY KEY(start, `end`))",
            "CREATE INDEX night_end ON night(`end`)",
            "CREATE TABLE workout(start INTEGER PRIMARY KEY, `end` INTEGER, type TEXT, steps INTEGER, dist REAL, kcal REAL, hr_avg INTEGER)",
        ).forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block(db)
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun newest(table: String): Long =
        readableDatabase.rawQuery("SELECT MAX(t) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun count(table: String): Pair<Long, LongArray> =
        readableDatabase.rawQuery("SELECT COUNT(*), MIN(t), MAX(t) FROM $table", null).use {
            it.moveToFirst(); it.getLong(0) to longArrayOf(it.getLong(1), it.getLong(2))
        }

    fun epoch(day: LocalDate): Long = day.atStartOfDay(zone).toEpochSecond()

    // ---- Activity ----

    fun days(from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Day> {
        val out = HashMap<LocalDate, Day>()
        readableDatabase.rawQuery(
            "SELECT day, steps, dist, kcal FROM daily WHERE day >= ? AND day < ?",
            arrayOf(from.toString(), toExclusive.toString()),
        ).use {
            while (it.moveToNext()) {
                val d = LocalDate.parse(it.getString(0))
                out[d] = Day(d, it.getInt(1), it.getDouble(2), it.getDouble(3))
            }
        }
        return out
    }

    /** Steps per hour (24 values) for one day; null if there's no minute data that day. */
    fun hourlySteps(day: LocalDate): IntArray? {
        val start = epoch(day)
        val out = IntArray(24)
        var any = false
        readableDatabase.rawQuery(
            "SELECT t, steps FROM motion WHERE t >= ? AND t < ? AND steps > 0",
            arrayOf(start.toString(), epoch(day.plusDays(1)).toString()),
        ).use {
            while (it.moveToNext()) {
                val h = Instant.ofEpochSecond(it.getLong(0)).atZone(zone).hour
                out[h] += it.getInt(1)
                any = true
            }
        }
        return if (any) out else null
    }

    fun workouts(from: Long, to: Long): List<Workout> {
        val out = ArrayList<Workout>()
        readableDatabase.rawQuery(
            "SELECT start, `end`, type, steps, dist, kcal, hr_avg FROM workout WHERE start >= ? AND start < ? ORDER BY start",
            arrayOf(from.toString(), to.toString()),
        ).use {
            while (it.moveToNext()) out.add(Workout(it.getLong(0), it.getLong(1), it.getString(2), it.getInt(3), it.getDouble(4), it.getDouble(5), it.getInt(6)))
        }
        return out
    }

    // ---- Sleep ----

    /** All sleep sessions that end in [from, to), in order. */
    fun nights(from: Long, to: Long): List<Night> {
        val out = ArrayList<Night>()
        readableDatabase.rawQuery(
            "SELECT start, `end`, light, deep, rem, awake, wakeups, latency, hr_avg, hr_min, hr_max FROM night" +
                " WHERE `end` >= ? AND `end` < ? ORDER BY `end`",
            arrayOf(from.toString(), to.toString()),
        ).use {
            while (it.moveToNext()) out.add(Night(
                it.getLong(0), it.getLong(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getInt(5),
                it.getInt(6), it.getInt(7), it.getInt(8), it.getInt(9), it.getInt(10),
            ))
        }
        return out
    }

    /** The main (longest) sleep for each wake-up day in [from, toExclusive). */
    fun mainNights(from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Night> =
        nights(epoch(from), epoch(toExclusive)).groupBy { it.day(zone) }.mapValues { (_, v) -> v.maxBy { it.asleep } }

    fun stages(from: Long, to: Long): List<Stage> {
        val out = ArrayList<Stage>()
        readableDatabase.rawQuery(
            "SELECT t, d, state FROM sleep_state WHERE t >= ? AND t < ? ORDER BY t",
            arrayOf(from.toString(), to.toString()),
        ).use { while (it.moveToNext()) out.add(Stage(it.getLong(0), it.getInt(1), it.getInt(2))) }
        return out
    }

    // ---- Vitals ----

    fun points(table: String, col: String, from: Long, to: Long): List<Point> {
        val out = ArrayList<Point>()
        readableDatabase.rawQuery(
            "SELECT t, $col FROM $table WHERE t >= ? AND t < ? AND $col > 0 ORDER BY t",
            arrayOf(from.toString(), to.toString()),
        ).use { while (it.moveToNext()) out.add(Point(it.getLong(0), it.getDouble(1))) }
        return out
    }

    fun bands(table: String, col: String, from: LocalDate, toExclusive: LocalDate): List<Band> {
        val out = ArrayList<Band>()
        readableDatabase.rawQuery(
            "SELECT date(t, 'unixepoch', 'localtime') d, AVG($col), MIN($col), MAX($col) FROM $table" +
                " WHERE t >= ? AND t < ? AND $col > 0 GROUP BY d ORDER BY d",
            arrayOf(epoch(from).toString(), epoch(toExclusive).toString()),
        ).use {
            while (it.moveToNext()) out.add(Band(LocalDate.parse(it.getString(0)), it.getDouble(1), it.getDouble(2), it.getDouble(3)))
        }
        return out
    }

    // ---- Derived tables after a watch sync ----

    /** Recomputes daily totals from minute data from [fromT] on; never lowers an existing total. */
    fun recomputeDaily(fromT: Long) = tx { db ->
        val start = epoch(Instant.ofEpochSecond(fromT).atZone(zone).toLocalDate())
        db.execSQL(
            "INSERT INTO daily(day, steps, dist, kcal)" +
                " SELECT date(t, 'unixepoch', 'localtime') dd, SUM(steps), SUM(dist), SUM(kcal) FROM motion WHERE t >= ? GROUP BY dd" +
                " ON CONFLICT(day) DO UPDATE SET steps = MAX(steps, excluded.steps), dist = MAX(dist, excluded.dist), kcal = MAX(kcal, excluded.kcal)",
            arrayOf(start),
        )
    }

    /**
     * Rebuilds watch-derived sleep sessions from sleep_state from [fromT] on. Sessions are
     * runs of stage records with gaps under [SESSION_GAP]; leading and trailing awake time
     * is trimmed. Sessions that overlap an imported Withings night are left to that night.
     */
    fun recomputeNights(fromT: Long) {
        val from = fromT - 12 * 3600
        val stages = stages(from, Long.MAX_VALUE)
        val sessions = ArrayList<List<Stage>>()
        var cur = ArrayList<Stage>()
        for (s in stages) {
            val last = cur.lastOrNull()
            if (last != null && s.t - (last.t + last.d) > SESSION_GAP) {
                sessions.add(cur); cur = ArrayList()
            }
            cur.add(s)
        }
        if (cur.isNotEmpty()) sessions.add(cur)

        tx { db ->
            db.execSQL("DELETE FROM night WHERE source = 'watch' AND `end` >= ?", arrayOf(from))
            for (raw in sessions) {
                val first = raw.indexOfFirst { it.state != 0 }
                val last = raw.indexOfLast { it.state != 0 }
                if (first < 0) continue
                val s = raw.subList(first, last + 1)
                val secs = IntArray(4)
                var wakeups = 0
                for ((i, st) in s.withIndex()) {
                    if (st.state in 0..3) secs[st.state] += st.d
                    if (st.state == 0 && i > 0 && s[i - 1].state != 0) wakeups++
                }
                if (secs[1] + secs[2] + secs[3] < MIN_SLEEP) continue
                val start = s.first().t
                val end = s.last().t + s.last().d
                val overlaps = db.rawQuery(
                    "SELECT 1 FROM night WHERE source = 'import' AND start < ? AND `end` > ? LIMIT 1",
                    arrayOf(end.toString(), start.toString()),
                ).use { it.moveToFirst() }
                if (overlaps) continue
                val hr = db.rawQuery("SELECT AVG(bpm), MIN(bpm), MAX(bpm) FROM hr WHERE t >= ? AND t < ? AND bpm > 0",
                    arrayOf(start.toString(), end.toString())).use { it.moveToFirst(); intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2)) }
                db.execSQL(
                    "INSERT OR REPLACE INTO night VALUES(?,?,?,?,?,?,?,?,?,?,?,'watch')",
                    arrayOf(start, end, secs[1], secs[2], secs[3], secs[0], wakeups, 0, hr[0], hr[1], hr[2]),
                )
            }
        }
    }

    companion object {
        const val SESSION_GAP = 60 * 60
        const val MIN_SLEEP = 30 * 60
    }
}

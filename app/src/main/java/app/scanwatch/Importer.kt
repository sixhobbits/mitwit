package app.scanwatch

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Imports a Withings / Health Mate data export (the "data_XXX_NNN.zip" you can download
 * from the Withings account settings) into the local database. Re-importing is safe:
 * rows are upserted by timestamp.
 */
class Importer(private val db: Db, private val progress: (String) -> Unit) {

    fun run(input: InputStream) {
        var rows = 0L
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val handler = handlers[name] ?: continue
                val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8), 1 shl 16)
                val n = db.tx { handler(it, Csv(reader)) }
                rows += n
                progress("${name.removeSuffix(".csv")}: %,d".format(n))
            }
        }
        progress("Import finished: $rows rows")
    }

    private val handlers: Map<String, (SQLiteDatabase, Csv) -> Long> = mapOf(
        "raw_tracker_steps.csv" to series("INSERT INTO motion(t, d, steps) VALUES(?,?,?) ON CONFLICT(t) DO UPDATE SET steps = excluded.steps, d = excluded.d"),
        "raw_tracker_distance.csv" to series("INSERT INTO motion(t, d, dist) VALUES(?,?,?) ON CONFLICT(t) DO UPDATE SET dist = excluded.dist, d = excluded.d"),
        "raw_tracker_calories-earned.csv" to series("INSERT INTO motion(t, d, kcal) VALUES(?,?,?) ON CONFLICT(t) DO UPDATE SET kcal = excluded.kcal, d = excluded.d"),
        "raw_tracker_sleep-state.csv" to series("INSERT OR REPLACE INTO sleep_state(t, d, state) VALUES(?,?,?)"),
        "raw_hr_hr.csv" to series("INSERT OR REPLACE INTO hr(t, bpm) VALUES(?,?)", withDuration = false),
        "raw_core_body_temperature_Core body temperature.csv" to series("INSERT OR REPLACE INTO temp(t, c) VALUES(?,?)", withDuration = false),
        "raw_heart_rate_variability_HR RMS SD.csv" to series("INSERT INTO hrv(t, rmssd) VALUES(?,?) ON CONFLICT(t) DO UPDATE SET rmssd = excluded.rmssd", withDuration = false),
        "raw_heart_rate_variability_HR SD NN.csv" to series("INSERT INTO hrv(t, sdnn) VALUES(?,?) ON CONFLICT(t) DO UPDATE SET sdnn = excluded.sdnn", withDuration = false),
        "raw_import_Respiratory rate.csv" to series("INSERT OR REPLACE INTO rr(t, rr) VALUES(?,?)", withDuration = false),
        "manual_spo2.csv" to ::spo2,
        "aggregates_steps.csv" to aggregate("steps"),
        "aggregates_distance.csv" to aggregate("dist"),
        "aggregates_calories_earned.csv" to aggregate("kcal"),
        "sleep.csv" to ::sleep,
        "activities.csv" to ::activities,
    )

    /** "start,duration,value" files: start time plus arrays of durations and values. */
    private fun series(sql: String, withDuration: Boolean = true) = { db: SQLiteDatabase, csv: Csv ->
        val st = db.compileStatement(sql)
        var n = 0L
        csv.next() // header
        while (true) {
            val row = csv.next() ?: break
            if (row.size < 3) continue
            var t = OffsetDateTime.parse(row[0]).toEpochSecond()
            val durs = numbers(row[1])
            val vals = numbers(row[2])
            for (i in vals.indices) {
                val d = durs.getOrElse(i) { 60.0 }.toLong()
                st.clearBindings()
                st.bindLong(1, t)
                if (withDuration) {
                    st.bindLong(2, d)
                    st.bindDouble(3, vals[i])
                } else {
                    st.bindDouble(2, vals[i])
                }
                st.executeInsert()
                n++
                t += d
            }
        }
        n
    }

    private fun aggregate(col: String) = { db: SQLiteDatabase, csv: Csv ->
        val st = db.compileStatement("INSERT INTO daily(day, $col) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET $col = excluded.$col")
        var n = 0L
        csv.next()
        while (true) {
            val row = csv.next() ?: break
            if (row.size < 2) continue
            st.bindString(1, row[0]); st.bindDouble(2, row[1].toDouble()); st.executeInsert(); n++
        }
        n
    }

    private fun spo2(db: SQLiteDatabase, csv: Csv): Long {
        val st = db.compileStatement("INSERT OR REPLACE INTO spo2(t, pct) VALUES(?,?)")
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        var n = 0L
        csv.next()
        while (true) {
            val row = csv.next() ?: break
            if (row.size < 2) continue
            val t = LocalDateTime.parse(row[0], fmt).atZone(this@Importer.db.zone).toEpochSecond()
            st.bindLong(1, t); st.bindDouble(2, row[1].toDouble()); st.executeInsert(); n++
        }
        return n
    }

    /** sleep.csv: from, to, light, deep, rem, awake, wake ups, time to sleep, …, avg/min/max HR. */
    private fun sleep(db: SQLiteDatabase, csv: Csv): Long {
        val st = db.compileStatement("INSERT OR REPLACE INTO night VALUES(?,?,?,?,?,?,?,?,?,?,?,'import')")
        var n = 0L
        csv.next()
        while (true) {
            val r = csv.next() ?: break
            if (r.size < 14) continue
            st.bindLong(1, OffsetDateTime.parse(r[0]).toEpochSecond())
            st.bindLong(2, OffsetDateTime.parse(r[1]).toEpochSecond())
            for ((col, idx) in listOf(3 to 2, 4 to 3, 5 to 4, 6 to 5, 7 to 6, 8 to 7, 9 to 11, 10 to 12, 11 to 13)) {
                st.bindLong(col, r[idx].toDoubleOrNull()?.toLong() ?: 0L)
            }
            st.executeInsert(); n++
        }
        return n
    }

    /** activities.csv: from, to, …, type, Data (JSON with steps, distance, calories, hr_average). */
    private fun activities(db: SQLiteDatabase, csv: Csv): Long {
        val st: SQLiteStatement = db.compileStatement("INSERT OR REPLACE INTO workout VALUES(?,?,?,?,?,?,?)")
        var n = 0L
        csv.next()
        while (true) {
            val r = csv.next() ?: break
            if (r.size < 7) continue
            val data = runCatching { JSONObject(r[6]) }.getOrElse { JSONObject() }
            st.bindLong(1, OffsetDateTime.parse(r[0]).toEpochSecond())
            st.bindLong(2, OffsetDateTime.parse(r[1]).toEpochSecond())
            st.bindString(3, r[5])
            st.bindLong(4, data.optLong("steps"))
            st.bindDouble(5, data.optDouble("distance", 0.0))
            st.bindDouble(6, data.optDouble("calories", 0.0))
            st.bindLong(7, data.optLong("hr_average"))
            st.executeInsert(); n++
        }
        return n
    }

    private fun numbers(s: String): DoubleArray {
        val inner = s.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return DoubleArray(0)
        val parts = inner.split(',')
        return DoubleArray(parts.size) { parts[it].trim().toDoubleOrNull() ?: 0.0 }
    }
}

/** Minimal RFC 4180 reader: quoted fields may contain commas, doubled quotes and newlines. */
class Csv(private val r: BufferedReader) {
    fun next(): List<String>? {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var c = r.read()
        if (c < 0) return null
        while (c >= 0) {
            val ch = c.toChar()
            if (quoted) {
                if (ch == '"') {
                    r.mark(1)
                    if (r.read() == '"'.code) sb.append('"') else { r.reset(); quoted = false }
                } else sb.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { out.add(sb.toString()); sb.setLength(0) }
                '\r' -> {}
                '\n' -> break
                else -> sb.append(ch)
            }
            c = r.read()
        }
        out.add(sb.toString())
        return out
    }
}

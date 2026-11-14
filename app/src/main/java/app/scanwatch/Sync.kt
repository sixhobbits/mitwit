package app.scanwatch

import java.nio.ByteBuffer

/**
 * One sync: authenticate, read battery and the time-series ("vasistas") data, then SYNC_OK.
 *
 * Read-only towards the watch: it never deletes stored measures, ECGs or events, and
 * never changes watch settings, so the Withings app (if still used) sees everything.
 * Each sync re-reads from the newest timestamp already stored here.
 */
class Sync(
    private val client: WatchClient,
    private val db: Db,
    private val setMeta: (String, String) -> Unit,
    private val log: (String) -> Unit,
) {
    /** One decoded record: start time, duration and named values. */
    private class Rec(val t: Long) {
        var d = 60L
        val v = HashMap<String, Number>()
    }

    fun run(address: String, secret: String) {
        if (secret.isEmpty() || address.isEmpty()) throw WatchException("Watch address/secret missing (secrets.properties)")
        client.connect(address, secret)
        val since: Long
        try {
            client.authenticate(secret)

            val bat = client.request(Frame(Cmd.BATTERY)).first(Obj.BATTERY_STATUS)?.buf()
            if (bat != null) {
                val pct = u8(bat)
                setMeta("battery", pct.toString())
                log("Battery $pct%")
            }

            since = start(maxOf(db.newest("motion"), db.newest("sleep_state")))
            val motion = fetch(Frame(Cmd.WAM_VASISTAS_GET, listOf(get(since), Tlv(Obj.VERSION, byteArrayOf(3)))))
            val hr = fetch(Frame(Cmd.BODY_VASISTAS_GET, listOf(get(start(db.newest("hr"))))))
            val vitals = HashMap<String, List<Rec>>()
            for ((table, type) in listOf("spo2" to 8, "temp" to 10, "hrv" to 11, "rr" to 12)) {
                try {
                    vitals[table] = fetch(Frame(Cmd.VASISTAS_GET, listOf(get(start(db.newest(table))), vasistasType(type))))
                } catch (e: WatchException) {
                    log("$table: ${e.message}")
                }
            }
            client.request(Frame(Cmd.SYNC_OK))

            save(motion, hr, vitals)
        } finally {
            client.disconnect()
        }
        db.recomputeDaily(since)
        db.recomputeNights(since)
        setMeta("lastSync", (System.currentTimeMillis() / 1000).toString())
        log("Sync complete")
    }

    private fun save(motion: List<Rec>, hr: List<Rec>, vitals: Map<String, List<Rec>>) = db.tx { s ->
        val m = s.compileStatement(
            "INSERT INTO motion(t, d, steps, dist, kcal) VALUES(?,?,?,?,?) ON CONFLICT(t) DO UPDATE SET d = excluded.d," +
                " steps = COALESCE(excluded.steps, steps), dist = COALESCE(excluded.dist, dist), kcal = COALESCE(excluded.kcal, kcal)",
        )
        val sl = s.compileStatement("INSERT OR REPLACE INTO sleep_state(t, d, state) VALUES(?,?,?)")
        var nm = 0; var ns = 0
        for (r in motion) {
            if (r.v.keys.any { it in setOf("steps", "dist", "kcal") }) {
                m.clearBindings()
                m.bindLong(1, r.t); m.bindLong(2, r.d)
                r.v["steps"]?.let { m.bindLong(3, it.toLong()) }
                r.v["dist"]?.let { m.bindDouble(4, it.toDouble()) }
                r.v["kcal"]?.let { m.bindDouble(5, it.toDouble()) }
                m.executeInsert(); nm++
            }
            r.v["sleep"]?.let {
                sl.bindLong(1, r.t); sl.bindLong(2, r.d); sl.bindLong(3, it.toLong()); sl.executeInsert(); ns++
            }
        }
        log("motion: $nm, sleep: $ns records")

        fun simple(table: String, sql: String, recs: List<Rec>, vararg keys: String) {
            val st = s.compileStatement(sql)
            var n = 0
            for (r in recs) {
                if (keys.any { r.v[it] == null }) continue
                st.clearBindings()
                st.bindLong(1, r.t)
                keys.forEachIndexed { i, k -> st.bindDouble(i + 2, r.v[k]!!.toDouble()) }
                st.executeInsert(); n++
            }
            log("$table: $n records")
        }
        simple("hr", "INSERT OR REPLACE INTO hr(t, bpm) VALUES(?,?)", hr, "bpm")
        vitals["spo2"]?.let { simple("spo2", "INSERT OR REPLACE INTO spo2(t, pct) VALUES(?,?)", it, "spo2") }
        vitals["temp"]?.let { simple("temp", "INSERT OR REPLACE INTO temp(t, c) VALUES(?,?)", it, "temp") }
        vitals["hrv"]?.let { simple("hrv", "INSERT OR REPLACE INTO hrv(t, rmssd, sdnn) VALUES(?,?,?)", it, "rmssd", "sdnn") }
        vitals["rr"]?.let { simple("rr", "INSERT OR REPLACE INTO rr(t, rr) VALUES(?,?)", it, "rr") }
    }

    /** Everything after the newest stored record; the last [FIRST_SYNC_DAYS] days if there is none. */
    private fun start(newest: Long): Long =
        if (newest > 0) newest + 1 else System.currentTimeMillis() / 1000 - FIRST_SYNC_DAYS * 86400

    /** WamVasistasGet{utcStart u32, max u16}. */
    private fun get(start: Long) =
        Tlv(Obj.WAM_VASISTAS_GET, ByteBuffer.allocate(6).putInt(start.toInt()).putShort(0).array())

    private fun vasistasType(t: Int) = Tlv(Obj.VASISTAS_TYPE, ByteBuffer.allocate(4).putInt(t).array())

    private fun fetch(req: Frame): List<Rec> {
        val records = ArrayList<Rec>()
        for (frame in client.requestList(req)) {
            var cur: Rec? = null
            for (t in frame.tlvs) {
                if (t.type == Obj.VASISTAS_HEAD) {
                    cur = Rec(u32(t.buf())).also { records.add(it) }
                    continue
                }
                decode(t, cur ?: continue)
            }
        }
        return records.filter { it.v.isNotEmpty() }
    }

    private fun decode(t: Tlv, r: Rec) {
        val b = t.buf()
        when (t.type) {
            Obj.VASISTAS_DURATION -> u16(b).let { if (it > 0) r.d = it.toLong() }
            Obj.VASISTAS_AWAKE -> {
                r.v["steps"] = u16(b)
                r.v["dist"] = u32(b) / 100.0
            }
            Obj.VASISTAS_METCAL_EARNED -> r.v["kcal"] = u16(b) / 100.0
            Obj.VASISTAS_SLEEP -> r.v["sleep"] = u16(b)
            Obj.ACTIVITY_TYPE -> {
                val sub = u16(b)
                val level = u16(b)
                if (sub == SLEEP_SUBCATEGORY) r.v["sleep"] = level
            }
            Obj.HEARTRATE -> u8(b).let { if (it > 0) r.v["bpm"] = it }
            Obj.SPO2 -> {
                val v = u16(b)
                u16(b)
                val err = u16(b)
                if (err == 0 && v > 0) r.v["spo2"] = v / 10.0
            }
            Obj.TEMPERATURE -> {
                u8(b)
                val attrib = u8(b)
                val c = b.int / 1000.0
                if (attrib != TEMP_BASELINE && c in 30.0..45.0) r.v["temp"] = c
            }
            Obj.HRV -> {
                u8(b)
                val sdnn = u16(b)
                val rmssd = u16(b)
                if (rmssd > 0) { r.v["rmssd"] = rmssd; r.v["sdnn"] = sdnn }
            }
            Obj.RESP_RATE -> u16(b).let { if (it > 0) r.v["rr"] = it }
        }
    }

    companion object {
        const val FIRST_SYNC_DAYS = 7L
        const val SLEEP_SUBCATEGORY = 37
        const val TEMP_BASELINE = 5
    }
}

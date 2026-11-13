package app.scanwatch

import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * One sync: authenticate, read battery and the time-series ("vasistas") data, then SYNC_OK.
 *
 * Read-only towards the watch: it never deletes stored measures, ECGs or events, and
 * never changes watch settings, so the Withings app (if still used) sees everything.
 * Time-series data is never deleted on the watch; each sync re-reads from the newest
 * timestamp already stored here.
 */
class Sync(private val client: WatchClient, private val store: Store, private val log: (String) -> Unit) {

    fun run(secret: String) {
        if (secret.isEmpty()) throw WatchException("No watch secret configured (secrets.properties)")
        client.connect()
        try {
            client.authenticate(secret)

            val bat = client.request(Frame(Cmd.BATTERY)).first(Obj.BATTERY_STATUS)?.buf()
            if (bat != null) {
                val pct = u8(bat)
                store.setMeta("battery", pct.toString())
                log("Battery $pct%")
            }

            fetch("motion", Frame(Cmd.WAM_VASISTAS_GET, listOf(get("motion"), Tlv(Obj.VERSION, byteArrayOf(3)))))
            fetch("hr", Frame(Cmd.BODY_VASISTAS_GET, listOf(get("hr"))))
            for ((kind, type) in listOf("spo2" to 8, "temp" to 10, "hrv" to 11, "rr" to 12)) {
                try {
                    fetch(kind, Frame(Cmd.VASISTAS_GET, listOf(get(kind), vasistasType(type))))
                } catch (e: WatchException) {
                    log("$kind: ${e.message}")
                }
            }

            client.request(Frame(Cmd.SYNC_OK))
            store.setMeta("lastSync", (System.currentTimeMillis() / 1000).toString())
            log("Sync complete")
        } finally {
            client.disconnect()
        }
    }

    /** WamVasistasGet{utcStart u32, max u16}: everything after the newest stored record. */
    private fun get(kind: String): Tlv {
        val floor = System.currentTimeMillis() / 1000 - FIRST_SYNC_DAYS * 86400
        val start = maxOf(store.newest(kind) + 1, floor)
        return Tlv(Obj.WAM_VASISTAS_GET, ByteBuffer.allocate(6).putInt(start.toInt()).putShort(0).array())
    }

    private fun vasistasType(t: Int) = Tlv(Obj.VASISTAS_TYPE, ByteBuffer.allocate(4).putInt(t).array())

    private fun fetch(kind: String, req: Frame) {
        val records = ArrayList<JSONObject>()
        for (frame in client.requestList(req)) {
            var cur: JSONObject? = null
            for (t in frame.tlvs) {
                if (t.type == Obj.VASISTAS_HEAD) {
                    cur = JSONObject().put("t", u32(t.buf())).put("d", 60)
                    records.add(cur)
                    continue
                }
                val r = cur ?: continue
                decode(t, r)
            }
        }
        val kept = records.filter { it.length() > 2 }
        store.add(kind, kept)
        log("$kind: ${kept.size} records")
    }

    private fun decode(t: Tlv, r: JSONObject) {
        val b = t.buf()
        when (t.type) {
            Obj.VASISTAS_DURATION -> u16(b).let { if (it > 0) r.put("d", it) }
            Obj.VASISTAS_AWAKE -> {
                r.put("steps", u16(b))
                r.put("dist", u32(b) / 100.0)
            }
            Obj.VASISTAS_METCAL_EARNED -> r.put("kcal", u16(b) / 100.0)
            Obj.VASISTAS_SLEEP -> r.put("sleep", u16(b))
            Obj.ACTIVITY_TYPE -> {
                val sub = u16(b)
                val level = u16(b)
                if (sub == SLEEP_SUBCATEGORY) r.put("sleep", level)
            }
            Obj.HEARTRATE -> {
                val bpm = u8(b)
                if (bpm > 0) r.put("bpm", bpm)
            }
            Obj.SPO2 -> {
                val v = u16(b)
                u16(b)
                val err = u16(b)
                if (err == 0 && v > 0) r.put("spo2", v / 10.0)
            }
            Obj.TEMPERATURE -> {
                u8(b)
                val attrib = u8(b)
                val c = b.int / 1000.0
                if (attrib != TEMP_BASELINE && c in 30.0..45.0) r.put("temp", c)
            }
            Obj.HRV -> {
                u8(b)
                val sdnn = u16(b)
                val rmssd = u16(b)
                if (rmssd > 0) r.put("rmssd", rmssd).put("sdnn", sdnn)
            }
            Obj.RESP_RATE -> u16(b).let { if (it > 0) r.put("rr", it) }
        }
    }

    companion object {
        const val FIRST_SYNC_DAYS = 7L
        const val SLEEP_SUBCATEGORY = 37
        const val TEMP_BASELINE = 5
    }
}

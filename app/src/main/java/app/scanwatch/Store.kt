package app.scanwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TreeMap

/**
 * Local storage in the app's private files: one JSON file per data kind, keyed by
 * timestamp (Unix seconds). Records older than [KEEP_DAYS] are dropped.
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val meta = context.getSharedPreferences("meta", Context.MODE_PRIVATE)

    @Synchronized
    fun load(kind: String): TreeMap<Long, JSONObject> {
        val map = TreeMap<Long, JSONObject>()
        val f = File(dir, "$kind.json")
        if (!f.exists()) return map
        val arr = JSONArray(f.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            map[o.getLong("t")] = o
        }
        return map
    }

    @Synchronized
    fun add(kind: String, records: List<JSONObject>) {
        val map = load(kind)
        for (r in records) {
            val t = r.getLong("t")
            val existing = map[t]
            if (existing == null) map[t] = r
            else r.keys().forEach { existing.put(it, r.get(it)) }
        }
        val cutoff = System.currentTimeMillis() / 1000 - KEEP_DAYS * 86400
        map.headMap(cutoff).clear()
        val arr = JSONArray()
        map.values.forEach { arr.put(it) }
        File(dir, "$kind.json").writeText(arr.toString())
    }

    fun newest(kind: String): Long = load(kind).lastEntry()?.key ?: 0L

    fun setMeta(key: String, value: String) = meta.edit().putString(key, value).apply()
    fun meta(key: String): String? = meta.getString(key, null)

    companion object {
        const val KEEP_DAYS = 365L
    }
}

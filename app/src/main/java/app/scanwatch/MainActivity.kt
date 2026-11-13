package app.scanwatch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var syncButton: Button
    private lateinit var status: TextView
    private val logLines = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom)
            insets
        }

        syncButton = Button(this).apply {
            text = "Sync watch"
            setOnClickListener { startSync() }
        }
        status = TextView(this).apply { setTextColor(Color.GRAY) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(syncButton)
        root.addView(status)
        root.addView(content)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
        // `adb shell am start -n app.scanwatch/.MainActivity --ez sync true` starts a sync.
        if (intent.getBooleanExtra("sync", false)) startSync()
    }

    private fun startSync() {
        val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(perms, 1)
            return
        }
        syncButton.isEnabled = false
        logLines.clear()
        Thread {
            val log: (String) -> Unit = { s ->
                android.util.Log.i("ScanWatchLocal", s)
                main.post { logLines.add(s); status.text = s }
            }
            try {
                Sync(WatchClient(this, log), store, log).run(BuildConfig.WATCH_ADDRESS, BuildConfig.WATCH_SECRET)
            } catch (e: Exception) {
                log("Sync failed: ${e.message}")
            }
            main.post { syncButton.isEnabled = true; render() }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startSync()
    }

    private fun render() {
        content.removeAllViews()
        val now = System.currentTimeMillis() / 1000
        val dayAgo = now - 86400

        val last = store.meta("lastSync")?.toLongOrNull()
        val battery = store.meta("battery")
        val info = buildList {
            add(if (last != null) "Last sync ${time.format(Date(last * 1000))}" else "Never synced")
            if (battery != null) add("Battery $battery%")
        }.joinToString(" · ")
        content.addView(text(info, 13f, Color.GRAY))

        // Today
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis / 1000
        val motion = store.load("motion")
        val today = motion.tailMap(midnight).values
        val steps = today.sumOf { it.optInt("steps") }
        val km = today.sumOf { it.optDouble("dist", 0.0) } / 1000
        val kcal = today.sumOf { it.optDouble("kcal", 0.0) }
        header("Today")
        content.addView(text("%,d steps · %.2f km · %.0f active kcal".format(steps, km, kcal), 18f))

        // Heart rate
        val hr = store.load("hr").tailMap(dayAgo).values.filter { it.has("bpm") }
        header("Heart rate (24 h)")
        if (hr.isNotEmpty()) {
            val l = hr.last()
            content.addView(text("${l.getInt("bpm")} bpm at ${time.format(Date(l.getLong("t") * 1000))} · " +
                "range ${hr.minOf { it.getInt("bpm") }}–${hr.maxOf { it.getInt("bpm") }}", 16f))
            content.addView(LineChart(this, hr.map { it.getLong("t") to it.getDouble("bpm") }, Color.parseColor("#E53935")))
        } else content.addView(text("No data", 14f, Color.GRAY))

        // Sleep
        header("Last sleep")
        renderSleep(motion.tailMap(now - 30 * 3600).values.filter { it.has("sleep") })

        // Vitals
        series("Skin temperature (24 h)", "temp", "°C", dayAgo, Color.parseColor("#FB8C00"))
        series("SpO2 (24 h)", "spo2", "%", dayAgo, Color.parseColor("#1E88E5"))
        series("HRV RMSSD (7 days)", "rmssd", "ms", now - 7 * 86400, Color.parseColor("#43A047"), kind = "hrv")
        series("Respiratory rate (7 days)", "rr", "/min", now - 7 * 86400, Color.parseColor("#8E24AA"))

        if (logLines.isNotEmpty()) {
            header("Sync log")
            content.addView(text(logLines.joinToString("\n"), 12f, Color.GRAY).apply { typeface = Typeface.MONOSPACE })
        }
    }

    private fun renderSleep(records: List<JSONObject>) {
        if (records.isEmpty()) {
            content.addView(text("No data", 14f, Color.GRAY)); return
        }
        // Take the most recent session: walk back from the end until a gap of more than 2 hours.
        val session = ArrayList<JSONObject>()
        for (r in records.reversed()) {
            if (session.isNotEmpty() && session.last().getLong("t") - r.getLong("t") > 7200) break
            session.add(r)
        }
        session.reverse()
        val mins = IntArray(4)
        for (r in session) {
            val s = r.getInt("sleep")
            if (s in 0..3) mins[s] += r.getInt("d") / 60
        }
        val asleep = mins[1] + mins[2] + mins[3]
        content.addView(text("%dh %02dm asleep".format(asleep / 60, asleep % 60), 18f))
        content.addView(text("Deep ${hm(mins[2])} · Light ${hm(mins[1])} · REM ${hm(mins[3])} · Awake ${hm(mins[0])}", 14f))
        content.addView(Hypnogram(this, session.map { Triple(it.getLong("t"), it.getInt("d"), it.getInt("sleep")) }))
    }

    private fun series(title: String, field: String, unit: String, since: Long, color: Int, kind: String = field) {
        val pts = store.load(kind).tailMap(since).values.filter { it.has(field) }
            .map { it.getLong("t") to it.getDouble(field) }
        header(title)
        if (pts.isEmpty()) {
            content.addView(text("No data", 14f, Color.GRAY)); return
        }
        val (t, v) = pts.last()
        content.addView(text("Latest %.1f %s at %s".format(v, unit, time.format(Date(t * 1000))), 16f))
        content.addView(LineChart(this, pts, color))
    }

    private fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    private fun header(s: String) = content.addView(text(s, 13f, Color.GRAY).apply {
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(20), 0, dp(4))
    })

    private fun text(s: String, size: Float, color: Int? = null) = TextView(this).apply {
        text = s
        textSize = size
        if (color != null) setTextColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

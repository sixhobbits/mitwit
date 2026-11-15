package app.scanwatch

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.view.View
import android.widget.EditText
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class MainActivity : Activity() {
    enum class Tab(val label: String) { ACTIVITY("Activity"), SLEEP("Sleep"), VITALS("Vitals"), WATCH("Watch") }

    private lateinit var db: Db
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private lateinit var syncPill: TextView
    private lateinit var todayPill: TextView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("meta", MODE_PRIVATE) }

    private var tab = Tab.ACTIVITY
    private var period = Period.of(Range.DAY, LocalDate.now())
    private var busy: String? = null
    private val logLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        // Data from version 0.1 (JSON files) is re-read from the watch on the next sync.
        File(filesDir, "data").deleteRecursively()

        intent.getStringExtra("tab")?.let { t -> tab = Tab.valueOf(t.uppercase()) }
        val range = intent.getStringExtra("range")?.let { Range.valueOf(it.uppercase()) } ?: Range.DAY
        val date = intent.getStringExtra("date")?.let(LocalDate::parse) ?: LocalDate.now()
        period = Period.of(range, date)

        window.decorView.setBackgroundColor(Pal.BG)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.BG) }

        syncPill = label("Sync", 14f, Pal.TEXT, MEDIUM).apply {
            background = rounded(Pal.CHIP, dpf(16))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { startSync() }
        }
        todayPill = label("Today", 14f, Pal.TEXT, MEDIUM).apply {
            background = rounded(Pal.CHIP, dpf(16))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { open(Period.of(period.range, LocalDate.now())) }
        }
        val header = hrow().apply {
            setPadding(dp(20), dp(8), dp(16), dp(4))
            addView(label("MitWit", 22f, Pal.TEXT, BOLD), LinearLayout.LayoutParams(0, -2, 1f))
            addView(todayPill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            addView(syncPill)
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        scroll = ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false }
        nav = hrow().apply { setBackgroundColor(Pal.CARD) }

        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(dp(20), bars.top + dp(8), dp(16), dp(4))
            nav.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        render()

        // adb hooks: `--es secret <s>` saves the watch secret, `--ez sync true` starts a sync,
        // `--es import <path>` imports an export zip.
        intent.getStringExtra("secret")?.let { prefs.edit().putString("secret", it).commit() }
        if (intent.getBooleanExtra("sync", false)) startSync()
        intent.getStringExtra("import")?.let { path -> importFrom { File(path).inputStream() } }
    }

    // ---- Navigation ----

    fun open(p: Period) {
        period = p
        render()
        scroll.scrollTo(0, 0)
    }

    private val swipe by lazy { gestures() }

    private fun gestures() = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || tab == Tab.WATCH) return false
            val dx = e2.x - e1.x
            if (abs(dx) < dp(80) || abs(dx) < abs(e2.y - e1.y) * 2 || abs(vx) < 600) return false
            if (dx < 0 && period.hasNext) open(period.shift(1)) else if (dx > 0) open(period.shift(-1))
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Calendar picker; jumps to the period of the current range containing the picked day. */
    private fun pickDate() {
        val d = period.latestDay
        DatePickerDialog(this, { _, y, m, day -> open(Period.of(period.range, LocalDate.of(y, m + 1, day))) },
            d.year, d.monthValue - 1, d.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    private fun render() {
        renderNav()
        todayPill.visibility = if (tab != Tab.WATCH && !period.containsToday) View.VISIBLE else View.GONE
        syncPill.text = busy ?: "Sync"
        syncPill.alpha = if (busy != null) 0.6f else 1f
        body.removeAllViews()
        if (tab == Tab.WATCH) return renderWatch()

        // Switching range keeps the most recent day of the period being viewed, so Year → Day
        // lands on today (or the last day of a past year), not on 1 January.
        body.addView(segmented(Range.entries.map { it.label }, period.range.ordinal) { i ->
            open(Period.of(Range.entries[i], period.latestDay))
        })
        body.addView(hrow().apply {
            setPadding(0, dp(6), 0, dp(6))
            addView(arrow("‹", true) { open(period.shift(-1)) })
            addView(label("${period.title()}  ▾", 17f, Pal.TEXT, MEDIUM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { pickDate() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow("›", period.hasNext) { open(period.shift(1)) })
        })
        when (tab) {
            Tab.ACTIVITY -> ActivityScreen(this, db).build(period, body)
            Tab.SLEEP -> SleepScreen(this, db).build(period, body)
            Tab.VITALS -> VitalsScreen(this, db).build(period, body)
            Tab.WATCH -> {}
        }
    }

    private fun arrow(s: String, enabled: Boolean, go: () -> Unit) = label(s, 28f, if (enabled) Pal.TEXT else Pal.GRID).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(44))
        if (enabled) setOnClickListener { go() }
    }

    private fun renderNav() {
        nav.removeAllViews()
        for (t in Tab.entries) {
            nav.addView(label(t.label, 13f, if (t == tab) Pal.TEXT else Pal.SUB, MEDIUM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(14))
                val marker = rounded(if (t == tab) Pal.STEPS else Color.TRANSPARENT, dpf(2)).apply { setSize(dp(28), dp(4)) }
                setCompoundDrawablesWithIntrinsicBounds(null, marker, null, null)
                compoundDrawablePadding = dp(8)
                setOnClickListener { tab = t; render(); scroll.scrollTo(0, 0) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    // ---- Watch tab: sync, import, stored data ----

    private fun renderWatch() {
        val last = prefs.getString("lastSync", null)?.toLongOrNull()
        val battery = prefs.getString("battery", null)
        body.addView(card().apply {
            addView(label("ScanWatch 2", 20f, Pal.TEXT, BOLD))
            addView(label(buildList {
                add(if (last != null) "Last sync ${fmtTime(last)}" else "Never synced")
                if (battery != null) add("Battery $battery%")
            }.joinToString(" · "), 14f, Pal.SUB).apply { setPadding(0, dp(2), 0, dp(14)) })
            addView(button(busy ?: "Sync now", busy == null) { startSync() })
        })
        body.addView(card("Watch secret").apply {
            val secret = prefs.getString("secret", "") ?: ""
            addView(label(
                if (secret.isEmpty()) "Paste the association secret for your watch. See the README for how to get it from a Bluetooth log."
                else "Saved (…${secret.takeLast(4)}). It's used to recognise and log in to your watch.",
                14f, Pal.SUB,
            ).apply { setPadding(0, 0, 0, dp(12)) })
            val input = EditText(context).apply {
                hint = "Secret"
                setTextColor(Pal.TEXT)
                setHintTextColor(Pal.SUB)
                isSingleLine = true
                background = rounded(Pal.CHIP, dpf(12))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(button("Save secret", true) {
                prefs.edit().putString("secret", input.text.toString().trim()).apply()
                render()
            })
        })
        body.addView(card("Import Withings data").apply {
            addView(label("Pick the data_….zip export from Withings (Settings → Download my data). Re-importing is safe.", 14f, Pal.SUB)
                .apply { setPadding(0, 0, 0, dp(12)) })
            addView(button("Choose export file", busy == null) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                }, PICK_ZIP)
            })
        })
        if (logLines.isNotEmpty()) body.addView(card("Log").apply {
            addView(label(logLines.takeLast(40).joinToString("\n"), 12f, Pal.SUB).apply { typeface = Typeface.MONOSPACE })
        })
        body.addView(card("Stored on this phone").apply {
            for ((name, table) in listOf("Steps & distance" to "motion", "Sleep stages" to "sleep_state", "Heart rate" to "hr",
                "HRV" to "hrv", "Temperature" to "temp", "Respiratory rate" to "rr", "SpO2" to "spo2")) {
                val (n, range) = db.count(table)
                addView(statRow(name, if (n == 0L) "none" else "${n.toInt().grouped()} · since ${fmtDate(range[0])}"))
            }
            addView(label("No internet permission: nothing leaves this phone.", 13f, Pal.SUB).apply { setPadding(0, dp(10), 0, 0) })
        })
    }

    private fun button(s: String, enabled: Boolean, go: () -> Unit) = label(s, 16f, if (enabled) Pal.BG else Pal.SUB, MEDIUM).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
        background = rounded(if (enabled) Pal.STEPS else Pal.CHIP, dpf(14))
        if (enabled) setOnClickListener { go() }
    }

    private fun fmtTime(t: Long) = Instant.ofEpochSecond(t).atZone(db.zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
    private fun fmtDate(t: Long) = Instant.ofEpochSecond(t).atZone(db.zone).format(DateTimeFormatter.ofPattern("d MMM yyyy"))

    private fun log(s: String) {
        android.util.Log.i("ScanWatchLocal", s)
        main.post {
            logLines.add(s)
            if (busy != null) { busy = s.take(28); syncPill.text = busy }
        }
    }

    /** Runs [work] on a worker thread with the sync pill showing progress, then re-renders. */
    private fun background(title: String, work: () -> Unit) {
        if (busy != null) return
        busy = title
        logLines.clear()
        render()
        Thread {
            try { work() } catch (e: Exception) { log("Failed: ${e.message}") }
            main.post { busy = null; render() }
        }.start()
    }

    private fun startSync() {
        val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(perms, 1)
            return
        }
        background("Syncing…") {
            Sync(WatchClient(this, ::log), db, { k, v -> prefs.edit().putString(k, v).apply() }, ::log)
                .run(prefs.getString("secret", "") ?: "")
        }
    }

    private fun importFrom(open: () -> java.io.InputStream) = background("Importing…") {
        open().use { Importer(db, ::log).run(it) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startSync()
    }

    @Deprecated("Activity result API needs AndroidX; the platform callback is enough here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode == PICK_ZIP && resultCode == RESULT_OK && uri != null) {
            tab = Tab.WATCH
            importFrom { contentResolver.openInputStream(uri)!! }
        }
    }

    companion object {
        const val PICK_ZIP = 2
    }
}

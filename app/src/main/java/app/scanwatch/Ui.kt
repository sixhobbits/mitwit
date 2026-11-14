package app.scanwatch

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Pal {
    val BG = Color.parseColor("#0E1014")
    val CARD = Color.parseColor("#181B21")
    val CHIP = Color.parseColor("#232730")
    val TEXT = Color.parseColor("#F2F3F5")
    val SUB = Color.parseColor("#8E949E")
    val GRID = Color.parseColor("#262A33")
    val STEPS = Color.parseColor("#2DD4A3")
    val DEEP = Color.parseColor("#4338CA")
    val LIGHT = Color.parseColor("#60A5FA")
    val REM = Color.parseColor("#A78BFA")
    val AWAKE = Color.parseColor("#FBBF24")
    val HR = Color.parseColor("#F43F5E")
    val HRV = Color.parseColor("#34D399")
    val TEMP = Color.parseColor("#FB923C")
    val RR = Color.parseColor("#C084FC")
    val SPO2 = Color.parseColor("#38BDF8")

    /** Sleep stage codes: 0 awake, 1 light, 2 deep, 3 REM. */
    fun stage(s: Int) = when (s) { 0 -> AWAKE; 1 -> LIGHT; 2 -> DEEP; else -> REM }

    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = Pal.TEXT, face: Typeface? = null) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (face != null) typeface = face
}

/** A big number with a small unit after it, e.g. "7h 12m" or "8,308 steps". */
fun Context.metric(value: String, unit: String, size: Float = 40f) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.BOTTOM
    addView(label(value, size, Pal.TEXT, BOLD))
    if (unit.isNotEmpty()) addView(label(unit, 15f, Pal.SUB).apply { setPadding(dp(6), 0, 0, dp(7)) })
}

fun Context.card(title: String? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Pal.CARD, dpf(20))
    setPadding(dp(18), dp(16), dp(18), dp(18))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    if (title != null) addView(label(title, 13f, Pal.SUB, MEDIUM).apply { setPadding(0, 0, 0, dp(10)) })
}

fun Context.hrow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Label on the left, value on the right. */
fun Context.statRow(name: String, value: String, dot: Int? = null) = hrow().apply {
    setPadding(0, dp(7), 0, dp(7))
    if (dot != null) addView(View(context).apply {
        background = rounded(dot, dpf(5))
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
    })
    addView(label(name, 15f, Pal.SUB), LinearLayout.LayoutParams(0, -2, 1f))
    addView(label(value, 15f, Pal.TEXT, MEDIUM))
}

/** Small stat block: value over caption, laid out side by side with weight. */
fun Context.tile(value: String, caption: String, color: Int = Pal.TEXT) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(label(value, 22f, color, BOLD))
    addView(label(caption, 13f, Pal.SUB))
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
}

/** Pill-style segmented control. */
fun Context.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    background = rounded(Pal.CHIP, dpf(14))
    setPadding(dp(3), dp(3), dp(3), dp(3))
    options.forEachIndexed { i, s ->
        addView(label(s, 14f, if (i == selected) Pal.TEXT else Pal.SUB, MEDIUM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            if (i == selected) background = rounded(Color.parseColor("#3A3F4B"), dpf(11))
            setOnClickListener { onSelect(i) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
}

fun hm(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "%dh %02dm".format(m / 60, m % 60) else "${m}m"
}

fun Int.grouped(): String = "%,d".format(this)

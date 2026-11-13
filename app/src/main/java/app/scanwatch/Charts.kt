package app.scanwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Line chart of (unix seconds, value) points with min/max and time-range labels. */
class LineChart(context: Context, private val points: List<Pair<Long, Double>>, color: Int) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = context.dp(2f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.GRAY; textSize = context.dp(11f)
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(140f).toInt())

    override fun onDraw(c: Canvas) {
        if (points.size < 2) {
            c.drawText("Not enough data", 0f, height / 2f, label)
            return
        }
        val pad = context.dp(16f)
        val t0 = points.first().first
        val t1 = points.last().first
        val vMin = points.minOf { it.second }
        val vMax = points.maxOf { it.second }
        val span = (vMax - vMin).takeIf { it > 0 } ?: 1.0
        val top = pad
        val bottom = height - pad
        val path = Path()
        var prevT = -1L
        for ((t, v) in points) {
            val x = (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * width
            val y = (bottom - (v - vMin) / span * (bottom - top)).toFloat()
            // Break the line across gaps longer than 30 minutes.
            if (prevT < 0 || t - prevT > 1800) path.moveTo(x, y) else path.lineTo(x, y)
            prevT = t
        }
        c.drawPath(path, line)
        c.drawText(fmt(vMax), 0f, top - context.dp(3f), label)
        c.drawText(fmt(vMin), 0f, height.toFloat() - context.dp(2f), label)
        val range = "${hhmm.format(Date(t0 * 1000))} – ${hhmm.format(Date(t1 * 1000))}"
        c.drawText(range, width - label.measureText(range), height.toFloat() - context.dp(2f), label)
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
}

/** Sleep stages over time: awake, REM, light, deep from top to bottom. */
class Hypnogram(context: Context, private val slots: List<Triple<Long, Int, Int>>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textSize = context.dp(11f)
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(110f).toInt())

    override fun onDraw(c: Canvas) {
        if (slots.isEmpty()) return
        val t0 = slots.first().first
        val t1 = slots.last().let { it.first + it.second }
        val rowH = (height - context.dp(16f)) / 4f
        for ((t, dur, stage) in slots) {
            val row = ROW[stage] ?: continue
            paint.color = COLOR[stage] ?: Color.GRAY
            val x0 = (t - t0).toFloat() / (t1 - t0) * width
            val x1 = (t + dur - t0).toFloat() / (t1 - t0) * width
            c.drawRect(x0, row * rowH, maxOf(x1, x0 + 1f), (row + 1) * rowH, paint)
        }
        val range = "${hhmm.format(Date(t0 * 1000))} – ${hhmm.format(Date(t1 * 1000))}"
        c.drawText(range, width - label.measureText(range), height.toFloat() - context.dp(2f), label)
    }

    companion object {
        // Stage codes from the watch: 0 awake, 1 light, 2 deep, 3 REM.
        val ROW = mapOf(0 to 0, 3 to 1, 1 to 2, 2 to 3)
        val COLOR = mapOf(
            0 to Color.parseColor("#F2A541"),
            3 to Color.parseColor("#4FC3F7"),
            1 to Color.parseColor("#5C6BC0"),
            2 to Color.parseColor("#283593"),
        )
    }
}

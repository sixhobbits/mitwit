package app.scanwatch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private fun textPaint(ctx: Context, tint: Int = Pal.SUB, size: Float = 11f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = tint; textSize = ctx.dpf(size)
}

/** A "nice" step for about [n] grid lines up to [maxV]. */
private fun niceStep(maxV: Double, n: Int = 3): Double {
    if (maxV <= 0) return 1.0
    val raw = maxV / n
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 2.5 -> 2.5; f <= 5 -> 5.0; else -> 10.0 }
}

/** Draws a rounded tooltip bubble centred on x at the top of the chart. */
private fun Canvas.bubble(ctx: Context, text: String, x: Float, width: Int) {
    val p = textPaint(ctx, Pal.TEXT, 12f).apply { typeface = MEDIUM }
    val w = p.measureText(text) + ctx.dpf(20)
    val h = ctx.dpf(26)
    val left = (x - w / 2).coerceIn(0f, width - w)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.CHIP }
    drawRoundRect(RectF(left, 0f, left + w, h), h / 2, h / 2, bg)
    drawText(text, left + ctx.dpf(10), h / 2 + p.textSize / 2.8f, p)
}

class Bar(val segments: List<Pair<Double, Int>>, val label: String?, val tip: String) {
    val total get() = segments.sumOf { it.first }
}

/**
 * Bar chart (optionally stacked). Tapping a bar shows its tooltip; tapping the same bar
 * again calls [onOpen] (used to drill down from week/month/year into a single period).
 */
@SuppressLint("ViewConstructor")
class BarChart(
    ctx: Context,
    private val bars: List<Bar>,
    private val goal: Double? = null,
    private val yLabel: (Double) -> String = { "%,d".format(it.toLong()) },
    private val heightDp: Int = 200,
    private val onOpen: ((Int) -> Unit)? = null,
) : View(ctx) {
    private var selected = -1
    private val grid = Paint().apply { color = Pal.GRID; strokeWidth = ctx.dpf(1) }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.SUB; strokeWidth = ctx.dpf(1.2f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(4), ctx.dpf(4)), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = textPaint(ctx)
    private val top get() = context.dpf(34)
    private val bottomPad get() = context.dpf(22)
    private val rightPad get() = context.dpf(40)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(heightDp))

    private fun slot() = (width - rightPad) / bars.size.coerceAtLeast(1)

    override fun onDraw(c: Canvas) {
        val maxV = max(bars.maxOfOrNull { it.total } ?: 0.0, goal ?: 0.0).coerceAtLeast(1.0)
        val step = niceStep(maxV)
        val yMax = ceil(maxV / step) * step
        val chartBottom = height - bottomPad
        val h = chartBottom - top
        fun y(v: Double) = (chartBottom - v / yMax * h).toFloat()

        var g = 0.0
        while (g <= yMax + 1e-9) {
            val yy = y(g)
            c.drawLine(0f, yy, width - rightPad + context.dpf(4), yy, grid)
            c.drawText(yLabel(g), width - rightPad + context.dpf(8), yy + text.textSize / 3, text)
            g += step
        }

        val s = slot()
        val bw = min(s * 0.62f, context.dpf(28))
        val r = min(bw / 2, context.dpf(6))
        for ((i, b) in bars.withIndex()) {
            val cx = s * i + s / 2
            val dim = selected >= 0 && i != selected
            if (b.total > 0) {
                val rect = RectF(cx - bw / 2, y(b.total), cx + bw / 2, chartBottom)
                c.save()
                c.clipPath(Path().apply { addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW) })
                var acc = 0.0
                for ((v, col) in b.segments) {
                    fill.color = if (dim) Pal.alpha(col, 0.35f) else col
                    c.drawRect(rect.left, y(acc + v), rect.right, y(acc), fill)
                    acc += v
                }
                c.restore()
            }
            b.label?.let {
                text.color = if (i == selected) Pal.TEXT else Pal.SUB
                c.drawText(it, cx - text.measureText(it) / 2, height - context.dpf(5), text)
                text.color = Pal.SUB
            }
        }
        goal?.let {
            val gy = y(it)
            c.drawPath(Path().apply { moveTo(0f, gy); lineTo(width - rightPad, gy) }, goalPaint)
        }
        if (selected in bars.indices) c.bubble(context, bars[selected].tip, s * selected + s / 2, (width - rightPad).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            val i = (e.x / slot()).toInt()
            if (i !in bars.indices) return true
            if (i == selected && onOpen != null) onOpen.invoke(i)
            else { selected = if (i == selected) -1 else i; invalidate() }
        }
        return true
    }
}

/**
 * Time-series line chart. With [bands] it draws each day's min–max range as a shaded
 * area behind the line of daily averages.
 */
@SuppressLint("ViewConstructor")
class LineChart(
    ctx: Context,
    private val points: List<Point>,
    private val tint: Int,
    private val xFrom: Long,
    private val xTo: Long,
    private val ticks: List<Pair<Long, String>>,
    private val tip: (Point) -> String,
    private val bands: List<Triple<Long, Double, Double>> = emptyList(),
    private val gap: Long = 3600,
) : View(ctx) {
    private var selected: Point? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = tint; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2); strokeJoin = Paint.Join.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Pal.alpha(tint, 0.22f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = tint }
    private val grid = Paint().apply { this.color = Pal.GRID; strokeWidth = ctx.dpf(1) }
    private val text = textPaint(ctx)
    private val top get() = context.dpf(34)
    private val bottomPad get() = context.dpf(22)
    private val rightPad get() = context.dpf(40)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(170))

    private fun x(t: Long) = ((t - xFrom).toDouble() / (xTo - xFrom) * (width - rightPad)).toFloat()

    override fun onDraw(c: Canvas) {
        val lo = min(points.minOfOrNull { it.v } ?: 0.0, bands.minOfOrNull { it.second } ?: Double.MAX_VALUE)
        val hi = max(points.maxOfOrNull { it.v } ?: 1.0, bands.maxOfOrNull { it.third } ?: -Double.MAX_VALUE)
        val step = niceStep((hi - lo).coerceAtLeast(0.5), 3)
        val yMin = floor(lo / step) * step
        val yMax = ceil(hi / step) * step + if (ceil(hi / step) * step == hi) step else 0.0
        val chartBottom = height - bottomPad
        fun y(v: Double) = (chartBottom - (v - yMin) / (yMax - yMin) * (chartBottom - top)).toFloat()

        var g = yMin
        while (g <= yMax + 1e-9) {
            c.drawLine(0f, y(g), width - rightPad + context.dpf(4), y(g), grid)
            val s = if (step < 1) "%.1f".format(g) else "%,d".format(g.toLong())
            c.drawText(s, width - rightPad + context.dpf(8), y(g) + text.textSize / 3, text)
            g += step
        }
        for ((t, s) in ticks) {
            val tx = x(t)
            c.drawText(s, (tx - text.measureText(s) / 2).coerceIn(0f, width - rightPad - text.measureText(s)), height - context.dpf(5), text)
        }

        val bw = if (bands.size > 1) (x(bands[1].first) - x(bands[0].first)) * 0.6f else context.dpf(6)
        for ((t, a, b) in bands) {
            val cx = x(t)
            c.drawRoundRect(RectF(cx - bw / 2, y(b), cx + bw / 2, y(a)), bw / 2, bw / 2, area)
        }

        val path = Path()
        var prev: Point? = null
        for (p in points) {
            if (prev == null || p.t - prev.t > gap) path.moveTo(x(p.t), y(p.v)) else path.lineTo(x(p.t), y(p.v))
            prev = p
        }
        c.drawPath(path, line)
        if (points.size <= 60) for (p in points) c.drawCircle(x(p.t), y(p.v), context.dpf(2.5f), dot)

        selected?.let {
            c.drawLine(x(it.t), top, x(it.t), chartBottom, grid)
            c.drawCircle(x(it.t), y(it.v), context.dpf(5), dot)
            c.bubble(context, tip(it), x(it.t), (width - rightPad).toInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        if (e.action == MotionEvent.ACTION_UP) {
            selected = points.minBy { abs(x(it.t) - e.x) }
            invalidate()
        }
        return true
    }
}

/**
 * Hypnogram: one lane per stage (awake, REM, light, deep from top to bottom), rounded
 * blocks per stage run and thin connectors between consecutive runs.
 */
@SuppressLint("ViewConstructor")
class Hypnogram(ctx: Context, stages: List<Stage>, private val zone: ZoneId) : View(ctx) {
    private class Run(val from: Long, var to: Long, val state: Int)

    private val runs = ArrayList<Run>().apply {
        for (s in stages) {
            val last = lastOrNull()
            if (last != null && last.state == s.state && s.t - last.to < 300) last.to = s.t + s.d
            else add(Run(s.t, s.t + s.d, s.state))
        }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.alpha(Pal.TEXT, 0.18f); strokeWidth = ctx.dpf(1) }
    private val text = textPaint(ctx)
    private val grid = Paint().apply { color = Pal.GRID; strokeWidth = ctx.dpf(1) }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(190))

    override fun onDraw(c: Canvas) {
        if (runs.isEmpty()) return
        val t0 = runs.first().from
        val t1 = runs.last().to
        val left = context.dpf(48)
        val bottomPad = context.dpf(22)
        val laneH = (height - bottomPad) / 4f
        val barH = laneH * 0.62f
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * (width - left)
        fun lane(state: Int) = when (state) { 0 -> 0; 3 -> 1; 1 -> 2; else -> 3 }

        for ((i, name) in listOf("Awake", "REM", "Light", "Deep").withIndex()) {
            val cy = laneH * i + laneH / 2
            c.drawLine(left, cy, width.toFloat(), cy, grid)
            c.drawText(name, 0f, cy + text.textSize / 3, text)
        }
        // Hour ticks.
        var tick = (t0 / 3600 + 1) * 3600
        val every = if (t1 - t0 > 10 * 3600) 2 else 1
        while (tick < t1) {
            val zt = Instant.ofEpochSecond(tick).atZone(zone)
            if (zt.hour % every == 0) {
                val s = "%02d".format(zt.hour)
                c.drawText(s, x(tick) - text.measureText(s) / 2, height - context.dpf(5), text)
            }
            tick += 3600
        }
        var prev: Run? = null
        for (r in runs) {
            val cy = laneH * lane(r.state) + laneH / 2
            prev?.let {
                val py = laneH * lane(it.state) + laneH / 2
                c.drawLine(x(r.from), py, x(r.from), cy, link)
            }
            fill.color = Pal.stage(r.state)
            c.drawRoundRect(RectF(x(r.from), cy - barH / 2, max(x(r.to), x(r.from) + context.dpf(2)), cy + barH / 2),
                context.dpf(3), context.dpf(3), fill)
            prev = r
        }
    }
}

/** Progress ring with a value and caption in the middle. */
@SuppressLint("ViewConstructor")
class Ring(ctx: Context, private val progress: Float, private val tint: Int, private val value: String, private val caption: String) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(16); color = Pal.CHIP; strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(track).apply { this.color = tint }
    private val big = textPaint(ctx, Pal.TEXT, 38f).apply { typeface = BOLD; textAlign = Paint.Align.CENTER }
    private val small = textPaint(ctx, Pal.SUB, 14f).apply { textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(220))

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f - track.strokeWidth
        val cx = width / 2f
        val cy = height / 2f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, 135f, 270f, false, track)
        if (progress > 0) c.drawArc(oval, 135f, 270f * progress.coerceAtMost(1f), false, arc)
        c.drawText(value, cx, cy + big.textSize / 3, big)
        c.drawText(caption, cx, cy + big.textSize / 3 + context.dpf(24), small)
    }
}

/** Thin horizontal proportion bar. */
@SuppressLint("ViewConstructor")
class Proportion(ctx: Context, private val fraction: Float, private val tint: Int) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Pal.CHIP }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = tint }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(6))
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bg)
        if (fraction > 0) c.drawRoundRect(RectF(0f, 0f, max(width * fraction, height.toFloat()), height.toFloat()), r, r, fg)
    }
}

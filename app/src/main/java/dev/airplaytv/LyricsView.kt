package dev.airplaytv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.pow

/**
 * Time-synced lyrics, one line highlighted. The highlighted line is held at a fixed height in the
 * view; on a new line the text glides up (fast ease-out) and the highlight colour fades from the old
 * line to the new one. Its size never changes, so nothing around it moves. Clock-driven, so it also
 * animates when the system's animations are off.
 */
class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 25f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val gap = 14 * resources.displayMetrics.density

    private var lines: List<String> = emptyList()
    private var layouts: List<StaticLayout> = emptyList()
    /** Top of each line, from the top of the first. */
    private var tops: FloatArray = FloatArray(0)

    /** Colour of the line being sung; set from the album art. */
    var highlight = Color.rgb(0x7D, 0xD3, 0xFC)
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    private var current = -1
    private var previous = -1
    private var changedAt = 0L
    private var scrollFrom = 0f

    fun setLines(newLines: List<String>) {
        if (newLines == lines) return
        lines = newLines
        current = -1
        previous = -1
        layouts = emptyList()
        requestLayout()
        invalidate()
    }

    /** The line being sung (-1: before the first). Starts the glide and colour fade on a change. */
    fun setCurrent(index: Int) {
        if (index == current) return
        scrollFrom = scrollNow()
        previous = current
        current = index
        changedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layouts = emptyList()
    }

    private fun build() {
        val width = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        layouts = lines.map {
            StaticLayout.Builder.obtain(it, 0, it.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        tops = FloatArray(layouts.size)
        var y = 0f
        for (i in layouts.indices) {
            tops[i] = y
            y += layouts[i].height + gap
        }
    }

    /** 0 → 1 over the transition, eased out (fast start, gentle stop). */
    private fun progress(): Float {
        val t = ((SystemClock.uptimeMillis() - changedAt) / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - (1f - t).pow(3)
    }

    /** How far the lines are scrolled: the highlighted line's top. */
    private fun scrollTarget() = if (current in tops.indices) tops[current] else -gap * 4
    private fun scrollNow(): Float {
        if (layouts.isEmpty()) return scrollTarget()
        return scrollFrom + (scrollTarget() - scrollFrom) * progress()
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        if (layouts.isEmpty()) {
            build()
            scrollFrom = scrollTarget()
        }
        val p = progress()
        val anchor = height * ANCHOR
        val scroll = scrollFrom + (scrollTarget() - scrollFrom) * p
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(paddingLeft.toFloat(), anchor - scroll)
        for (i in layouts.indices) {
            val top = tops[i]
            val screenTop = anchor - scroll + top
            if (screenTop > height || screenTop + layouts[i].height < 0) continue
            paint.color = fadeAtEdges(colorFor(i, p), screenTop, screenTop + layouts[i].height)
            canvas.save()
            canvas.translate(0f, top)
            layouts[i].draw(canvas)
            canvas.restore()
        }
        canvas.restore()
        if (p < 1f) postInvalidateOnAnimation()
    }

    /** [highlight] for the sung line, fading in; dim white elsewhere, dimmer further away. */
    private fun colorFor(i: Int, p: Float): Int {
        val distance = if (current < 0) i + 1 else abs(i - current)
        val dim = Color.argb((DIM_ALPHA * 0.72f.pow(maxOf(0, distance - 1))).toInt(), 255, 255, 255)
        return when (i) {
            current -> blend(dim, highlight, p)
            previous -> blend(highlight, dim, p)
            else -> dim
        }
    }

    /** Lines fade out as they reach the top or bottom edge, instead of being cut off there. */
    private fun fadeAtEdges(color: Int, top: Float, bottom: Float): Int {
        val zone = height * EDGE_FADE
        val f = minOf(top / zone, (height - bottom) / zone).coerceIn(0f, 1f)
        return Color.argb((Color.alpha(color) * f).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    companion object {
        private const val TRANSITION_MS = 260L
        /** Where the sung line sits, as a fraction of the view's height. */
        private const val ANCHOR = 0.42f
        private const val DIM_ALPHA = 150f
        /** Height, as a fraction of the view, over which lines fade at the top and bottom. */
        private const val EDGE_FADE = 0.16f
    }
}

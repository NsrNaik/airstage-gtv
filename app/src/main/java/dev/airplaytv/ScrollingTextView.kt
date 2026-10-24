package dev.airplaytv

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.TextView

/**
 * A one-line TextView that, when its text is too long, slides to the end, pauses, slides
 * back and repeats, so every character is shown. (Android's built-in marquee restarts on
 * relayout and loops back before the end has been readable.)
 */
class ScrollingTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextView(context, attrs) {

    private var distance = 0
    private var startedAt = 0L
    private val speedPxPerMs = 30 * resources.displayMetrics.density / 1000f

    // Driven per frame from the clock rather than a ValueAnimator: animators are frozen when the
    // system "animator duration scale" is 0, as on TVs set up for speed.
    private val frame = object : Runnable {
        override fun run() {
            if (distance == 0) return
            val travel = (distance / speedPxPerMs).toLong().coerceAtLeast(600)
            val cycle = 2 * (PAUSE_MS + travel)
            val t = (SystemClock.uptimeMillis() - startedAt) % cycle
            val x = when {
                t < PAUSE_MS -> 0f
                t < PAUSE_MS + travel -> (t - PAUSE_MS) * speedPxPerMs
                t < 2 * PAUSE_MS + travel -> distance.toFloat()
                else -> distance - (t - 2 * PAUSE_MS - travel) * speedPxPerMs
            }
            val px = Math.round(x).coerceIn(0, distance)
            if (px != scrollX) scrollTo(px, 0)
            postOnAnimation(this)
        }
    }

    init {
        isSingleLine = true
        ellipsize = null
        setHorizontallyScrolling(true)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        super.onTextChanged(text, start, before, after)
        post { restart() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) post { restart() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { restart() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun overflow(): Int {
        val l = layout ?: return 0
        val visible = width - compoundPaddingLeft - compoundPaddingRight
        return (l.getLineWidth(0) - visible).toInt().coerceAtLeast(0)
    }

    private fun stop() {
        removeCallbacks(frame)
        distance = 0
        scrollTo(0, 0)
    }

    private fun restart() {
        stop()
        if (!isAttachedToWindow) return
        distance = overflow()
        if (distance == 0) return
        startedAt = SystemClock.uptimeMillis()
        postOnAnimation(frame)
    }

    companion object {
        private const val PAUSE_MS = 2500L
    }
}

package dev.airplaytv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos

/**
 * "[phone] Alex's iPhone · · · [tv] Living Room TV": who is AirPlaying to this TV. The three dots
 * pulse from the phone towards the TV while something plays and rest when paused.
 */
class ConnectionHeader @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private val phoneIcon = icon(R.drawable.ic_phone)
    private val clientName = label()
    private val dots = Dots(context)
    private val tvIcon = icon(R.drawable.ic_tv)
    private val tvName = label()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(phoneIcon, LayoutParams((22 * d).toInt(), (22 * d).toInt()))
        addView(clientName, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (8 * d).toInt()
        })
        addView(dots, LayoutParams((44 * d).toInt(), (22 * d).toInt()).apply {
            marginStart = (14 * d).toInt()
            marginEnd = (14 * d).toInt()
        })
        addView(tvIcon, LayoutParams((22 * d).toInt(), (22 * d).toInt()))
        addView(tvName, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (8 * d).toInt()
        })
    }

    /** An empty [client] (not known yet) shows the TV alone. */
    fun set(client: String, tv: String) {
        if (clientName.text.toString() != client) clientName.text = client
        if (tvName.text.toString() != tv) tvName.text = tv
        val showClient = if (client.isEmpty()) View.GONE else View.VISIBLE
        phoneIcon.visibility = showClient
        clientName.visibility = showClient
        dots.visibility = showClient
    }

    /** Pulse the dots (something is playing) or let them rest. */
    fun setLive(live: Boolean) = dots.setLive(live)

    private fun icon(res: Int) = ImageView(context).apply { setImageResource(res) }

    private fun label() = TextView(context).apply {
        textSize = 16f
        setTextColor(0xD9FFFFFF.toInt())
        maxLines = 1
        isSingleLine = true
    }

    /** Three dots; while live a brightness wave runs phone → TV, driven by the clock. */
    private class Dots(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() }
        private var live = false

        fun setLive(on: Boolean) {
            if (on == live) return
            live = on
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = height * 0.13f
            val step = width / 3f
            val t = SystemClock.uptimeMillis() % PERIOD_MS / PERIOD_MS.toFloat()
            for (i in 0..2) {
                val alpha = if (live) {
                    // A wave of brightness moving left to right, one dot after the other.
                    val phase = (t - i / 3f) * 2 * PI
                    0.3f + 0.7f * ((cos(phase) + 1) / 2).toFloat()
                } else 0.45f
                paint.alpha = (alpha * 255).toInt()
                canvas.drawCircle(step * (i + 0.5f), height / 2f, r, paint)
            }
            if (live) postInvalidateOnAnimation()
        }

        companion object {
            private const val PERIOD_MS = 1200L
        }
    }
}

package dev.airplaytv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Screenshot mode, in debug builds only: fills the screens with an original demo track (cover drawn
 * here, lyrics written for it, neutral device names) so screenshots contain no copyrighted art or
 * personal names. Start it with
 *   adb shell am start -n dev.airplaytv/.MainActivity --es demo lyrics|music|connected|pin|home
 * ("home" keeps the start screen, with the neutral TV name; force-stop the app first.)
 */
object Demo {

    /** TV name to show instead of the real one while a demo runs. */
    @Volatile var tvName: String? = null
        private set

    private const val CLIENT = "Alex's iPhone"
    private val TRACK = NowPlaying.Track("Midnight Avenue", "The Night Owls", "City Lights")
    private const val LENGTH_MS = 214_000L

    private val LYRICS = listOf(
        "City lights are humming low",
        "Every window keeps a glow",
        "We drive until the morning finds us",
        "Midnight avenue, the world behind us",
        "Turn it up and let it play",
        "Every song will find its way",
        "From your hand onto the stage",
        "Midnight avenue, we turn the page",
        "♪",
        "Hold the moment, let it ring",
        "Every street begins to sing",
    )

    /**
     * Returns true if [intent] asked for a Now Playing demo and this is a debug build; then shows it.
     * The "home" demo only sets the TV name and returns false, so the start screen stays.
     */
    fun startIfRequested(context: Context, intent: Intent?): Boolean {
        val mode = intent?.getStringExtra("demo") ?: return false
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        tvName = "Living Room TV"
        if (mode == "home") return false
        when (mode) {
            "pin" -> NowPlaying.setPin("4821")
            "connected" -> NowPlaying.setConnected(CLIENT)
            else -> {
                val start = 58_000L
                NowPlaying.demo(TRACK, cover(), LENGTH_MS, start, CLIENT)
                Lyrics.demo(TRACK, LENGTH_MS, LYRICS.mapIndexed { i, text -> Lyrics.Line(48_000L + i * 4_200L, text) })
                context.getSharedPreferences("airplay", Context.MODE_PRIVATE).edit()
                    .putBoolean("show_lyrics", mode == "lyrics").apply()
            }
        }
        context.startActivity(Intent(context, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** An original abstract cover: a dusk gradient with two soft glows and a horizon line. */
    private fun cover(): Bitmap {
        val size = 600
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(),
            intArrayOf(Color.rgb(0x1B, 0x10, 0x3F), Color.rgb(0x5B, 0x21, 0x7A), Color.rgb(0xE1, 0x5A, 0x6B)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        fun glow(x: Float, y: Float, r: Float, color: Int) {
            p.shader = RadialGradient(x, y, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        glow(size * 0.7f, size * 0.62f, size * 0.32f, Color.argb(200, 0xFF, 0xB3, 0x6B))
        glow(size * 0.25f, size * 0.3f, size * 0.22f, Color.argb(150, 0x38, 0xBD, 0xF8))
        p.shader = null
        p.color = Color.argb(170, 0x10, 0x08, 0x20)
        c.drawRect(0f, size * 0.74f, size.toFloat(), size.toFloat(), p)
        p.color = Color.argb(220, 0xFF, 0xD9, 0xA0)
        p.strokeWidth = 3f
        c.drawLine(0f, size * 0.74f, size.toFloat(), size * 0.74f, p)
        return bmp
    }
}

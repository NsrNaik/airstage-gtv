package dev.airplaytv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen "Now Playing" for AirPlay audio: large cover art over a blurred copy of it,
 * with title, artist, album and track progress sent by the iPhone. Before any audio arrives it
 * shows which iPhone connected and that the TV is waiting for audio or video.
 */
class NowPlayingActivity : Activity() {

    private lateinit var backdrop: ImageView
    private lateinit var cover: ImageView
    private lateinit var source: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView

    private val main = Handler(Looper.getMainLooper())
    private var shownCover: Bitmap? = null
    private var shownPlaceholder = 0

    private val onChange: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            if (NowPlaying.active) renderProgress() else render()
            main.postDelayed(this, if (NowPlaying.active) 500 else 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_now_playing)
        backdrop = findViewById(R.id.backdrop)
        cover = findViewById(R.id.cover)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        album = findViewById(R.id.album)
        progress = findViewById(R.id.progress)
        elapsed = findViewById(R.id.elapsed)
        remaining = findViewById(R.id.remaining)

        val radius = resources.displayMetrics.density * 16
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        cover.clipToOutline = true
        source = findViewById(R.id.source)
    }

    override fun onStart() {
        super.onStart()
        NowPlaying.observe(onChange)
        render()
        main.post(ticker)
    }

    override fun onStop() {
        NowPlaying.unobserve(onChange)
        main.removeCallbacks(ticker)
        super.onStop()
        // Nothing to come back to: the next AirPlay session opens a fresh screen.
        finish()
    }

    private fun render() {
        if (idle()) {
            // The iPhone disconnected (or switched to another receiver).
            main.postDelayed({ if (idle()) finish() }, 1500)
        }
        val tv = AirPlayService.deviceName(this)
        val header = NowPlaying.clientName.takeIf { it.isNotEmpty() }?.let { "$it  →  $tv" } ?: tv
        setIfChanged(source, header)
        NowPlaying.pin?.let { pin ->
            setIfChanged(source, tv)
            setIfChanged(title, pin.toCharArray().joinToString("  "))
            setIfChanged(artist, getString(R.string.pin_hint))
            setIfChanged(album, getString(R.string.pin_once))
            showCover(null, R.drawable.ic_airplay)
            showWallpaper()
            renderProgress()
            return
        }
        if (!NowPlaying.active) {
            setIfChanged(title, getString(R.string.connected_title))
            setIfChanged(artist, getString(R.string.waiting_for_media) + animatedEllipsis())
            setIfChanged(album, getString(R.string.waiting_hint))
            showCover(null, R.drawable.ic_airplay)
            showWallpaper()
            renderProgress()
            return
        }
        val t = NowPlaying.track
        setIfChanged(title, t.title.ifEmpty { getString(R.string.waiting_title) })
        setIfChanged(artist, t.artist)
        setIfChanged(album, t.album)
        showCover(NowPlaying.cover, R.drawable.ic_music_note)
        renderProgress()
    }

    private fun showCover(bmp: Bitmap?, placeholder: Int) {
        if (bmp === shownCover && placeholder == shownPlaceholder) return
        shownCover = bmp
        shownPlaceholder = placeholder
        if (bmp != null) {
            cover.setImageBitmap(bmp)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            backdrop.setImageBitmap(blur(bmp))
        } else {
            cover.setImageResource(placeholder)
            cover.scaleType = ImageView.ScaleType.CENTER
            backdrop.setImageDrawable(null)
        }
    }

    private fun idle() = !NowPlaying.active && !NowPlaying.connected && NowPlaying.pin == null

    /** Waiting and PIN states have no cover art: show the wallpaper behind them instead. */
    private fun showWallpaper() {
        if (backdrop.drawable == null) backdrop.setImageResource(R.drawable.wallpaper)
    }

    @Deprecated("Back is handled with a dialog; onBackPressedDispatcher needs AndroidX.")
    override fun onBackPressed() {
        val who = NowPlaying.clientName.ifEmpty { "your device" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.back_title, who))
            .setMessage(R.string.back_message)
            .setPositiveButton(R.string.back_continue) { _, _ -> finish() }
            .setNegativeButton(R.string.back_stop) { _, _ ->
                AirPlayService.instance?.disconnect()
                finish()
            }
            .show()
    }

    /** Re-setting identical text would restart a running scroll from the beginning. */
    private fun setIfChanged(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    private fun renderProgress() {
        val dur = NowPlaying.durationMs
        val visible = if (dur > 0) View.VISIBLE else View.INVISIBLE
        progress.visibility = visible
        elapsed.visibility = visible
        remaining.visibility = visible
        if (dur <= 0) return
        val pos = NowPlaying.positionMs()
        progress.progress = (pos * 1000 / dur).toInt()
        elapsed.text = time(pos)
        remaining.text = "-" + time(dur - pos)
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * A cheap blur that works on every Android version: shrink the art to a few pixels and let
     * bilinear filtering smear it back up to screen size.
     */
    private fun blur(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, 12, 12, true)
        val medium = Bitmap.createScaledBitmap(small, 48, 48, true)
        return Bitmap.createScaledBitmap(medium, 192, 192, true)
    }
}

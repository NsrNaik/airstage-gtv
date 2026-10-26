package dev.airplaytv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen "Now Playing" for AirPlay audio: large cover art over a blurred copy of it,
 * with title, artist, album and track progress sent by the iPhone. Before any audio arrives it
 * shows which iPhone connected and that the TV is waiting for audio or video.
 */
class NowPlayingActivity : Activity() {

    private lateinit var backdrop: ImageView
    private lateinit var cover: ImageView
    private lateinit var source: ConnectionHeader
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var lyricsHint: TextView
    private lateinit var info: View
    private lateinit var lyricsView: LyricsView
    /** Lyrics shown instead of artist/album (▼ / ▲), remembered across songs. */
    private var showLyrics = false
    /** A short "Next track" / "Previous track" confirmation, shown until this uptime. */
    private var hint: String? = null
    private var hintUntil = 0L

    private val main = Handler(Looper.getMainLooper())
    private var shownCover: Bitmap? = null
    private var shownPlaceholder = 0

    private val onChange: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            if (NowPlaying.active) renderProgress() else render()
            if (NowPlaying.active && lyricsVisible()) renderLyrics()
            // Lyrics need finer steps than the progress bar to change line on time.
            main.postDelayed(this, if (NowPlaying.active && !lyricsVisible()) 500 else 150)
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
        remoteStatus = findViewById(R.id.remote_status)
        lyricsHint = findViewById(R.id.lyrics_hint)
        lyricsView = findViewById(R.id.lyrics)
        info = findViewById(R.id.info)
        showLyrics = getSharedPreferences("airplay", MODE_PRIVATE).getBoolean("show_lyrics", false)

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
        driftStart = SystemClock.uptimeMillis()
        backdrop.postOnAnimation(drift)
    }

    override fun onStop() {
        NowPlaying.unobserve(onChange)
        main.removeCallbacks(ticker)
        backdrop.removeCallbacks(drift)
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
        source.set(NowPlaying.clientName, tv)
        source.setLive(NowPlaying.active && !NowPlaying.paused)
        NowPlaying.pin?.let { pin ->
            source.set("", tv)
            setIfChanged(title, pin.toCharArray().joinToString("  "))
            setIfChanged(artist, getString(R.string.pin_hint))
            setIfChanged(album, getString(R.string.pin_once))
            showCover(null, R.drawable.ic_airplay)
            showWallpaper()
            hideLyrics()
            renderProgress()
            return
        }
        if (!NowPlaying.active) {
            setIfChanged(title, getString(R.string.connected_title))
            setIfChanged(artist, getString(R.string.waiting_for_media) + animatedEllipsis())
            setIfChanged(album, getString(R.string.waiting_hint))
            showCover(null, R.drawable.ic_airplay)
            showWallpaper()
            hideLyrics()
            renderProgress()
            return
        }
        val t = NowPlaying.track
        setIfChanged(title, t.title.ifEmpty { getString(R.string.waiting_title) })
        setIfChanged(artist, t.artist)
        setIfChanged(album, t.album)
        showCover(NowPlaying.cover, R.drawable.ic_music_note)
        if (NowPlaying.durationMs > 0) Lyrics.request(t, NowPlaying.durationMs) { render() }
        renderLyrics()
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
        applyColors(bmp)
    }

    /**
     * Colours for the title, artist, album, progress bar and sung lyric line, taken from the album
     * art: its most vivid colour, kept in hue but lightened (and calmed) so text stays readable on the
     * dark background. Warm ivory when there is no cover or it is black and white.
     */
    private fun applyColors(bmp: Bitmap?) {
        if (bmp == null) return setScheme(IVORY)
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            if (bmp !== shownCover) return@generate // the cover changed meanwhile
            setScheme(p?.let { mainColor(it) } ?: IVORY)
        }
    }

    /**
     * The cover's main colour: vivid AND covering a real part of the image (saturation × area), so
     * a small bright logo doesn't win over the colour that defines the cover. Null if it's greyscale.
     */
    private fun mainColor(p: Palette): Int? {
        val hsl = FloatArray(3)
        return p.swatches.maxByOrNull { s ->
            ColorUtils.colorToHSL(s.rgb, hsl)
            if (hsl[1] < 0.2f || hsl[2] < 0.12f || hsl[2] > 0.9f) 0.0 else s.population * hsl[1].toDouble()
        }?.takeIf { s ->
            ColorUtils.colorToHSL(s.rgb, hsl)
            hsl[1] >= 0.2f && hsl[2] in 0.12f..0.9f
        }?.rgb
    }

    private fun setScheme(base: Int) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        // Nearly grey: fall back to ivory's warm hue rather than a muddy tint.
        if (hsl[1] < 0.12f) ColorUtils.colorToHSL(IVORY, hsl)
        fun tone(sat: Float, light: Float, alpha: Int = 255): Int {
            val c = ColorUtils.HSLToColor(floatArrayOf(hsl[0], minOf(hsl[1], sat), light))
            return ColorUtils.setAlphaComponent(c, alpha)
        }
        title.setTextColor(tone(0.45f, 0.92f))
        artist.setTextColor(tone(0.55f, 0.80f, 0xD9))
        album.setTextColor(tone(0.40f, 0.80f, 0x8C))
        val bar = tone(0.70f, 0.72f)
        progress.progressTintList = ColorStateList.valueOf(bar)
        progress.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(bar, 0x40))
        lyricsView.highlight = tone(0.70f, 0.80f)
    }

    private fun idle() = !NowPlaying.active && !NowPlaying.connected && NowPlaying.pin == null

    /** Waiting and PIN states have no cover art: show the wallpaper behind them instead. */
    private fun showWallpaper() {
        if (backdrop.drawable != null) return
        Wallpaper.load(this) { bmp ->
            // Only if still on a waiting/PIN screen (no cover art arrived meanwhile).
            if (bmp != null && backdrop.drawable == null && shownCover == null) backdrop.setImageBitmap(bmp)
        }
    }

    @Deprecated("Back is handled with a dialog; onBackPressedDispatcher needs AndroidX.")
    override fun onBackPressed() {
        val who = NowPlaying.clientName.ifEmpty { "your device" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.back_title, who))
            .setMessage(R.string.back_message)
            .setPositiveButton(R.string.back_continue) { _, _ ->
                AirPlayService.instance?.inBackground = true
                goHome()
            }
            .setNegativeButton(R.string.back_stop) { _, _ ->
                AirPlayService.instance?.disconnect()
                goHome()
            }
            .show()
    }

    /**
     * Back to the TV's home screen, like the Home key: the whole app goes to the background.
     * (Only finishing this screen could bring back an AirStage screen left underneath it, which
     * would reopen Now Playing straight away.)
     */
    private fun goHome() {
        moveTaskToBack(true)
        finish()
    }

    private fun lyricsVisible() = showLyrics && Lyrics.lines != null

    /** Lyrics (when found and switched on) replace artist and album; otherwise a "▼ Lyrics" hint. */
    private fun renderLyrics() {
        val lines = Lyrics.lines
        val visible = lyricsVisible()
        info.visibility = if (visible) View.INVISIBLE else View.VISIBLE
        lyricsHint.visibility = if (lines != null && !visible) View.VISIBLE else View.GONE
        lyricsView.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || lines == null) return
        lyricsView.setLines(lines.map { it.text })
        lyricsView.setCurrent(Lyrics.indexAt(NowPlaying.positionMs()))
    }

    private fun hideLyrics() {
        lyricsView.visibility = View.GONE
        lyricsHint.visibility = View.GONE
        info.visibility = View.VISIBLE
    }

    private var driftStart = 0L

    /**
     * The blurred background floats: a slow zoom and drift on separate, unrelated periods so it
     * never visibly repeats. Clock-driven (system animations may be off); one GPU transform a frame.
     */
    private val drift = object : Runnable {
        override fun run() {
            val t = (SystemClock.uptimeMillis() - driftStart) / 1000.0
            val w = backdrop.width
            val h = backdrop.height
            val scale = (1.22 + 0.06 * sin(t * 2 * PI / 23)).toFloat()
            backdrop.scaleX = scale
            backdrop.scaleY = scale
            backdrop.translationX = (w * 0.05 * sin(t * 2 * PI / 31)).toFloat()
            backdrop.translationY = (h * 0.04 * cos(t * 2 * PI / 37)).toFloat()
            backdrop.postOnAnimation(this)
        }
    }

    /** Re-setting identical text would restart a running scroll from the beginning. */
    private fun setIfChanged(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    /** TV remote → iPhone player while music plays: OK / play-pause, ◀ previous, ▶ next. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val command = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> AirPlayService.Remote.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> AirPlayService.Remote.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> AirPlayService.Remote.PAUSE
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> AirPlayService.Remote.NEXT
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND -> AirPlayService.Remote.PREVIOUS
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                if (Lyrics.lines == null) return super.onKeyDown(keyCode, event)
                showLyrics = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                getSharedPreferences("airplay", MODE_PRIVATE).edit().putBoolean("show_lyrics", showLyrics).apply()
                renderLyrics()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount > 0) return true // holding a key: one command, not a burst
        if (AirPlayService.instance?.remote(command) != true) return super.onKeyDown(keyCode, event)
        when (command) {
            AirPlayService.Remote.NEXT -> showHint(getString(R.string.remote_next))
            AirPlayService.Remote.PREVIOUS -> showHint(getString(R.string.remote_previous))
            else -> {}
        }
        return true
    }

    private fun showHint(text: String) {
        hint = text
        hintUntil = SystemClock.uptimeMillis() + 1500
        renderProgress()
    }

    private fun renderProgress() {
        val status = if (hint != null && SystemClock.uptimeMillis() < hintUntil) hint
            else if (NowPlaying.active && NowPlaying.paused) getString(R.string.paused) else ""
        if (remoteStatus.text.toString() != status) remoteStatus.text = status
        // Nothing playing yet (connected, or the iPhone set up a paused song): no time bar.
        val dur = if (NowPlaying.active) NowPlaying.durationMs else 0
        val visible = if (dur > 0) View.VISIBLE else View.INVISIBLE
        progress.visibility = visible
        elapsed.visibility = visible
        remaining.visibility = visible
        if (dur <= 0) return
        val pos = NowPlaying.positionMs()
        progress.progress = (pos * 1000 / dur).toInt()
        elapsed.text = time(pos)
        // Rounded up, as the iPhone shows it (elapsed is rounded down).
        remaining.text = "-" + time(dur - pos + 999)
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        /** Warm ivory: the colour scheme when there is no usable album colour. */
        private const val IVORY = 0xFFFBF3E6.toInt()
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

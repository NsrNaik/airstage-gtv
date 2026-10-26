package dev.airplaytv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.pow

/**
 * Full-screen player for AirPlay video handed over as a URL (the YouTube app's HLS stream,
 * served by UxPlay at http://localhost:<port>/master.m3u8). The iPhone controls it through
 * [VideoController]; the playback state is reported back for its /playback-info polls.
 */
@OptIn(UnstableApi::class)
class VideoActivity : Activity(), VideoController.Target {

    private lateinit var view: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var status: TextView
    private var statusText = 0
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var finished = false
    private var loadGeneration = 0

    private val reporter = object : Runnable {
        override fun run() {
            report()
            // The iPhone polls playback state while it is in control (every second, every 30 s after
            // 15 minutes). If it stops (it gave up, moved to another receiver, or went out of range),
            // close rather than leave a video playing that nothing can pause.
            if (NativeBridge.nativeMsSincePlaybackPoll() > ORPHAN_TIMEOUT_MS) {
                Log.i(TAG, "iPhone stopped polling: closing the video")
                finish()
                return
            }
            main.postDelayed(this, 500)
        }
    }

    /** Animates the "…" of the status line until the first frame is on screen. */
    private val statusTicker = object : Runnable {
        override fun run() {
            if (overlay.visibility != View.VISIBLE || statusText == 0) return
            if (statusText == R.string.stream_failed) {
                status.text = getString(statusText)
            } else {
                // Always three dots, the unused ones transparent, so the centred line never shifts.
                val shown = animatedEllipsis().length
                status.text = SpannableString(getString(statusText) + "...").apply {
                    setSpan(ForegroundColorSpan(0), length - 3 + shown, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            main.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply {
            setBackgroundColor(0xff000000.toInt())
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            controllerAutoShow = false
        }
        setContentView(FrameLayout(this).apply {
            addView(view)
            addView(buildOverlay())
        })
        VideoController.target = this
        load(intent)
    }

    /** "iPhone → TV" and a status line, shown over the player until the video starts. */
    private fun buildOverlay(): View {
        val d = resources.displayMetrics.density
        val header = ConnectionHeader(this).apply {
            set(NowPlaying.clientName, AirPlayService.deviceName(this@VideoActivity))
            setLive(true) // the stream is on its way
        }
        status = TextView(this).apply {
            textSize = 30f
            setTextColor(0xffffffff.toInt())
            setPadding(0, (16 * d).toInt(), 0, 0)
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xff101014.toInt())
            addView(header, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        return overlay
    }

    private fun showStatus(text: Int) {
        statusText = text
        overlay.visibility = View.VISIBLE
        main.removeCallbacks(statusTicker)
        main.post(statusTicker)
    }

    private fun hideStatus() {
        overlay.visibility = View.GONE
        main.removeCallbacks(statusTicker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val start = intent.getFloatExtra(EXTRA_START, 0f)
        release()
        finished = false
        showStatus(R.string.starting_stream)
        val generation = ++loadGeneration
        Log.i(TAG, "Playing $url from ${start}s")
        // UxPlay fetches the YouTube playlists from the iPhone after /play: wait until the master playlist is served.
        thread(name = "playlist-wait") {
            if (url.startsWith("http://localhost")) waitForPlaylist(url)
            main.post { if (generation == loadGeneration && !isFinishing) startPlayer(url, start) }
        }
    }

    private fun waitForPlaylist(url: String) {
        repeat(40) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 1000
                c.readTimeout = 2000
                val ok = c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("#EXTM3U")
                c.disconnect()
                if (ok) return
            } catch (_: Exception) {
            }
            Thread.sleep(250)
        }
    }

    private fun startPlayer(url: String, startSeconds: Float) {
        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
            if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val http = OkHttpDataSource.Factory(Net.client)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(RetryPolicy())
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
            .build()
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() = hideStatus()

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    finished = true
                    report()
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback failed", error)
                showStatus(R.string.stream_failed)
                finished = true
                report()
            }
        })
        p.setMediaItem(item, (startSeconds * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        view.player = p
        player = p
        main.removeCallbacks(reporter)
        main.post(reporter)
    }

    private fun report() {
        val p = player
        if (p == null) {
            NativeBridge.nativeUpdatePlayback(0.0, 0.0, 0f, false, finished)
            return
        }
        val duration = if (p.duration == C.TIME_UNSET) 0.0 else p.duration / 1000.0
        // Like UxPlay's own player: report the requested rate and "ready", so the iPhone does not
        // read initial buffering as a stall and give up.
        NativeBridge.nativeUpdatePlayback(
            duration,
            p.currentPosition / 1000.0,
            if (p.playWhenReady) 1f else 0f,
            true,
            finished
        )
    }

    /**
     * TV remote while a video plays: OK / play-pause toggles, ◀ ▶ skip 10 s. The video plays on
     * the TV, so this acts on the player here; the iPhone follows through its playback polls.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val action: (() -> Unit)? = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { { p.playWhenReady = !p.playWhenReady } }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { { p.playWhenReady = true } }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { { p.playWhenReady = false } }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { { skip(p, SKIP_MS) } }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { { skip(p, -SKIP_MS) } }
            else -> null
        }
        if (action == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            action()
            report()
            view.showController() // brief progress bar as feedback; it hides by itself
        }
        return true
    }

    private fun skip(p: ExoPlayer, deltaMs: Long) {
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        p.seekTo(if (p.duration == C.TIME_UNSET) target else target.coerceAtMost(p.duration))
    }

    override fun seek(seconds: Float) {
        player?.seekTo((seconds * 1000).toLong())
    }

    override fun rate(rate: Float) {
        player?.playWhenReady = rate > 0f
    }

    override fun setVolume(db: Float) {
        val frac = if (db <= -30f) 0f else ((30f + db) / 30f).coerceIn(0f, 1f)
        player?.volume = if (frac == 0f) 0f else 10f.pow(-30f * (1f - frac) / 20f)
    }

    override fun close() {
        finish()
    }

    private fun release() {
        main.removeCallbacks(reporter)
        view.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        // Tell the iPhone playback is over, so it stops showing the video as playing on the TV.
        finished = true
        report()
        release()
        if (VideoController.target === this) VideoController.target = null
        super.onDestroy()
    }

    /** UxPlay may still be fetching media playlists from the iPhone: keep retrying for a while. */
    private class RetryPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (info.errorCount <= 10) 500 else C.TIME_UNSET

        override fun getMinimumLoadableRetryCount(dataType: Int) = 10
    }

    companion object {
        private const val TAG = "VideoActivity"


        private const val ORPHAN_TIMEOUT_MS = 75_000L
        private const val SKIP_MS = 10_000L
        const val EXTRA_URL = "url"
        const val EXTRA_START = "start"
    }
}

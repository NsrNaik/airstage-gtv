package dev.airplaytv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * What the iPhone says is playing (title/artist/album, cover art, track progress).
 * Updated from UxPlay's threads, read and observed on the main thread.
 */
object NowPlaying {

    data class Track(val title: String = "", val artist: String = "", val album: String = "")

    var track = Track()
        private set
    var cover: Bitmap? = null
        private set
    var durationMs = 0L
        private set
    private var positionMs = 0L
    private var positionAt = 0L
    private var startRtp = -1L
    @Volatile private var pausedAt = 0L

    /** How far ahead of playback the sender streams (~1.8 s); its progress reports are that far ahead. */
    @Volatile var senderLeadMs = 0L

    // The RTP timestamp of the packet being heard, and when (System.nanoTime), from the audio writer.
    @Volatile private var heardRtp = -1L
    @Volatile private var heardAtNanos = 0L
    @Volatile var paused = false
        private set
    /** Name of the sending device, e.g. "Alex's iPhone". */
    var clientName = ""
        private set
    /** True while an iPhone is connected, before or during playback. */
    var connected = false
        private set
    /** PIN to show while a new device pairs, else null. */
    var pin: String? = null
        private set
    /** True once AirPlay sound is playing in this session (not merely set up). */
    @Volatile var active = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private const val PIN_TIMEOUT_MS = 120_000L
    private const val RESUME_GRACE_MS = 400L
    private val observers = mutableListOf<() -> Unit>()

    fun observe(o: () -> Unit) { observers += o }
    fun unobserve(o: () -> Unit) { observers -= o }

    /** Called by the audio writer for each packet: what is being heard, and when. */
    fun heard(rtpTime: Long, atNanos: Long) {
        heardRtp = rtpTime
        heardAtNanos = atNanos
        // Audio is being played again after a pause: the iPhone resumed (it does not always send a
        // progress report first). Ignore stragglers in the first moments after the pause.
        if (paused && rtpTime >= 0 && SystemClock.uptimeMillis() - pausedAt > RESUME_GRACE_MS) {
            post { if (paused) setPausedNow(false) }
        }
    }

    /**
     * Playback position: from the packet actually being heard when there is one (this matches
     * the iPhone), else extrapolated from the last progress report.
     */
    fun positionMs(): Long {
        if (!paused) heardPositionMs()?.let { return it }
        // The sender's progress report describes what it has sent: take off how far ahead it sends.
        val p = if (paused) positionMs else positionMs + (SystemClock.elapsedRealtime() - positionAt) - senderLeadMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /** Position of the packet being heard, or null if none (yet) for this track. */
    private fun heardPositionMs(): Long? {
        if (heardRtp >= 0 && startRtp >= 0) {
            val sinceMs = ((System.nanoTime() - heardAtNanos) / 1_000_000).coerceIn(-1000, 500)
            val p = ((heardRtp - startRtp) and 0xffffffffL) * 1000 / AudioOutput.RATE + sinceMs
            if (durationMs > 0 && p in 0..durationMs + 2000) return p.coerceAtMost(durationMs)
        }
        return null
    }

    fun setMetadata(dmap: ByteArray) {
        val t = parseDmap(dmap) ?: return
        post { track = t }
    }

    /** Debug builds only ([Demo]): a fake playing track, for screenshots. */
    internal fun demo(t: Track, art: Bitmap, lengthMs: Long, atMs: Long, client: String) = post {
        clientName = client
        connected = true
        active = true
        paused = false
        pin = null
        track = t
        cover = art
        durationMs = lengthMs
        positionMs = atMs
        positionAt = SystemClock.elapsedRealtime()
        startRtp = -1
        heardRtp = -1
        senderLeadMs = 0
    }

    fun setCover(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        post { cover = bmp }
    }

    /** RTP timestamps at 44.1 kHz, as sent by the client with SET_PARAMETER progress. */
    fun setProgress(start: Long, current: Long, end: Long) {
        val rate = AudioOutput.RATE
        val dur = ((end - start) and 0xffffffffL) * 1000 / rate
        val pos = ((current - start) and 0xffffffffL) * 1000 / rate
        post {
            startRtp = start
            heardRtp = -1 // a seek or new track: wait for its first packet to be heard
            durationMs = dur
            positionMs = pos
            positionAt = SystemClock.elapsedRealtime()
            paused = false
        }
    }

    fun setPaused(p: Boolean) = post { setPausedNow(p) }

    /** Main thread. */
    private fun setPausedNow(p: Boolean) {
        if (p && !paused) {
            // Freeze at what was last heard, then forget it: the flushed audio is never played.
            positionMs = positionMs()
            heardRtp = -1
            pausedAt = SystemClock.uptimeMillis()
        }
        positionAt = SystemClock.elapsedRealtime()
        paused = p
    }

    fun setActive(a: Boolean) = post {
        active = a
        if (a) pin = null
    }

    /**
     * Shows [p] until pairing succeeds, audio starts or it expires. Not cleared on disconnect:
     * the iPhone drops the connection while the user types the PIN and then reconnects.
     */
    fun setPin(p: String?) = post {
        pin = p
        if (p != null) {
            main.postDelayed({ if (pin == p) post { pin = null } }, PIN_TIMEOUT_MS)
        }
    }

    /** An iPhone connected: show it, waiting for audio or video. */
    fun setConnected(name: String) = post {
        clientName = name
        connected = true
    }

    fun clear() = post {
        connected = false
        active = false
        track = Track()
        cover = null
        durationMs = 0
        positionMs = 0
        paused = false
    }

    private fun post(change: () -> Unit) {
        main.post {
            change()
            observers.toList().forEach { it() }
        }
    }

    /** Parses a DMAP "mlit" listing item (minm = title, asar = artist, asal = album). */
    internal fun parseDmap(b: ByteArray): Track? {
        fun tag(i: Int) = String(b, i, 4, Charsets.US_ASCII)
        fun len(i: Int) = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
        if (b.size < 8 || tag(0) != "mlit") return null
        var title = ""
        var artist = ""
        var album = ""
        var i = 8
        while (i + 8 <= b.size) {
            val t = tag(i)
            val n = len(i + 4)
            if (n < 0 || i + 8 + n > b.size) break
            val v = String(b, i + 8, n, Charsets.UTF_8)
            when (t) {
                "minm" -> title = v
                "asar" -> artist = v
                "asal" -> album = v
            }
            i += 8 + n
        }
        return Track(title, artist, album)
    }
}

/** "", ".", "..", "..." cycling with the clock, for status lines (animations may be off on TVs). */
fun animatedEllipsis(): String = ".".repeat(((android.os.SystemClock.uptimeMillis() / 450) % 4).toInt())

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
    var paused = false
        private set
    /** Name of the sending device, e.g. "Alex's iPhone". */
    var clientName = ""
        private set
    /** True while an AirPlay audio session is open. */
    var active = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<() -> Unit>()

    fun observe(o: () -> Unit) { observers += o }
    fun unobserve(o: () -> Unit) { observers -= o }

    /** Playback position, extrapolated from the last progress report while playing. */
    fun positionMs(): Long {
        val p = if (paused) positionMs else positionMs + (SystemClock.elapsedRealtime() - positionAt)
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p
    }

    fun setMetadata(dmap: ByteArray) {
        val t = parseDmap(dmap) ?: return
        post { track = t }
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
            durationMs = dur
            positionMs = pos
            positionAt = SystemClock.elapsedRealtime()
            paused = false
        }
    }

    fun setPaused(p: Boolean) = post {
        if (p && !paused) {
            positionMs = positionMs()
        }
        positionAt = SystemClock.elapsedRealtime()
        paused = p
    }

    fun setActive(a: Boolean) = post { active = a }

    fun setClient(name: String) = post { clientName = name }

    fun clear() = post {
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

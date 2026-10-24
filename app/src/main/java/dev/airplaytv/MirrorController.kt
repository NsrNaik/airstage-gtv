package dev.airplaytv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue

/**
 * Hands screen-mirroring frames from UxPlay's thread to the [MirrorActivity] decoder. Frames that
 * arrive before the screen is up are queued; the frame carrying SPS/PPS is kept so a decoder that
 * starts later can always begin with it.
 */
object MirrorController {

    class Frame(val data: ByteArray, val ptsUs: Long)

    interface Target {
        fun formatChanged(h265: Boolean, width: Int, height: Int)
        fun close()
    }

    val frames = ArrayBlockingQueue<Frame>(240)

    @Volatile var h265 = false
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    /** The last frame with SPS/PPS (stream parameters): a decoder must see it first. */
    @Volatile var config: Frame? = null
        private set

    @Volatile var target: Target? = null
    private val main = Handler(Looper.getMainLooper())

    fun format(h265: Boolean, width: Int, height: Int) {
        this.h265 = h265
        this.width = width
        this.height = height
        main.post { target?.formatChanged(h265, width, height) }
    }

    fun frame(data: ByteArray, ptsUs: Long) {
        val f = Frame(data, ptsUs)
        if (hasParameterSets(data)) config = f
        if (!frames.offer(f)) {
            frames.poll()
            frames.offer(f)
        }
    }

    /** Mirroring ended: drop queued frames and close the screen. */
    fun stop() {
        frames.clear()
        config = null
        width = 0
        height = 0
        main.post { target?.close() }
    }

    val active get() = width > 0

    /** Whether an Annex-B access unit starts with an SPS (H.264 type 7, H.265 VPS 32 / SPS 33). */
    private fun hasParameterSets(d: ByteArray): Boolean {
        if (d.size < 5) return false
        val header = d[4].toInt()
        return if (h265) ((header shr 1) and 0x3f) in 32..33 else (header and 0x1f) == 7
    }
}

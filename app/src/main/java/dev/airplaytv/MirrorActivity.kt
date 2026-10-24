package dev.airplaytv

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import java.util.concurrent.TimeUnit

/**
 * Full-screen AirPlay screen mirroring (what iOS sends for Photos pictures and videos, and for
 * Control Center's Screen Mirroring). Decodes the iPhone's H.264 stream with the TV's hardware
 * decoder straight onto a surface. The stream is a live re-encode: SDR, not the original file.
 */
@OptIn(UnstableApi::class)
class MirrorActivity : Activity(), MirrorController.Target, SurfaceHolder.Callback {

    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    @Volatile private var decoding = false
    private var decoder: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        frame = AspectRatioFrameLayout(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(surfaceView)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(frame, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        })
        MirrorController.target = this
        setAspect(MirrorController.width, MirrorController.height)
    }

    override fun onDestroy() {
        stopDecoder()
        if (MirrorController.target === this) MirrorController.target = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen (Home, another app) ends mirroring on the TV side.
        finish()
    }

    /** Back stops mirroring: the iPhone is disconnected and shows its own screen again. */
    @Deprecated("Back is handled directly; onBackPressedDispatcher needs AndroidX.")
    override fun onBackPressed() {
        AirPlayService.instance?.disconnect()
        finish()
    }

    override fun formatChanged(h265: Boolean, width: Int, height: Int) {
        setAspect(width, height)
        // A new resolution or codec (rotation, Photos switching to a video): restart the decoder.
        if (surfaceView.holder.surface?.isValid == true) startDecoder()
    }

    override fun close() = finish()

    private fun setAspect(width: Int, height: Int) {
        if (width > 0 && height > 0) frame.setAspectRatio(width.toFloat() / height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startDecoder()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = stopDecoder()

    private fun startDecoder() {
        stopDecoder()
        if (!MirrorController.active) return
        val surface = surfaceView.holder.surface
        decoding = true
        decoder = Thread({ decode(surface) }, "mirror-decoder").apply { start() }
    }

    private fun stopDecoder() {
        decoding = false
        decoder?.interrupt()
        decoder?.join(500)
        decoder = null
    }

    private fun decode(surface: android.view.Surface) {
        val mime = if (MirrorController.h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, MirrorController.width, MirrorController.height)
        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start $mime decoder", e)
            return
        }
        Log.i(TAG, "Decoding $mime ${MirrorController.width}x${MirrorController.height}")
        val info = MediaCodec.BufferInfo()
        // Start from the stream parameters, then whatever is queued after them.
        val config = MirrorController.config
        var pending: MirrorController.Frame? = config
        var configFed = false
        try {
            while (decoding) {
                val f = pending ?: MirrorController.frames.poll(20, TimeUnit.MILLISECONDS)
                pending = null
                when {
                    f == null -> {}
                    f === config && configFed -> {} // fed first already; it is also in the queue
                    feed(codec, f) -> if (f === config) configFed = true
                    else -> pending = f // decoder busy: retry this frame
                }
                drain(codec, info)
            }
        } catch (_: InterruptedException) {
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder stopped", e)
        } finally {
            try { codec.stop() } catch (_: IllegalStateException) {}
            codec.release()
        }
    }

    /** Queues one access unit; false if the decoder has no free input buffer yet. */
    private fun feed(codec: MediaCodec, f: MirrorController.Frame): Boolean {
        val index = codec.dequeueInputBuffer(20_000)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index)!!
        buffer.clear()
        val size = if (f.data.size <= buffer.capacity()) f.data.size else 0
        if (size > 0) buffer.put(f.data)
        codec.queueInputBuffer(index, 0, size, f.ptsUs, 0)
        return true
    }

    /** Shows each decoded frame at once (live mirroring) and follows size changes. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> codec.releaseOutputBuffer(out, true)
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    val w = of.getInteger("crop-right", of.getInteger(MediaFormat.KEY_WIDTH) - 1) -
                        of.getInteger("crop-left", 0) + 1
                    val h = of.getInteger("crop-bottom", of.getInteger(MediaFormat.KEY_HEIGHT) - 1) -
                        of.getInteger("crop-top", 0) + 1
                    runOnUiThread { setAspect(w, h) }
                }
                else -> return
            }
        }
    }

    companion object {
        private const val TAG = "MirrorActivity"
    }
}

package dev.airplaytv

import android.media.AudioAttributes
import android.media.AudioTimestamp
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log2
import kotlin.math.pow

/**
 * Plays AirPlay audio: 44.1 kHz stereo 16-bit PCM (ALAC is decoded natively) or AAC frames
 * (decoded with MediaCodec). Packets are queued and written to an AudioTrack on a writer thread
 * so UxPlay's network thread never blocks on audio output.
 *
 * The sender stamps each packet with when it should be heard (about 2 s after it is sent); the
 * writer holds packets until then, so the TV stays in step with the iPhone's clock and lyrics.
 */
class AudioOutput {

    /** [dueNanos]: System.nanoTime() when it should be heard, 0 for "as soon as possible". */
    private class Packet(val samples: ShortArray, val dueNanos: Long, val rtpTime: Long)

    private val queue = ArrayBlockingQueue<Packet>(QUEUE_PACKETS)
    private val framesWritten = AtomicLong()
    private val timestamp = AudioTimestamp()
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var running = false
    @Volatile private var gain = 1f
    private var aac: MediaCodec? = null

    @Synchronized
    fun start(ct: Int) {
        stopAac()
        if (ct == CT_AAC_LC || ct == CT_AAC_ELD) {
            startAac(ct)
        }
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, RATE)) // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(gain)
        t.play()
        track = t
        running = true
        writer = Thread({ writeLoop(t) }, "airplay-audio").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        writer?.interrupt()
        writer = null
        queue.clear()
        track?.run {
            pause()
            flush()
            release()
        }
        track = null
        framesWritten.set(0)
        stopAac()
    }

    fun pcm(samples: ShortArray, delayNs: Long = 0, rtpTime: Long = -1) {
        if (!running) return
        val due = if (delayNs > 0) System.nanoTime() + delayNs else 0L
        if (delayNs > 0) NowPlaying.senderLeadMs = delayNs / 1_000_000
        val packet = Packet(samples, due, rtpTime)
        if (!queue.offer(packet)) {
            // The writer has fallen behind: drop the oldest packet rather than build up delay.
            queue.poll()
            queue.offer(packet)
        }
    }

    fun flush() {
        queue.clear()
        track?.run {
            pause()
            flush()
            framesWritten.set(0)
            play()
        }
    }

    /** AirPlay volume is in dB, -30 (min) to 0 (max), -144 for mute; tapered like UxPlay/shairport-sync. */
    fun setVolume(db: Float) {
        val frac = if (db <= -30f) 0f else ((30f + db) / 30f).coerceIn(0f, 1f)
        gain = if (frac == 0f) 0f else {
            val dB = maxOf(10f * log2(frac), -30f * (1f - frac))
            10f.pow(dB / 20f)
        }
        track?.setVolume(gain)
    }

    fun aacFrame(frame: ByteArray) {
        val codec = synchronized(this) { aac } ?: return
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
                codec.queueInputBuffer(index, 0, frame.size, 0, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                val buf = codec.getOutputBuffer(out)!!
                buf.position(info.offset).limit(info.offset + info.size)
                val shorts = ShortArray(info.size / 2)
                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                codec.releaseOutputBuffer(out, false)
                pcm(shorts)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AAC decode failed", e)
        }
    }

    private fun startAac(ct: Int) {
        val csd = if (ct == CT_AAC_ELD) byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
                  else byteArrayOf(0x12, 0x10)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        aac = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "No AAC decoder for ct=$ct", e)
            null
        }
    }

    private fun stopAac() {
        aac?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        aac = null
    }

    private fun writeLoop(t: AudioTrack) {
        while (running) {
            val packet = try {
                queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            var heardAt = nextFrameNanos(t)
            if (packet.dueNanos > 0) {
                val wait = packet.dueNanos - heardAt
                if (wait < -LATE_NS) continue // far behind (e.g. after a stall): skip to catch up
                if (wait > 1_000_000) {
                    try {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                        break
                    }
                    heardAt = packet.dueNanos
                }
            }
            val n = t.write(packet.samples, 0, packet.samples.size)
            if (n > 0) framesWritten.addAndGet((n / 2).toLong())
            if (packet.rtpTime >= 0) NowPlaying.heard(packet.rtpTime, heardAt)
        }
    }

    /** When a frame written now would be heard: from the track's timestamp, or an estimate. */
    private fun nextFrameNanos(t: AudioTrack): Long {
        val now = System.nanoTime()
        val written = framesWritten.get()
        val estimate = if (t.getTimestamp(timestamp)) {
            timestamp.nanoTime + (written - timestamp.framePosition) * 1_000_000_000L / RATE
        } else {
            now + maxOf(0L, written - t.playbackHeadPosition.toLong()) * 1_000_000_000L / RATE + HW_LATENCY_NS
        }
        // After an underrun the timestamp is stale: nothing can be heard sooner than the hardware allows.
        return maxOf(estimate, now + HW_LATENCY_NS)
    }

    companion object {
        private const val TAG = "AudioOutput"
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        const val RATE = 44100
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
        // ALAC packets are 352 frames (8 ms): 600 packets is ~4.8 s, room for the ~2 s the sender
        // sends ahead plus network jitter.
        private const val QUEUE_PACKETS = 600
        private const val HW_LATENCY_NS = 40_000_000L
        private const val LATE_NS = 200_000_000L
    }
}

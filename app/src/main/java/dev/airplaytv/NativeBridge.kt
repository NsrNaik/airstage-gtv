package dev.airplaytv

/**
 * JNI surface of libairplaytv (UxPlay's AirPlay server). The on* methods are called by
 * native code on UxPlay's network threads; they forward to [listener].
 */
object NativeBridge {

    /** UxPlay's reset_type_t values (raop.h). */
    const val RESET_NOHOLD = 0
    const val RESET_RTP_SHUTDOWN = 1
    const val RESET_HLS_SHUTDOWN = 2
    const val RESET_HLS_EOS = 3

    interface Listener {
        fun registerService(type: String, name: String, port: Int, txt: Map<String, String>)
        fun unregisterService(type: String)
        fun audioFormat(ct: Int, spf: Int)
        fun audioPcm(pcm: ShortArray)
        fun audioAac(frame: ByteArray)
        fun audioFlush()
        fun volume(db: Float)
        fun coverArt(jpeg: ByteArray)
        fun metadata(dmap: ByteArray)
        fun progress(start: Long, current: Long, end: Long)
        fun connections(delta: Int)
        fun client(name: String, model: String)
        fun videoPlay(url: String, startSeconds: Float)
        fun videoScrub(seconds: Float)
        fun videoRate(rate: Float)
        fun videoStop()
        fun videoReset(type: Int)
        fun connectionLost(reason: Int)
    }

    @Volatile
    var listener: Listener? = null

    init {
        System.loadLibrary("airplaytv")
    }

    external fun nativeStart(name: String, hwAddr: ByteArray, keyFile: String, lang: String): Int
    external fun nativeStop()
    external fun nativeRestartHttpd()
    /** Milliseconds since the iPhone last polled the video's playback state. */
    external fun nativeMsSincePlaybackPoll(): Long
    external fun nativeUpdatePlayback(duration: Double, position: Double, rate: Float, ready: Boolean, finished: Boolean)

    @JvmStatic
    fun onRegisterService(type: String, name: String, port: Int, keys: Array<String>, values: Array<String>) {
        listener?.registerService(type, name, port, keys.zip(values).toMap())
    }

    @JvmStatic fun onUnregisterService(type: String) { listener?.unregisterService(type) }
    @JvmStatic fun onAudioFormat(ct: Int, spf: Int) { listener?.audioFormat(ct, spf) }
    @JvmStatic fun onAudioPcm(pcm: ShortArray) { listener?.audioPcm(pcm) }
    @JvmStatic fun onAudioAac(frame: ByteArray) { listener?.audioAac(frame) }
    @JvmStatic fun onAudioFlush() { listener?.audioFlush() }
    @JvmStatic fun onVolume(db: Float) { listener?.volume(db) }
    @JvmStatic fun onCoverArt(jpeg: ByteArray) { listener?.coverArt(jpeg) }
    @JvmStatic fun onMetadata(dmap: ByteArray) { listener?.metadata(dmap) }
    @JvmStatic fun onProgress(start: Long, current: Long, end: Long) { listener?.progress(start, current, end) }
    @JvmStatic fun onConnections(delta: Int) { listener?.connections(delta) }
    @JvmStatic fun onClient(name: String, model: String) { listener?.client(name, model) }
    @JvmStatic fun onVideoPlay(url: String, startSeconds: Float) { listener?.videoPlay(url, startSeconds) }
    @JvmStatic fun onVideoScrub(seconds: Float) { listener?.videoScrub(seconds) }
    @JvmStatic fun onVideoRate(rate: Float) { listener?.videoRate(rate) }
    @JvmStatic fun onVideoStop() { listener?.videoStop() }
    @JvmStatic fun onVideoReset(type: Int) { listener?.videoReset(type) }
    @JvmStatic fun onConnReset(reason: Int) { listener?.connectionLost(reason) }
}

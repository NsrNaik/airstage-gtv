package dev.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Locale

/**
 * Foreground service that runs UxPlay's AirPlay server, announces it with NsdManager,
 * plays the audio, and opens the Now Playing and video screens.
 */
class AirPlayService : Service(), NativeBridge.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var nsd: NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val registrations = mutableMapOf<String, NsdManager.RegistrationListener>()
    /** What was announced per service type, to announce it again on "Refresh on network". */
    private class Announcement(val name: String, val port: Int, val txt: Map<String, String>)
    private val announced = mutableMapOf<String, Announcement>()
    private val audio = AudioOutput()
    private var openConnections = 0
    private lateinit var paired: PairedDevices
    private lateinit var dacp: DacpRemote
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // Set when another app took the TV's audio: our output is muted and the iPhone was asked to
    // pause. Playback resumes when the iPhone starts sending again after the pause's flush.
    @Volatile private var pausedForOtherMedia = false
    @Volatile private var flushedSincePause = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseForOtherMedia()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        nsd = getSystemService(NsdManager::class.java)
        paired = PairedDevices(this)
        dacp = DacpRemote(nsd)
        audioManager = getSystemService(AudioManager::class.java)
        instance = this
        multicastLock = getSystemService(WifiManager::class.java)
            .createMulticastLock("airplay-tv").apply { setReferenceCounted(false); acquire() }
        worker = Handler(HandlerThread("airplay-control").apply { start() }.looper)

        NativeBridge.listener = this
        val name = airPlayName(this)
        startedName = name
        worker.post {
            val keyFile = File(filesDir, "airplay-key.pem").absolutePath
            val port = NativeBridge.nativeStart(
                name, hardwareAddress(), keyFile, Locale.getDefault().toLanguageTag(), requirePin(this)
            )
            Log.i(TAG, "AirPlay server \"$name\" started on port $port")
            status = if (port > 0) "Ready: \"$name\" (port $port)" else "Failed to start the AirPlay server"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (instance === this) instance = null
        abandonFocus()
        NativeBridge.listener = null
        worker.post { NativeBridge.nativeStop() }
        worker.looper.quitSafely()
        registrations.keys.toList().forEach { unregisterService(it) }
        audio.stop()
        multicastLock?.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay receiver", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airstage)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Waiting for AirPlay as \"${airPlayName(this)}\"")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
    }

    /** A random, locally administered MAC, kept so the iPhone sees the same receiver every time. */
    private fun hardwareAddress(): ByteArray {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("hwaddr", null)?.let { hex ->
            return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xfc) or 0x02).toByte()
        prefs.edit().putString("hwaddr", b.joinToString("") { "%02x".format(it) }).apply()
        return b
    }

    // ---- NativeBridge.Listener (called on UxPlay threads) ----

    override fun registerService(type: String, name: String, port: Int, txt: Map<String, String>) {
        main.post { doRegister(type, name, port, txt) }
    }

    private fun doRegister(type: String, name: String, port: Int, txt: Map<String, String>) {
        unregisterService(type)
        announced[type] = Announcement(name, port, txt)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                Log.i(TAG, "Announced ${s.serviceName} $type")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Announce $type failed: $code")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        registrations[type] = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun unregisterService(type: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { unregisterService(type) }
            return
        }
        registrations.remove(type)?.let {
            try { nsd.unregisterService(it) } catch (_: IllegalArgumentException) {}
        }
    }

    override fun audioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        audio.start(ct)
        if (usingScreen) { // sound of a screen-mirroring session: no Now Playing screen
            requestFocus()
            return
        }
        // iOS sets the stream up as soon as the TV is picked, even while the song is paused: show
        // "Connected" for now; the song screen (and the TV's audio focus) wait for actual sound.
        NowPlaying.setConnected(NowPlaying.clientName)
        main.post { if (!inBackground) show(NowPlayingActivity::class.java, null) }
    }

    /** True once sound has arrived in this session (see [audioFormat]). */
    @Volatile private var soundStarted = false

    override fun audioPcm(pcm: ShortArray, delayNs: Long, rtpTime: Long) {
        if (!soundStarted) {
            soundStarted = true
            requestFocus()
            NowPlaying.setActive(true)
        }
        if (resumeIfAllowed()) audio.pcm(pcm, delayNs, rtpTime)
    }

    override fun audioAac(frame: ByteArray) {
        if (resumeIfAllowed()) audio.aacFrame(frame)
    }

    /** False while paused for other media; true again once the iPhone resumes after the pause. */
    private fun resumeIfAllowed(): Boolean {
        if (!pausedForOtherMedia) return true
        if (!flushedSincePause) return false
        // The iPhone paused (flush) and is now sending again: the user pressed play.
        pausedForOtherMedia = false
        requestFocus()
        return true
    }

    override fun audioFlush() {
        audio.flush()
        if (pausedForOtherMedia) flushedSincePause = true
        NowPlaying.setPaused(true)
    }

    /** iOS tears the audio stream down on pause (and sets it up again on play). */
    override fun audioStopped() {
        audio.flush()
        if (pausedForOtherMedia) flushedSincePause = true
        NowPlaying.setPaused(true)
    }

    /** Another app (YouTube, Netflix…) started playing on the TV: go quiet and pause the iPhone. */
    private fun pauseForOtherMedia() {
        if (!NowPlaying.active || pausedForOtherMedia) return
        Log.i(TAG, "Audio focus lost: pausing AirPlay and the iPhone")
        pausedForOtherMedia = true
        flushedSincePause = false
        audio.flush()
        NowPlaying.setPaused(true)
        dacp.pause()
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioOutput.ATTRIBUTES)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        pausedForOtherMedia = false
    }

    /** The name the receiver was started under (the TV's device name + " AirStage"). */
    private var startedName = ""

    /**
     * "Refresh on network" button: if the TV was renamed (Settings → System → About → Device
     * name) since the receiver started, restart it under the new name; otherwise re-announce.
     */
    fun refresh() {
        if (airPlayName(this) != startedName) {
            Log.i(TAG, "Device renamed: restarting as \"${airPlayName(this)}\"")
            restart(this)
        } else {
            reannounce()
        }
    }

    /**
     * "Refresh on network": withdraw and re-announce the AirPlay services, for when the TV has
     * dropped out of Apple devices' AirPlay lists (Wi-Fi reconnect, sleep). Main thread.
     */
    fun reannounce() {
        val again = announced.toMap()
        Log.i(TAG, "Re-announcing ${again.keys}")
        again.keys.forEach { unregisterService(it) }
        // Give the withdrawal a moment to go out, or the same name is refused as a conflict.
        main.postDelayed({ again.forEach { (type, a) -> doRegister(type, a.name, a.port, a.txt) } }, 1500)
    }

    /**
     * "Continue in background" was chosen: don't bring Now Playing back by itself (on the next
     * song, after pause/play, on reconnect) until the session ends, the user opens the app, or a
     * video or mirroring starts.
     */
    @Volatile var inBackground = false

    /** TV remote → iPhone player (DACP), from the Now Playing screen only (not in the background). */
    enum class Remote { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS }

    fun remote(command: Remote): Boolean {
        if (!dacp.connected) return false // also from "Connected": OK starts the paused song
        when (command) {
            Remote.PLAY_PAUSE -> dacp.playPause()
            Remote.PLAY -> dacp.play()
            Remote.PAUSE -> dacp.pause()
            Remote.NEXT -> dacp.next()
            Remote.PREVIOUS -> dacp.previous()
        }
        return true
    }

    /** "Stop & disconnect" from the TV: drop the iPhone, which falls back to its own speaker. */
    fun disconnect() {
        Log.i(TAG, "Disconnecting the AirPlay client")
        inBackground = false
        soundStarted = false
        worker.post { NativeBridge.nativeDisconnect() }
        MirrorController.stop()
        audio.stop()
        abandonFocus()
        dacp.clear()
        NowPlaying.clear()
    }

    override fun volume(db: Float) {
        audio.setVolume(db)
        VideoController.setVolume(db)
    }

    override fun client(name: String, model: String) {
        NowPlaying.setConnected(name)
        // Show who connected straight away, unless a video is already on screen.
        main.post { if (VideoController.target == null && !inBackground) show(NowPlayingActivity::class.java, null) }
    }

    override fun showPin(pin: String) {
        NowPlaying.setPin(pin)
        main.post { show(NowPlayingActivity::class.java, null) }
    }

    override fun registerClient(deviceId: String, publicKey: String, name: String) {
        Log.i(TAG, "Paired with $name")
        paired.add(publicKey, name)
        NowPlaying.setPin(null)
    }

    override fun isRegistered(publicKey: String) = paired.contains(publicKey)

    override fun remoteControl(activeRemote: String, dacpId: String) = dacp.setSession(activeRemote, dacpId)

    override fun coverArt(jpeg: ByteArray) = NowPlaying.setCover(jpeg)
    override fun metadata(dmap: ByteArray) = NowPlaying.setMetadata(dmap)
    override fun progress(start: Long, current: Long, end: Long) = NowPlaying.setProgress(start, current, end)

    override fun connections(delta: Int) {
        main.post { connectionsChanged(delta) }
    }

    private fun connectionsChanged(delta: Int) {
        openConnections = maxOf(0, openConnections + delta)
        if (openConnections == 0) {
            inBackground = false
            soundStarted = false
            MirrorController.stop()
            audio.stop()
            abandonFocus()
            dacp.clear()
            NowPlaying.clear()
        }
    }

    override fun videoPlay(url: String, startSeconds: Float) {
        main.post { openVideo(url, startSeconds) }
    }

    private fun openVideo(url: String, startSeconds: Float) {
        inBackground = false
        soundStarted = false
        audio.stop()
        NowPlaying.clear()
        show(VideoActivity::class.java) {
            putExtra(VideoActivity.EXTRA_URL, url)
            putExtra(VideoActivity.EXTRA_START, startSeconds)
        }
    }

    override fun videoScrub(seconds: Float) = VideoController.seek(seconds)
    override fun videoRate(rate: Float) = VideoController.rate(rate)
    override fun videoStop() = VideoController.close()

    override fun mirrorFormat(h265: Boolean, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int) {
        val starting = !MirrorController.active
        MirrorController.format(h265, width, height)
        if (starting) main.post {
            inBackground = false
            show(MirrorActivity::class.java, null)
        }
    }

    override fun mirrorFrame(data: ByteArray, ptsUs: Long) = MirrorController.frame(data, ptsUs)

    override fun videoReset(type: Int) {
        when (type) {
            NativeBridge.RESET_NOHOLD, NativeBridge.RESET_RTP_SHUTDOWN, NativeBridge.RESET_RTP_TO_HLS_TEARDOWN ->
                MirrorController.stop()
        }
        when (type) {
            NativeBridge.RESET_NOHOLD, NativeBridge.RESET_HLS_SHUTDOWN, NativeBridge.RESET_HLS_EOS -> VideoController.close()
        }
    }

    override fun connectionLost(reason: Int) {
        Log.w(TAG, "Connection lost (reason $reason); restarting the AirPlay HTTP server")
        VideoController.close()
        MirrorController.stop()
        audio.stop()
        NowPlaying.clear()
        // Not on the calling thread: it may be the HTTP server thread that is being restarted.
        worker.postDelayed({ NativeBridge.nativeRestartHttpd() }, 500)
    }

    private fun show(activity: Class<*>, extras: (Intent.() -> Unit)?) {
        val intent = Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras?.invoke(intent)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open ${activity.simpleName}", e)
        }
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay"

        @Volatile
        var status = "Starting…"
            private set

        /** The running service, for the TV screens' "Stop & disconnect". */
        @Volatile
        var instance: AirPlayService? = null
            private set

        private const val PREFS = "airplay"
        private const val KEY_REQUIRE_PIN = "require_pin"

        /** New devices must enter a PIN shown on the TV (once each). On by default. */
        fun requirePin(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUIRE_PIN, true)

        fun setRequirePin(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REQUIRE_PIN, on).apply()
            restart(context)
        }

        /** Restarts the receiver so a changed setting takes effect (drops any connected device). */
        fun restart(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ start(context) }, 1000)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AirPlayService::class.java))
        }

        /**
         * The name shown in the iPhone's AirPlay menu. Not the bare device name: Android 14's mDNS
         * advertiser refuses a second service with the same instance name (the TV's remote-control
         * service already uses it), even of a different type.
         */
        fun airPlayName(context: Context) = deviceName(context) + " AirStage"

        fun deviceName(context: Context): String =
            Demo.tvName ?: Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                ?: Build.MODEL
    }
}

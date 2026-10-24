package dev.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private val audio = AudioOutput()
    private var openConnections = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        nsd = getSystemService(NsdManager::class.java)
        multicastLock = getSystemService(WifiManager::class.java)
            .createMulticastLock("airplay-tv").apply { setReferenceCounted(false); acquire() }
        worker = Handler(HandlerThread("airplay-control").apply { start() }.looper)

        NativeBridge.listener = this
        val name = airPlayName(this)
        worker.post {
            val keyFile = File(filesDir, "airplay-key.pem").absolutePath
            val port = NativeBridge.nativeStart(name, hardwareAddress(), keyFile, Locale.getDefault().toLanguageTag())
            Log.i(TAG, "AirPlay server \"$name\" started on port $port")
            status = if (port > 0) "Ready: \"$name\" (port $port)" else "Failed to start the AirPlay server"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("AirPlay receiver")
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
        val prefs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
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

    override fun audioFormat(ct: Int, spf: Int) {
        audio.start(ct)
        NowPlaying.setActive(true)
        main.post { show(NowPlayingActivity::class.java, null) }
    }

    override fun audioPcm(pcm: ShortArray) = audio.pcm(pcm)
    override fun audioAac(frame: ByteArray) = audio.aacFrame(frame)

    override fun audioFlush() {
        audio.flush()
        NowPlaying.setPaused(true)
    }

    override fun volume(db: Float) {
        audio.setVolume(db)
        VideoController.setVolume(db)
    }

    override fun client(name: String, model: String) = NowPlaying.setClient(name)
    override fun coverArt(jpeg: ByteArray) = NowPlaying.setCover(jpeg)
    override fun metadata(dmap: ByteArray) = NowPlaying.setMetadata(dmap)
    override fun progress(start: Long, current: Long, end: Long) = NowPlaying.setProgress(start, current, end)

    override fun connections(delta: Int) {
        main.post { connectionsChanged(delta) }
    }

    private fun connectionsChanged(delta: Int) {
        openConnections = maxOf(0, openConnections + delta)
        if (openConnections == 0) {
            audio.stop()
            NowPlaying.clear()
        }
    }

    override fun videoPlay(url: String, startSeconds: Float) {
        main.post { openVideo(url, startSeconds) }
    }

    private fun openVideo(url: String, startSeconds: Float) {
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

    override fun videoReset(type: Int) {
        when (type) {
            NativeBridge.RESET_NOHOLD, NativeBridge.RESET_HLS_SHUTDOWN, NativeBridge.RESET_HLS_EOS -> VideoController.close()
        }
    }

    override fun connectionLost(reason: Int) {
        Log.w(TAG, "Connection lost (reason $reason); restarting the AirPlay HTTP server")
        VideoController.close()
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AirPlayService::class.java))
        }

        /**
         * The name shown in the iPhone's AirPlay menu. Not the bare device name: Android 14's mDNS
         * advertiser refuses a second service with the same instance name (the TV's remote-control
         * service already uses it), even of a different type.
         */
        fun airPlayName(context: Context) = deviceName(context) + " AirPlay"

        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                ?: Build.MODEL
    }
}

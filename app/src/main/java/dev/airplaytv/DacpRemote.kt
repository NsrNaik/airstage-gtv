package dev.airplaytv

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Sends playback commands back to the iPhone over DACP (the remote-control protocol AirPlay
 * senders still expose): the iPhone announces "iTunes_Ctrl_<DACP-ID>" as _dacp._tcp, and accepts
 * GET /ctrl-int/1/<command> with the session's Active-Remote header.
 */
class DacpRemote(private val nsd: NsdManager) {

    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun setSession(activeRemote: String, dacpId: String) {
        this.activeRemote = activeRemote
        this.dacpId = dacpId
    }

    fun clear() {
        activeRemote = null
        dacpId = null
    }

    fun pause() = send("pause")

    private fun send(command: String) {
        val id = dacpId
        val remote = activeRemote
        if (id == null || remote == null) {
            Log.w(TAG, "No DACP session: cannot send $command")
            return
        }
        val wanted = "iTunes_Ctrl_$id"
        var done = false
        lateinit var listener: NsdManager.DiscoveryListener
        val stop = Runnable {
            if (!done) Log.w(TAG, "$wanted not found: $command not sent")
            done = true
            try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) {}
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (done || !info.serviceName.equals(wanted, ignoreCase = true)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        if (done) return
                        done = true
                        main.post(stop)
                        thread(name = "dacp") { request(r, remote, command) }
                    }

                    override fun onResolveFailed(r: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "Resolve $wanted failed: $code")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "DACP discovery failed: $code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices("_dacp._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        main.postDelayed(stop, 5000)
    }

    private fun request(service: NsdServiceInfo, remote: String, command: String) {
        @Suppress("DEPRECATION")
        val host = service.host?.hostAddress?.let { if (it.contains(':')) "[$it]" else it } ?: return
        try {
            val c = URL("http://$host:${service.port}/ctrl-int/1/$command").openConnection() as HttpURLConnection
            c.setRequestProperty("Active-Remote", remote)
            c.connectTimeout = 3000
            c.readTimeout = 3000
            Log.i(TAG, "Sent $command to iPhone: HTTP ${c.responseCode}")
            c.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Sending $command failed", e)
        }
    }

    companion object {
        private const val TAG = "DacpRemote"
    }
}

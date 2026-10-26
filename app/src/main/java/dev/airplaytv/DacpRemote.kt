package dev.airplaytv

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends playback commands back to the iPhone over DACP (the remote-control protocol AirPlay
 * senders still expose): the iPhone announces "iTunes_Ctrl_<DACP-ID>" as _dacp._tcp, and accepts
 * GET /ctrl-int/1/<command> with the session's Active-Remote header.
 *
 * The iPhone's address is looked up once per session (as soon as the session starts) and reused,
 * so a key press reaches the iPhone without a network search. Commands are sent in order.
 */
class DacpRemote(private val nsd: NsdManager) {

    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    /** "host:port" of the iPhone's DACP server for [dacpId], once found. */
    @Volatile private var endpoint: String? = null
    private var discovering = false
    private val waiting = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "dacp") }

    fun setSession(activeRemote: String, dacpId: String) {
        val changed = dacpId != this.dacpId
        this.activeRemote = activeRemote
        this.dacpId = dacpId
        if (changed) {
            endpoint = null
            main.post { discover() } // find the iPhone now, before the first key press
        }
    }

    fun clear() {
        activeRemote = null
        dacpId = null
        endpoint = null
    }

    val connected get() = dacpId != null

    fun pause() = send("pause")
    fun play() = send("play")
    fun playPause() = send("playpause")
    fun next() = send("nextitem")
    fun previous() = send("previtem")

    private fun send(command: String) {
        if (activeRemote == null) {
            Log.w(TAG, "No DACP session: cannot send $command")
            return
        }
        val target = endpoint
        if (target != null) {
            sender.execute { request(target, command, retry = true) }
        } else {
            main.post {
                waiting += command
                discover()
            }
        }
    }

    /** Main thread. Finds "iTunes_Ctrl_<id>", then sends any commands waiting for it. */
    private fun discover() {
        val id = dacpId ?: return
        if (discovering) return
        discovering = true
        val wanted = "iTunes_Ctrl_$id"
        var done = false
        lateinit var listener: NsdManager.DiscoveryListener
        val stop = Runnable {
            if (!done) {
                Log.w(TAG, "$wanted not found: ${waiting.size} command(s) dropped")
                waiting.clear()
            }
            done = true
            discovering = false
            try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) {}
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (done || !sameId(info.serviceName, id)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = r.host?.hostAddress?.let { if (it.contains(':')) "[$it]" else it } ?: return
                        main.post {
                            if (done || dacpId != id) return@post
                            done = true
                            endpoint = "$host:${r.port}"
                            Log.i(TAG, "iPhone remote control at $endpoint")
                            val target = endpoint!!
                            waiting.forEach { c -> sender.execute { request(target, c, retry = false) } }
                            waiting.clear()
                            stop.run()
                        }
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

    /**
     * The iPhone announces "iTunes_Ctrl_" + its DACP-ID as 16 hex digits, but sends the DACP-ID
     * header without leading zeros ("0C62…" vs "C62…"): compare the numbers, not the strings.
     */
    private fun sameId(serviceName: String, id: String): Boolean {
        val prefix = "iTunes_Ctrl_"
        if (!serviceName.startsWith(prefix, ignoreCase = true)) return false
        return serviceName.substring(prefix.length).trimStart('0').equals(id.trimStart('0'), ignoreCase = true)
    }

    /** Sender thread. On a network failure, forgets the address and (once) looks it up again. */
    private fun request(target: String, command: String, retry: Boolean) {
        val remote = activeRemote ?: return
        try {
            val c = URL("http://$target/ctrl-int/1/$command").openConnection() as HttpURLConnection
            c.setRequestProperty("Active-Remote", remote)
            c.connectTimeout = 2000
            c.readTimeout = 2000
            Log.i(TAG, "Sent $command to iPhone: HTTP ${c.responseCode}")
            c.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Sending $command failed: $e")
            if (endpoint == target) endpoint = null
            if (retry) send(command)
        }
    }

    companion object {
        private const val TAG = "DacpRemote"
    }
}

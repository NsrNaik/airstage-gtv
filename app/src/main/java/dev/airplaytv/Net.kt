package dev.airplaytv

import java.net.Inet4Address
import okhttp3.Dns
import okhttp3.OkHttpClient

/** The app's HTTP client, shared by the video player and the lyrics lookup. */
object Net {

    /**
     * Tries IPv4 addresses first. Home networks often hand out IPv6 addresses (e.g. fd00::/8)
     * with no IPv6 route to the internet; plain HttpURLConnection then waits out the full
     * connect timeout on an IPv6 address before anything loads.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String) =
                Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
        })
        // UxPlay's local playlist server answers one request per connection: don't reuse them.
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "localhost") {
                chain.proceed(request.newBuilder().header("Connection", "close").build())
            } else {
                chain.proceed(request)
            }
        }
        .build()
}

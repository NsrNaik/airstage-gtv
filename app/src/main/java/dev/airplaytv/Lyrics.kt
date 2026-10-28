package dev.airplaytv

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Time-synced lyrics from LRCLIB (lrclib.net, an open lyrics database; no account needed).
 * AirPlay does not carry lyrics, so they are looked up by title / artist / album / duration.
 * Kept in memory for the current track only.
 */
object Lyrics {

    class Line(val timeMs: Long, val text: String)

    private const val TAG = "Lyrics"
    private const val API = "https://lrclib.net/api"
    private const val USER_AGENT = "AirStage/3.0 (Android TV AirPlay receiver)"
    private val main = Handler(Looper.getMainLooper())

    /** The track the lyrics below belong to (null: nothing looked up yet), and the length used. */
    private var track: NowPlaying.Track? = null
    private var trackDurationMs = 0L
    /** Synced lines for [track], or null if none were found (or the lookup is still running). */
    var lines: List<Line>? = null
        private set

    /** Debug builds only ([Demo]): use these lines for [t] instead of looking it up. */
    internal fun demo(t: NowPlaying.Track, durationMs: Long, demoLines: List<Line>) {
        track = t
        trackDurationMs = durationMs
        lines = demoLines
    }

    /** Looks the track up once; [onReady] runs on the main thread when lines are known. Main thread. */
    fun request(t: NowPlaying.Track, durationMs: Long, onReady: () -> Unit) {
        if (t.title.isEmpty()) return
        // A new song's title can arrive before its length: look it up again when the length changes.
        if (t == track && abs(durationMs - trackDurationMs) < 3000) return
        track = t
        trackDurationMs = durationMs
        lines = null
        thread(name = "lyrics") {
            val found = try {
                lookup(t, durationMs / 1000.0)
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics lookup failed: $e")
                null
            }
            main.post {
                if (track == t && trackDurationMs == durationMs) {
                    lines = found
                    Log.i(TAG, "${t.title}: ${found?.size ?: 0} synced lines")
                    onReady()
                }
            }
        }
    }

    /** The index of the line being sung at [positionMs], or -1 before the first. */
    fun indexAt(positionMs: Long): Int {
        val l = lines ?: return -1
        var lo = 0
        var hi = l.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (l[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    private fun lookup(t: NowPlaying.Track, durationS: Double): List<Line>? {
        // 1. Exact match on all four fields.
        if (durationS > 0) {
            val exact = get("$API/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", t.title)
                .addQueryParameter("artist_name", t.artist)
                .addQueryParameter("album_name", t.album)
                .addQueryParameter("duration", Math.round(durationS).toString())
                .build().toString())
            exact?.let { parse(JSONObject(it).optString("syncedLyrics")) }?.let { return it }
        }
        // 2. Apple Music lists several artists ("A, B & C"); LRCLIB usually has the first.
        // Titles also differ ("Song" vs "Song (From "Film")"), so search and pick by duration.
        val firstArtist = t.artist.split(", ", " & ", " feat. ", " x ").first().trim()
        val searches = listOf(
            "$API/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", t.title)
                .addQueryParameter("artist_name", firstArtist).build().toString(),
            "$API/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "${t.title} $firstArtist").build().toString(),
        )
        for (url in searches) {
            val body = get(url) ?: continue
            val results = JSONArray(body)
            var best: JSONObject? = null
            val synced = (0 until results.length()).map { results.getJSONObject(it) }
                .filter { it.optString("syncedLyrics").isNotEmpty() }
            for (r in synced) {
                val off = abs(r.optDouble("duration", 0.0) - durationS)
                if (durationS > 0 && off > 5) continue // probably a different recording
                if (best == null || off < abs(best.optDouble("duration", 0.0) - durationS)) best = r
            }
            // A single synced result is very likely the song even if the length looks off.
            if (best == null && synced.size == 1) best = synced[0]
            best?.let { parse(it.optString("syncedLyrics")) }?.let { return it }
        }
        return null
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Net.client.newCall(request).execute().use { r ->
            return if (r.isSuccessful) r.body?.string() else null
        }
    }

    private val TIME = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

    /** LRC "[mm:ss.xx] text" lines; empty text becomes ♪ (an instrumental gap). */
    private fun parse(lrc: String): List<Line>? {
        if (lrc.isBlank()) return null
        val out = mutableListOf<Line>()
        for (raw in lrc.lines()) {
            val stamps = TIME.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim().ifEmpty { "♪" }
            for (m in stamps) {
                val ms = (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toDouble() * 1000).toLong()
                out += Line(ms, text)
            }
        }
        return out.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
    }
}

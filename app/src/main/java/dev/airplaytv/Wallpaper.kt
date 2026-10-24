package dev.airplaytv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Optional background for the start and waiting screens: `assets/wallpaper.jpg`, which is not in
 * git (add your own before building). Without it the screens are plain black. Decoded once, off the
 * main thread, as RGB_565 so a 4K image takes half the memory.
 */
object Wallpaper {

    private const val FILE = "wallpaper.jpg"
    private val main = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null
    private var loaded = false
    private val waiting = mutableListOf<(Bitmap?) -> Unit>()

    /** Calls [onReady] on the main thread with the wallpaper, or null if the app has none. */
    fun load(context: Context, onReady: (Bitmap?) -> Unit) {
        if (loaded) return onReady(bitmap)
        waiting += onReady
        if (waiting.size > 1) return // already decoding
        val assets = context.applicationContext.assets
        thread(name = "wallpaper") {
            val bmp = try {
                assets.open(FILE).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }
            } catch (_: IOException) {
                Log.i("Wallpaper", "No $FILE in assets: using a black background")
                null
            }
            main.post {
                bitmap = bmp
                loaded = true
                waiting.toList().forEach { it(bmp) }
                waiting.clear()
            }
        }
    }
}

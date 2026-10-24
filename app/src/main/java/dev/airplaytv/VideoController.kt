package dev.airplaytv

import android.os.Handler
import android.os.Looper

/** Routes the iPhone's video commands (from UxPlay's threads) to the open [VideoActivity]. */
object VideoController {

    interface Target {
        fun seek(seconds: Float)
        fun rate(rate: Float)
        fun setVolume(db: Float)
        fun close()
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var target: Target? = null

    fun seek(seconds: Float) {
        main.post { target?.seek(seconds) }
    }

    fun rate(rate: Float) {
        main.post { target?.rate(rate) }
    }

    fun setVolume(db: Float) {
        main.post { target?.setVolume(db) }
    }

    fun close() {
        main.post { target?.close() }
    }
}

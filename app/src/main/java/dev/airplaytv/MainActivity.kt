package dev.airplaytv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Launcher entry: starts the receiver and shows its status. The receiver itself runs in [AirPlayService]. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = buildString {
                append(AirPlayService.status)
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    append("\n\nTo let the Now Playing and video screens open by themselves, run once:\n")
                    append("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)
        val d = resources.displayMetrics.density
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 40f
            setTextColor(0xffffffff.toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_airplay, 0, 0, 0)
            compoundDrawablePadding = (16 * d).toInt()
        }
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(0xccffffff.toInt())
            setPadding(0, (24 * d).toInt(), 0, 0)
        }
        val hint = TextView(this).apply {
            textSize = 18f
            setTextColor(0x99ffffff.toInt())
            setPadding(0, (32 * d).toInt(), 0, 0)
            text = "On your iPhone: Music → AirPlay → \"${AirPlayService.airPlayName(this@MainActivity)}\".\n" +
                "Video: the AirPlay button in the YouTube app. Screen mirroring is not supported."
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((96 * d).toInt(), 0, (96 * d).toInt(), 0)
            setBackgroundColor(0xff101014.toInt())
            addView(title)
            addView(status)
            addView(hint)
        })
    }

    override fun onStart() {
        super.onStart()
        main.post(refresh)
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        super.onStop()
    }
}

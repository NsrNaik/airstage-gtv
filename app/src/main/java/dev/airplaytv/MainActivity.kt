package dev.airplaytv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Launcher entry: starts the receiver, shows its status and holds its settings (PIN for new
 * devices, forget paired devices). The receiver itself runs in [AirPlayService].
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var pinButton: Button
    private lateinit var forgetButton: Button
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
                "Video: the AirPlay button in the YouTube app. Photos and Screen Mirroring are mirrored."
        }
        pinButton = Button(this).apply {
            setOnClickListener {
                AirPlayService.setRequirePin(this@MainActivity, !AirPlayService.requirePin(this@MainActivity))
                renderSettings()
            }
        }
        forgetButton = Button(this).apply {
            setOnClickListener {
                PairedDevices(this@MainActivity).forgetAll()
                renderSettings()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (32 * d).toInt(), 0, 0)
            addView(pinButton)
            addView(forgetButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (16 * d).toInt()
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((96 * d).toInt(), 0, (96 * d).toInt(), 0)
            addView(title)
            addView(status)
            addView(hint)
            addView(buttons)
        }
        val background = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Wallpaper.load(this) { background.setImageBitmap(it) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(background, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // Darken the left side, where the text is, so it stays readable over any wallpaper.
            addView(android.view.View(this@MainActivity).apply { setBackgroundResource(R.drawable.scrim) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        renderSettings()
        pinButton.requestFocus()
    }

    private fun renderSettings() {
        val pinOn = AirPlayService.requirePin(this)
        pinButton.text = if (pinOn) "PIN for new devices: On" else "PIN for new devices: Off"
        val names = PairedDevices(this).names()
        forgetButton.text = if (names.isEmpty()) "No paired devices" else "Forget paired devices (${names.joinToString()})"
        forgetButton.isEnabled = names.isNotEmpty()
    }

    override fun onStart() {
        super.onStart()
        main.post(refresh)
        renderSettings()
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        super.onStop()
    }
}

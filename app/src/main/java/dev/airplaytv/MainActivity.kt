package dev.airplaytv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
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
    private lateinit var refreshButton: Button
    private lateinit var allowButton: Button
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = buildString {
                append(AirPlayService.status)
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    append("\n\n").append(getString(R.string.overlay_needed))
                    if (overlayFallback) append("\n").append(getString(R.string.overlay_adb, packageName))
                }
            }
            allowButton.visibility = if (Settings.canDrawOverlays(this@MainActivity)) View.GONE else View.VISIBLE
            main.postDelayed(this, 1000)
        }
    }

    /** Set when the TV has no settings page for the permission: show the ADB command instead. */
    private var overlayFallback = false

    /**
     * Opens Android's "Display over other apps" page for AirStage (needed so Now Playing and the
     * video screen can open by themselves). Some TVs hide that page: then the app's info page, and
     * as a last resort the one-line ADB command.
     */
    private fun requestOverlayPermission() {
        val pkg = Uri.parse("package:$packageName")
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        overlayFallback = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)
        if (Demo.startIfRequested(this, intent)) return finish()
        val d = resources.displayMetrics.density
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 40f
            setTextColor(0xffffffff.toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_airstage, 0, 0, 0)
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
        refreshButton = Button(this).apply {
            text = getString(R.string.refresh_network)
            setOnClickListener {
                AirPlayService.instance?.refresh()
                text = getString(R.string.refresh_done)
                main.postDelayed({ text = getString(R.string.refresh_network) }, 3000)
            }
        }
        allowButton = Button(this).apply {
            text = getString(R.string.overlay_allow)
            setOnClickListener { requestOverlayPermission() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (32 * d).toInt(), 0, 0)
            addView(allowButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = (16 * d).toInt()
            })
            addView(refreshButton)
            addView(pinButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (16 * d).toInt()
            })
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
            addView(View(this@MainActivity).apply { setBackgroundResource(R.drawable.scrim) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        renderSettings()
        // The first thing to do on a fresh install is allow the screens to open by themselves.
        if (Settings.canDrawOverlays(this)) refreshButton.requestFocus() else allowButton.requestFocus()
    }

    private fun renderSettings() {
        val pinOn = AirPlayService.requirePin(this)
        pinButton.text = if (pinOn) "PIN for new devices: On" else "PIN for new devices: Off"
        val names = PairedDevices(this).names()
        forgetButton.text = if (names.isEmpty()) "No paired devices" else "Forget paired devices (${names.size})"
        forgetButton.isEnabled = names.isNotEmpty()
    }

    override fun onStart() {
        super.onStart()
        // Opening the app while AirPlay is running (e.g. after "Continue in background") goes back to
        // what is playing, not to this start screen.
        val playing = when {
            MirrorController.active -> MirrorActivity::class.java
            NowPlaying.active || NowPlaying.connected || NowPlaying.pin != null -> NowPlayingActivity::class.java
            else -> null
        }
        if (playing != null) {
            AirPlayService.instance?.inBackground = false // the user came back to it
            startActivity(Intent(this, playing))
            finish()
            return
        }
        main.post(refresh)
        renderSettings()
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        super.onStop()
    }
}

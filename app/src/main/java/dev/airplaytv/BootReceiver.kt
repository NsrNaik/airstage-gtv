package dev.airplaytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the AirPlay receiver at boot and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> AirPlayService.start(context)
        }
    }
}

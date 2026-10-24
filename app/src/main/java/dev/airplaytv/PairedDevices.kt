package dev.airplaytv

import android.content.Context

/**
 * Devices that have entered the PIN once. Stores each device's pairing public key (base64,
 * from UxPlay) with its name; a returning device whose key is listed connects without a PIN.
 */
class PairedDevices(context: Context) {

    private val prefs = context.getSharedPreferences("paired_devices", Context.MODE_PRIVATE)

    fun add(publicKey: String, name: String) {
        prefs.edit().putString(publicKey, name).apply()
    }

    fun contains(publicKey: String) = prefs.contains(publicKey)

    fun names(): List<String> = prefs.all.values.map { it.toString() }.distinct().sorted()

    fun forgetAll() {
        prefs.edit().clear().apply()
    }
}

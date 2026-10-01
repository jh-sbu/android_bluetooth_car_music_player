package dev.carplayer.car

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The car's Bluetooth link (ACL) comes up a few seconds before its A2DP audio profile does.
 * Starting playback in between would play through the phone speaker, so auto-play waits here
 * until the system actually has an A2DP output to the car.
 */
object CarAudioRoute {

    /**
     * Whether an output device reported by AudioManager is the car's A2DP sink. Bluetooth
     * addresses can be partially masked ("XX:XX:XX:XX:AB:CD") depending on permissions, so a
     * masked address matches on its visible suffix.
     */
    fun isCarOutput(type: Int, address: String?, carAddress: String?): Boolean {
        if (type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return false
        if (carAddress.isNullOrBlank() || address.isNullOrBlank()) return true
        if (address.equals(carAddress, ignoreCase = true)) return true
        val visible = address.uppercase().split(':')
        val expected = carAddress.uppercase().split(':')
        return visible.size == expected.size &&
            visible.zip(expected).all { (v, e) -> v == "XX" || v == e }
    }

    fun isConnected(audioManager: AudioManager, carAddress: String?): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isCar(carAddress) }

    /** Suspends until the car's A2DP output exists; returns false on timeout. */
    suspend fun await(audioManager: AudioManager, carAddress: String?, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        if (addedDevices.any { it.isCar(carAddress) } && cont.isActive) {
                            audioManager.unregisterAudioDeviceCallback(this)
                            cont.resume(true)
                        }
                    }
                }
                cont.invokeOnCancellation { audioManager.unregisterAudioDeviceCallback(callback) }
                // Registering immediately reports all current devices via onAudioDevicesAdded,
                // which covers the case where A2DP is already up.
                audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            }
        } ?: false

    private fun AudioDeviceInfo.isCar(carAddress: String?) = isCarOutput(type, address, carAddress)
}

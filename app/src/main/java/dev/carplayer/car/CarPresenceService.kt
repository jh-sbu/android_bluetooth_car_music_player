package dev.carplayer.car

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import dev.carplayer.appGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bound by the system while the associated car is connected over Bluetooth. Being a companion
 * app with REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND is what lets playback
 * (a foreground service) start from the background when the car connects, with no UI open.
 */
class CarPresenceService : CompanionDeviceService() {

    private val scope = MainScope()
    private var autoPlayJob: Job? = null
    private val log by lazy { appGraph.eventLog }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> onCarConnected("presence event")
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> onCarDisconnected("presence event")
            else -> log.log("Ignoring presence event ${event.event} for association ${event.associationId}")
        }
    }

    // Pre-Android 16 callbacks. On 16 the system may deliver both; onCarConnected de-duplicates.
    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = onCarConnected("device appeared")

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = onCarDisconnected("device disappeared")

    private fun onCarConnected(via: String) {
        if (autoPlayJob?.isActive == true) return
        log.log("Car connected ($via)")
        autoPlayJob = scope.launch { AutoPlayer(this@CarPresenceService).onCarConnected() }
    }

    private fun onCarDisconnected(via: String) {
        // Playback pauses on its own: ExoPlayer handles "audio becoming noisy" when A2DP drops.
        log.log("Car disconnected ($via)")
        autoPlayJob?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

package dev.carplayer.car

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.carplayer.appGraph
import dev.carplayer.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Starts (or resumes) playback when the car connects. */
class AutoPlayer(private val context: Context) {

    private val log = context.appGraph.eventLog
    private val settings = context.appGraph.carSettings

    /**
     * @param waitForCarAudio wait for the car's A2DP output before playing. Only the in-app
     *   "simulate" button turns this off, so the flow can be exercised without a car.
     * @return whether playback is running afterwards.
     */
    suspend fun onCarConnected(waitForCarAudio: Boolean = true): Boolean {
        val config = settings.get()
        if (!config.autoPlay) {
            log.log("Auto-play is off; not starting playback")
            return false
        }

        if (waitForCarAudio) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val start = SystemClock.elapsedRealtime()
            if (!CarAudioRoute.await(audioManager, config.address, CAR_AUDIO_TIMEOUT_MS)) {
                log.log("Car audio (A2DP) didn't connect within ${CAR_AUDIO_TIMEOUT_MS / 1000}s; not playing")
                return false
            }
            log.log("Car audio (A2DP) ready after ${SystemClock.elapsedRealtime() - start} ms")
            // Give the head unit a moment to switch its source to Bluetooth audio so the start
            // of the track isn't clipped.
            delay(SETTLE_MS)
        }

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            if (controller.isPlaying) {
                log.log("Already playing: ${controller.describeCurrent()}")
                return true
            }
            // With an empty queue this triggers the session's playback resumption, which
            // restores the last queue (or shuffles the library on first use).
            controller.play()
            val playing = withTimeoutOrNull(START_TIMEOUT_MS) { controller.awaitPlaying() } ?: false
            if (playing) {
                log.log("Playback started: ${controller.describeCurrent()}")
            } else {
                val error = controller.playerError?.let { " (${it.errorCodeName}: ${it.message})" }.orEmpty()
                log.log("Playback didn't start; player state ${controller.playbackState}$error")
            }
            return playing
        } finally {
            controller.release()
        }
    }

    private suspend fun MediaController.awaitPlaying(): Boolean {
        if (isPlaying) return true
        return suspendCancellableCoroutine { cont ->
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && cont.isActive) {
                        removeListener(this)
                        cont.resume(true)
                    }
                }
            }
            addListener(listener)
            cont.invokeOnCancellation { removeListener(listener) }
        }
    }

    private fun MediaController.describeCurrent(): String =
        mediaMetadata.run { listOfNotNull(title, artist).joinToString(" — ").ifEmpty { "unknown track" } }

    companion object {
        /** Older head units can take a while after the phone link comes up to open A2DP. */
        const val CAR_AUDIO_TIMEOUT_MS = 60_000L
        const val SETTLE_MS = 1_500L
        const val START_TIMEOUT_MS = 10_000L
    }
}

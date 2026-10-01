package dev.carplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.carplayer.appGraph
import dev.carplayer.car.AutoPlayer
import dev.carplayer.car.CarConfig
import dev.carplayer.car.EventLog
import dev.carplayer.library.Track
import dev.carplayer.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private var controller: MediaController? = null

    private val _tracks = MutableStateFlow<List<Track>?>(null)
    /** Null while loading. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    val car: StateFlow<CarConfig> =
        graph.carSettings.changes().stateIn(viewModelScope, SharingStarted.Eagerly, graph.carSettings.get())

    val events: StateFlow<List<EventLog.Entry>> =
        graph.eventLog.changes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _simulating = MutableStateFlow(false)
    val simulating: StateFlow<Boolean> = _simulating.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(listener)
            publish(c)
            // Progress bar ticker.
            while (isActive) {
                delay(500)
                if (c.isPlaying) publish(c)
            }
        }
    }

    fun loadLibrary(refresh: Boolean = false) {
        viewModelScope.launch {
            if (refresh) graph.trackSource.invalidate()
            _tracks.value = graph.trackSource.tracks()
        }
    }

    fun play(tracks: List<Track>, startIndex: Int) {
        val c = controller ?: return
        c.shuffleModeEnabled = false
        c.setMediaItems(tracks.map(Track::toMediaItem), startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun shuffleAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val c = controller ?: return
        c.shuffleModeEnabled = true
        c.setMediaItems(tracks.map(Track::toMediaItem), tracks.indices.random(), 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        // play() with an empty queue triggers playback resumption in the session.
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNext()

    fun previous() = controller?.seekToPrevious()

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun setAutoPlay(enabled: Boolean) = graph.carSettings.setAutoPlay(enabled)

    /** Runs the car-connected flow without a car (skips waiting for A2DP). */
    fun simulateCarConnection() {
        if (_simulating.value) return
        _simulating.value = true
        viewModelScope.launch {
            try {
                graph.eventLog.log("Simulated car connection")
                // Pause first so the run is meaningful even if something is already playing.
                controller?.pause()
                AutoPlayer(getApplication()).onCarConnected(waitForCarAudio = false)
            } finally {
                _simulating.value = false
            }
        }
    }

    fun clearLog() = graph.eventLog.clear()

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        _nowPlaying.value = NowPlaying(
            mediaId = item?.mediaId,
            title = player.mediaMetadata.title?.toString(),
            artist = player.mediaMetadata.artist?.toString(),
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
        )
    }

    override fun onCleared() {
        controller?.run {
            removeListener(listener)
            release()
        }
    }
}

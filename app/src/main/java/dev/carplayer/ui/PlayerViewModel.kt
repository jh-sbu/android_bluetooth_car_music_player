package dev.carplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.carplayer.appGraph
import dev.carplayer.car.AutoPlayer
import dev.carplayer.car.CarConfig
import dev.carplayer.car.EventLog
import dev.carplayer.library.Album
import dev.carplayer.library.Artist
import dev.carplayer.library.Playlist
import dev.carplayer.library.Track
import dev.carplayer.library.albums
import dev.carplayer.library.artists
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
    /** Index of the current item in the player's list, matching [QueueEntry.index]; -1 if none. */
    val index: Int = -1,
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

data class Library(
    val songs: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<Playlist>,
)

/** One entry of the player's queue; [index] is its position in the player's (unshuffled) list. */
data class QueueEntry(val index: Int, val mediaId: String, val title: String, val artist: String?)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private var controller: MediaController? = null

    private val _library = MutableStateFlow<Library?>(null)
    /** Null while loading. */
    val library: StateFlow<Library?> = _library.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    /** The Now Playing queue, in the order it will play (shuffled order when shuffle is on). */
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    val car: StateFlow<CarConfig> =
        graph.carSettings.changes().stateIn(viewModelScope, SharingStarted.Eagerly, graph.carSettings.get())

    val events: StateFlow<List<EventLog.Entry>> =
        graph.eventLog.changes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _simulating = MutableStateFlow(false)
    val simulating: StateFlow<Boolean> = _simulating.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                publishQueue(player)
            }
        }
    }

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(listener)
            publish(c)
            publishQueue(c)
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
            val songs = graph.trackSource.tracks()
            _library.value = Library(
                songs = songs,
                albums = songs.albums(),
                artists = songs.artists(),
                playlists = graph.playlistSource.playlists(),
            )
        }
    }

    fun play(tracks: List<Track>, startIndex: Int) {
        val c = controller ?: return
        c.shuffleModeEnabled = false
        c.setMediaItems(tracks.map(Track::toMediaItem), startIndex, 0L)
        c.prepare()
        c.play()
    }

    /** Appends [tracks] to the queue, or starts a new (paused) queue if nothing is queued. */
    fun addToNowPlaying(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val c = controller ?: return
        val items = tracks.map(Track::toMediaItem)
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items)
            c.prepare()
        } else {
            c.addMediaItems(items)
        }
    }

    /** Jumps to the queue entry at [index] (as in [QueueEntry.index]). */
    fun skipTo(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0L)
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
            index = if (item == null) -1 else player.currentMediaItemIndex,
            title = player.mediaMetadata.title?.toString(),
            artist = player.mediaMetadata.artist?.toString(),
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
        )
    }

    private fun publishQueue(player: Player) {
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val window = Timeline.Window()
        val entries = ArrayList<QueueEntry>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && entries.size < timeline.windowCount) {
            val item = timeline.getWindow(i, window).mediaItem
            entries += QueueEntry(
                index = i,
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString() ?: "Unknown",
                artist = item.mediaMetadata.artist?.toString(),
            )
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        _queue.value = entries
    }

    override fun onCleared() {
        controller?.run {
            removeListener(listener)
            release()
        }
    }
}

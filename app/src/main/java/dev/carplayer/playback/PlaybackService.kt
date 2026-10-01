package dev.carplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.carplayer.appGraph
import dev.carplayer.library.LibraryTree
import dev.carplayer.library.Track
import dev.carplayer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Owns the player and the media session. The session is what the Bluetooth stack talks to:
 * AVRCP play/pause/skip commands from the head unit arrive as session commands, and the
 * session's metadata and playback state are what the head unit displays.
 */
class PlaybackService : MediaLibraryService() {

    private val scope: CoroutineScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var tree: LibraryTree
    private lateinit var snapshots: PlaybackSnapshotStore

    private val periodicSave = object : Runnable {
        override fun run() {
            snapshots.savePosition(player.currentMediaItemIndex, player.currentPosition)
            handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tree = appGraph.libraryTree
        snapshots = appGraph.snapshotStore

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            // Pauses when the car's A2DP link drops (engine off) instead of blasting the phone speaker.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(PersistingListener())

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        saveSnapshot()
        handler.removeCallbacks(periodicSave)
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun saveSnapshot() {
        if (player.mediaItemCount == 0) return
        snapshots.save(
            PlaybackSnapshot(
                mediaIds = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                shuffle = player.shuffleModeEnabled,
            ),
        )
    }

    private inner class PersistingListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                saveSnapshot()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(periodicSave)
            if (isPlaying) {
                handler.postDelayed(periodicSave, POSITION_SAVE_INTERVAL_MS)
            } else {
                saveSnapshot()
            }
        }
    }

    @OptIn(UnstableApi::class) // Playback resumption.
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.root, params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = tree.children(parentId)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            val from = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(children.size.toLong()).toInt()
            LibraryResult.ofItemList(children.subList(from, to), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future { tree.resolve(mediaItems) }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val tracks = appGraph.trackSource.tracks()
            val byMediaId = tracks.associateBy { it.mediaId }
            val snapshot = snapshots.load()
            val restored = snapshot?.restore { id -> byMediaId[id]?.toMediaItem() }
            when {
                restored != null -> {
                    mediaSession.player.shuffleModeEnabled = snapshot.shuffle
                    if (isForPlayback) {
                        MediaItemsWithStartPosition(restored.items, restored.index, restored.positionMs)
                    } else {
                        MediaItemsWithStartPosition(listOf(restored.items[restored.index]), 0, restored.positionMs)
                    }
                }
                else -> {
                    // Nothing played yet: shuffle the whole library, which is what you want in a car.
                    val all = tracks.map(Track::toMediaItem)
                    check(all.isNotEmpty()) { "No music on device to resume" }
                    mediaSession.player.shuffleModeEnabled = true
                    MediaItemsWithStartPosition(all, all.indices.random(), 0L)
                }
            }
        }
    }

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 10_000L
    }
}

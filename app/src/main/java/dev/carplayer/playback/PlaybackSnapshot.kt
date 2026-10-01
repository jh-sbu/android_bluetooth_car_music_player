package dev.carplayer.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.MediaItem

/** What was playing, persisted so a car connection (or a Bluetooth "play") resumes where we left off. */
data class PlaybackSnapshot(
    val mediaIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
) {
    /**
     * Rebuilds the queue using [lookup]; entries whose tracks were deleted are dropped. If the
     * current track is gone, resumes at the start of the next surviving one.
     */
    inline fun restore(lookup: (String) -> MediaItem?): Restored? {
        val items = ArrayList<MediaItem>(mediaIds.size)
        var newIndex = -1
        var currentSurvived = false
        mediaIds.forEachIndexed { i, id ->
            val item = lookup(id) ?: return@forEachIndexed
            if (i == index) currentSurvived = true
            if (i >= index && newIndex == -1) newIndex = items.size
            items += item
        }
        if (items.isEmpty()) return null
        if (newIndex == -1) newIndex = 0
        return Restored(items, newIndex, if (currentSurvived) positionMs else 0L)
    }

    data class Restored(val items: List<MediaItem>, val index: Int, val positionMs: Long)
}

class PlaybackSnapshotStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("playback", Context.MODE_PRIVATE))

    fun load(): PlaybackSnapshot? {
        val ids = prefs.getString(KEY_QUEUE, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }
        if (ids.isNullOrEmpty()) return null
        return PlaybackSnapshot(
            mediaIds = ids,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
        )
    }

    fun save(snapshot: PlaybackSnapshot) {
        prefs.edit {
            // Media IDs are "track:<number>", so they never contain the separator.
            putString(KEY_QUEUE, snapshot.mediaIds.joinToString(SEPARATOR))
            putInt(KEY_INDEX, snapshot.index)
            putLong(KEY_POSITION, snapshot.positionMs)
            putBoolean(KEY_SHUFFLE, snapshot.shuffle)
        }
    }

    /** Cheap update used by the periodic position save while playing. */
    fun savePosition(index: Int, positionMs: Long) {
        prefs.edit {
            putInt(KEY_INDEX, index)
            putLong(KEY_POSITION, positionMs)
        }
    }

    private companion object {
        const val SEPARATOR = "\n"
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
    }
}

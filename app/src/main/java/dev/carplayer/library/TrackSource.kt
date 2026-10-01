package dev.carplayer.library

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TrackSource {
    suspend fun tracks(): List<Track>

    suspend fun track(id: Long): Track? = tracks().firstOrNull { it.id == id }
}

/**
 * Local music from MediaStore. Results are cached until MediaStore reports a change, so browse
 * requests from the UI and Bluetooth head units don't each re-query the provider.
 */
class MediaStoreTrackSource(private val resolver: ContentResolver) : TrackSource {
    private val mutex = Mutex()
    @Volatile private var cache: List<Track>? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            cache = null
        }
    }

    init {
        resolver.registerContentObserver(COLLECTION, true, observer)
    }

    override suspend fun tracks(): List<Track> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) { query() }.also { cache = it }
    }

    fun invalidate() {
        cache = null
    }

    fun close() {
        resolver.unregisterContentObserver(observer)
    }

    private fun query(): List<Track> =
        resolver.query(
            COLLECTION,
            PROJECTION,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, " +
                "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, " +
                "${MediaStore.Audio.Media.TRACK}, " +
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use(::readTracks).orEmpty()

    companion object {
        val COLLECTION = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
        )

        fun readTracks(cursor: Cursor): List<Track> {
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            val result = ArrayList<Track>(cursor.count)
            while (cursor.moveToNext()) {
                val trackId = cursor.getLong(id)
                // MediaStore encodes TRACK as disc * 1000 + track.
                val rawTrack = if (cursor.isNull(track)) 0 else cursor.getInt(track)
                result += Track(
                    id = trackId,
                    title = cursor.getString(title).cleanTag()
                        ?: cursor.getString(displayName)?.substringBeforeLast('.')
                        ?: "Unknown",
                    artist = cursor.getString(artist).cleanTag(),
                    album = cursor.getString(album).cleanTag(),
                    albumId = cursor.getLong(albumId),
                    durationMs = cursor.getLong(duration),
                    trackNumber = (rawTrack % 1000).takeIf { it > 0 },
                    discNumber = (rawTrack / 1000).takeIf { it > 0 },
                    uri = ContentUris.withAppendedId(COLLECTION, trackId),
                )
            }
            return result
        }

        private fun String?.cleanTag(): String? =
            this?.trim()?.takeUnless { it.isEmpty() || it == MediaStore.UNKNOWN_STRING }
    }
}

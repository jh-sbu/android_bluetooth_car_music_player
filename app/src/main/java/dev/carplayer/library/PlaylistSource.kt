package dev.carplayer.library

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlists from MediaStore. Since Android 11 these are backed by playlist files (.m3u, .pls, …)
 * that the media scanner finds on the phone, e.g. in Music/ or Playlists/. The Playlists API is
 * deprecated in favour of reading those files directly, but it's still the only index of them.
 */
@Suppress("DEPRECATION")
class MediaStorePlaylistSource(
    private val resolver: ContentResolver,
    private val tracks: TrackSource,
) {
    /** Sorted by name; entries for tracks that aren't in the library are dropped. */
    suspend fun playlists(): List<Playlist> {
        val byId = tracks.tracks().associateBy { it.id }
        return withContext(Dispatchers.IO) {
            resolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null,
                null,
                "${MediaStore.Audio.Playlists.NAME} COLLATE NOCASE",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val playlistId = cursor.getLong(id)
                        add(
                            Playlist(
                                id = playlistId,
                                name = cursor.getString(name)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Playlist",
                                tracks = memberIds(playlistId).mapNotNull(byId::get),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }
    }

    private fun memberIds(playlistId: Long): List<Long> =
        resolver.query(
            MediaStore.Audio.Playlists.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId),
            MEMBER_PROJECTION,
            null,
            null,
            MediaStore.Audio.Playlists.Members.PLAY_ORDER,
        )?.use(::readMemberIds).orEmpty()

    companion object {
        val MEMBER_PROJECTION = arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID)

        fun readMemberIds(cursor: Cursor): List<Long> {
            val audioId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            return buildList { while (cursor.moveToNext()) add(cursor.getLong(audioId)) }
        }
    }
}

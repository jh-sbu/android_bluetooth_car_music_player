package dev.carplayer.library

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val durationMs: Long,
    /** Track number within its disc, if the file is tagged with one. */
    val trackNumber: Int?,
    val discNumber: Int?,
    val uri: Uri,
) {
    val mediaId: String get() = MediaIds.track(id)

    fun toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    // AVRCP 1.3 head units (e.g. 2013 Accord) show title/artist/album only.
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(artist)
                    .setTrackNumber(trackNumber)
                    .setDiscNumber(discNumber)
                    .setDurationMs(durationMs.takeIf { it > 0 })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
}

/** Media IDs shared by the browse tree, the UI and persisted queues. */
object MediaIds {
    const val ROOT = "root"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    private const val TRACK_PREFIX = "track:"
    private const val ALBUM_PREFIX = "album:"

    fun track(id: Long) = "$TRACK_PREFIX$id"
    fun album(id: Long) = "$ALBUM_PREFIX$id"
    fun trackId(mediaId: String): Long? = mediaId.removePrefixOrNull(TRACK_PREFIX)?.toLongOrNull()
    fun albumId(mediaId: String): Long? = mediaId.removePrefixOrNull(ALBUM_PREFIX)?.toLongOrNull()

    private fun String.removePrefixOrNull(prefix: String) =
        if (startsWith(prefix)) substring(prefix.length) else null
}

package dev.carplayer.library

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * The browse hierarchy exposed through the MediaLibrarySession. AVRCP 1.4+ head units that
 * support browsing, Android Auto, and this app's own UI all see the same tree:
 *
 * root
 * ├── songs            every track
 * └── albums
 *     └── album:<id>   tracks of one album, in track order
 */
class LibraryTree(private val source: TrackSource) {

    val root: MediaItem = folder(MediaIds.ROOT, "CarPlayer", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        MediaIds.ROOT -> root
        MediaIds.SONGS -> songsFolder()
        MediaIds.ALBUMS -> albumsFolder()
        else -> MediaIds.trackId(mediaId)?.let { source.track(it)?.toMediaItem() }
            ?: albums().firstOrNull { it.mediaId == mediaId }
    }

    /** Children of [parentId], or null if the parent doesn't exist. */
    suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        MediaIds.ROOT -> listOf(songsFolder(), albumsFolder())
        MediaIds.SONGS -> source.tracks().map { it.toMediaItem() }
        MediaIds.ALBUMS -> albums()
        else -> MediaIds.albumId(parentId)?.let { albumTracks(it).map(Track::toMediaItem) }
    }

    /**
     * Turns items a controller asked to play (which may carry only a media ID, as items from
     * browsing clients do) into playable items with URIs. Unknown IDs are dropped.
     */
    suspend fun resolve(items: List<MediaItem>): List<MediaItem> = items.flatMap { item ->
        when {
            item.localConfiguration != null -> listOf(item)
            else -> MediaIds.trackId(item.mediaId)?.let { id -> listOfNotNull(source.track(id)?.toMediaItem()) }
                ?: MediaIds.albumId(item.mediaId)?.let { albumTracks(it).map(Track::toMediaItem) }
                ?: if (item.mediaId == MediaIds.SONGS) source.tracks().map(Track::toMediaItem) else emptyList()
        }
    }

    private suspend fun albumTracks(albumId: Long): List<Track> =
        source.tracks().filter { it.albumId == albumId }.albums().singleOrNull()?.tracks.orEmpty()

    private suspend fun albums(): List<MediaItem> =
        source.tracks().albums().map { folder(it.mediaId, it.title, MediaMetadata.MEDIA_TYPE_ALBUM, subtitle = it.artist) }

    private fun songsFolder() = folder(MediaIds.SONGS, "All songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    private fun albumsFolder() = folder(MediaIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setIsBrowsable(true)
                    // "All songs" and albums can be played directly as a queue.
                    .setIsPlayable(id != MediaIds.ROOT && id != MediaIds.ALBUMS)
                    .setMediaType(type)
                    .build(),
            )
            .build()
}

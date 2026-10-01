package dev.carplayer.library

import android.net.Uri

fun track(
    id: Long,
    title: String = "Song $id",
    artist: String? = "Artist",
    album: String? = "Album",
    albumId: Long = 1,
    trackNumber: Int? = null,
    discNumber: Int? = null,
) = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = 180_000,
    trackNumber = trackNumber,
    discNumber = discNumber,
    uri = Uri.parse("content://media/external/audio/media/$id"),
)

class FakeTrackSource(var all: List<Track>) : TrackSource {
    override suspend fun tracks() = all
}

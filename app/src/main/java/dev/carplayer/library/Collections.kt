package dev.carplayer.library

data class Album(
    val id: Long,
    val title: String,
    /** The album's artist, "Various artists" for compilations, or null if untagged. */
    val artist: String?,
    /** In disc, then track order. */
    val tracks: List<Track>,
) {
    val mediaId: String get() = MediaIds.album(id)
}

data class Artist(
    val name: String,
    /** Grouped by album (alphabetically), each in disc/track order. */
    val tracks: List<Track>,
) {
    val albumCount: Int get() = tracks.distinctBy { it.albumId }.size
}

data class Playlist(val id: Long, val name: String, val tracks: List<Track>)

const val UNKNOWN_ARTIST = "Unknown artist"

private val ALBUM_ORDER = compareBy<Track>({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title })

/** Albums sorted by title, case-insensitively. */
fun List<Track>.albums(): List<Album> =
    groupBy { it.albumId }
        .map { (albumId, tracks) ->
            val artists = tracks.mapNotNull { it.artist }.distinct()
            Album(
                id = albumId,
                title = tracks.first().album ?: "Unknown album",
                artist = artists.singleOrNull() ?: if (artists.isEmpty()) null else "Various artists",
                tracks = tracks.sortedWith(ALBUM_ORDER),
            )
        }
        .sortedBy { it.title.lowercase() }

/** Artists sorted by name, case-insensitively, with untagged tracks under [UNKNOWN_ARTIST] at the end. */
fun List<Track>.artists(): List<Artist> =
    groupBy { it.artist }
        .map { (name, tracks) ->
            Artist(
                name = name ?: UNKNOWN_ARTIST,
                tracks = tracks.albums().flatMap { it.tracks },
            )
        }
        .sortedWith(compareBy({ it.name == UNKNOWN_ARTIST }, { it.name.lowercase() }))

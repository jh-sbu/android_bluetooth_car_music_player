package dev.carplayer.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollectionsTest {

    private val tracks = listOf(
        track(1, title = "B-side", artist = "beta", album = "Second", albumId = 20, trackNumber = 2),
        track(2, title = "Opener", artist = "beta", album = "Second", albumId = 20, trackNumber = 1),
        track(3, title = "Solo", artist = "Alpha", album = "First", albumId = 10),
        track(4, title = "Guest", artist = "Alpha", album = "Second", albumId = 20, trackNumber = 3),
        track(5, title = "Untagged", artist = null, album = null, albumId = 30),
        track(6, title = "Zed", artist = "beta", album = "Another", albumId = 40),
    )

    @Test
    fun albumsAreSortedByTitleWithTracksInOrderAndCompilationArtist() {
        val albums = tracks.albums()

        assertThat(albums.map { it.title }).containsExactly("Another", "First", "Second", "Unknown album").inOrder()
        val second = albums.single { it.id == 20L }
        assertThat(second.tracks.map { it.id }).containsExactly(2L, 1L, 4L).inOrder()
        assertThat(second.artist).isEqualTo("Various artists")
        assertThat(second.mediaId).isEqualTo("album:20")
        assertThat(albums.single { it.id == 10L }.artist).isEqualTo("Alpha")
        assertThat(albums.single { it.id == 30L }.artist).isNull()
    }

    @Test
    fun artistsAreSortedCaseInsensitivelyWithUnknownLast() {
        val artists = tracks.artists()

        assertThat(artists.map { it.name }).containsExactly("Alpha", "beta", UNKNOWN_ARTIST).inOrder()
    }

    @Test
    fun artistTracksAreGroupedByAlbumInTrackOrder() {
        val beta = tracks.artists().single { it.name == "beta" }

        assertThat(beta.tracks.map { it.id }).containsExactly(6L, 2L, 1L).inOrder()
        assertThat(beta.albumCount).isEqualTo(2)
    }
}

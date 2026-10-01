package dev.carplayer.library

import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryTreeTest {

    private val source = FakeTrackSource(
        listOf(
            track(1, title = "B-side", album = "Second", albumId = 20, trackNumber = 2),
            track(2, title = "Opener", album = "Second", albumId = 20, trackNumber = 1),
            track(3, title = "Solo", album = "First", albumId = 10, artist = "Other"),
            track(4, title = "Disc two", album = "Second", albumId = 20, trackNumber = 1, discNumber = 2),
        ),
    )
    private val tree = LibraryTree(source)

    private fun List<MediaItem>.ids() = map { it.mediaId }

    @Test
    fun rootHasSongsAndAlbums() = runTest {
        assertThat(tree.children(MediaIds.ROOT)!!.ids()).containsExactly(MediaIds.SONGS, MediaIds.ALBUMS).inOrder()
        assertThat(tree.root.mediaMetadata.isBrowsable).isTrue()
        assertThat(tree.root.mediaMetadata.isPlayable).isFalse()
    }

    @Test
    fun songsListsEveryTrackInSourceOrder() = runTest {
        assertThat(tree.children(MediaIds.SONGS)!!.ids())
            .containsExactly("track:1", "track:2", "track:3", "track:4").inOrder()
    }

    @Test
    fun albumsAreSortedByTitleAndTracksByDiscThenNumber() = runTest {
        val albums = tree.children(MediaIds.ALBUMS)!!
        assertThat(albums.map { it.mediaMetadata.title.toString() }).containsExactly("First", "Second").inOrder()
        assertThat(albums.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == true }).isTrue()

        assertThat(tree.children("album:20")!!.ids()).containsExactly("track:2", "track:1", "track:4").inOrder()
    }

    @Test
    fun unknownParentIsNull() = runTest {
        assertThat(tree.children("nope")).isNull()
        assertThat(tree.item("track:999")).isNull()
    }

    @Test
    fun itemLooksUpTracksAndAlbums() = runTest {
        assertThat(tree.item("track:3")!!.mediaMetadata.title.toString()).isEqualTo("Solo")
        assertThat(tree.item("album:10")!!.mediaMetadata.title.toString()).isEqualTo("First")
    }

    @Test
    fun resolveFillsInUrisAndExpandsFolders() = runTest {
        val bare = { id: String -> MediaItem.Builder().setMediaId(id).build() }

        val resolved = tree.resolve(listOf(bare("track:3"), bare("track:999"), bare("album:20")))

        assertThat(resolved.ids()).containsExactly("track:3", "track:2", "track:1", "track:4").inOrder()
        assertThat(resolved.all { it.localConfiguration != null }).isTrue()
    }

    @Test
    fun resolveKeepsItemsThatAlreadyHaveUris() = runTest {
        val external = MediaItem.Builder().setMediaId("file").setUri("file:///sdcard/x.mp3").build()
        assertThat(tree.resolve(listOf(external))).containsExactly(external)
    }
}

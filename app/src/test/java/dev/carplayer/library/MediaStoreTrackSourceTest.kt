package dev.carplayer.library

import android.database.MatrixCursor
import android.provider.MediaStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaStoreTrackSourceTest {

    private fun cursor(vararg rows: Array<Any?>) =
        MatrixCursor(MediaStoreTrackSource.PROJECTION).apply { rows.forEach(::addRow) }

    @Test
    fun readsTaggedTrack() {
        val tracks = MediaStoreTrackSource.readTracks(
            cursor(arrayOf(42L, "Fixture Tone A", "01-a-major.mp3", "CarPlayer Fixtures", "Test Tones", 7L, 12_000L, 2003)),
        )

        assertThat(tracks).hasSize(1)
        with(tracks.single()) {
            assertThat(id).isEqualTo(42L)
            assertThat(title).isEqualTo("Fixture Tone A")
            assertThat(artist).isEqualTo("CarPlayer Fixtures")
            assertThat(album).isEqualTo("Test Tones")
            assertThat(albumId).isEqualTo(7L)
            assertThat(durationMs).isEqualTo(12_000L)
            assertThat(discNumber).isEqualTo(2)
            assertThat(trackNumber).isEqualTo(3)
            assertThat(uri.toString()).isEqualTo("content://media/external/audio/media/42")
            assertThat(mediaId).isEqualTo("track:42")
        }
    }

    @Test
    fun untaggedFileFallsBackToFileNameAndDropsUnknownArtist() {
        val track = MediaStoreTrackSource.readTracks(
            cursor(arrayOf(1L, "  ", "My Recording.m4a", MediaStore.UNKNOWN_STRING, "", 3L, 1_000L, null)),
        ).single()

        assertThat(track.title).isEqualTo("My Recording")
        assertThat(track.artist).isNull()
        assertThat(track.album).isNull()
        assertThat(track.trackNumber).isNull()
        assertThat(track.discNumber).isNull()
    }

    @Test
    fun mediaItemCarriesMetadataForAvrcp() {
        val item = track(5, title = "T", artist = "A", album = "B", trackNumber = 4).toMediaItem()

        assertThat(item.mediaId).isEqualTo("track:5")
        assertThat(item.localConfiguration!!.uri.toString()).endsWith("/5")
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("T")
        assertThat(item.mediaMetadata.artist.toString()).isEqualTo("A")
        assertThat(item.mediaMetadata.albumTitle.toString()).isEqualTo("B")
        assertThat(item.mediaMetadata.trackNumber).isEqualTo(4)
        assertThat(item.mediaMetadata.durationMs).isEqualTo(180_000L)
        assertThat(item.mediaMetadata.isPlayable).isTrue()
    }
}

package dev.carplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSnapshotTest {

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun snapshot(index: Int, position: Long = 5_000) =
        PlaybackSnapshot(listOf("a", "b", "c", "d"), index, position, shuffle = false)

    @Test
    fun restoresEverythingWhenAllTracksExist() {
        val restored = snapshot(index = 2).restore(::item)!!
        assertThat(restored.items.map { it.mediaId }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(restored.index).isEqualTo(2)
        assertThat(restored.positionMs).isEqualTo(5_000)
    }

    @Test
    fun earlierDeletedTracksShiftTheIndex() {
        val restored = snapshot(index = 2).restore { if (it == "a") null else item(it) }!!
        assertThat(restored.items.map { it.mediaId }).containsExactly("b", "c", "d").inOrder()
        assertThat(restored.index).isEqualTo(1)
        assertThat(restored.positionMs).isEqualTo(5_000)
    }

    @Test
    fun deletedCurrentTrackResumesAtStartOfNext() {
        val restored = snapshot(index = 1).restore { if (it == "b") null else item(it) }!!
        assertThat(restored.items[restored.index].mediaId).isEqualTo("c")
        assertThat(restored.positionMs).isEqualTo(0)
    }

    @Test
    fun deletedLastTrackWrapsToStart() {
        val restored = snapshot(index = 3).restore { if (it == "d") null else item(it) }!!
        assertThat(restored.index).isEqualTo(0)
        assertThat(restored.positionMs).isEqualTo(0)
    }

    @Test
    fun nothingLeftRestoresNothing() {
        assertThat(snapshot(index = 0).restore { null }).isNull()
    }

    @Test
    fun storeRoundTrips() {
        val store = PlaybackSnapshotStore(ApplicationProvider.getApplicationContext<Context>())
        assertThat(store.load()).isNull()

        val saved = PlaybackSnapshot(listOf("track:1", "track:22"), 1, 42_000, shuffle = true)
        store.save(saved)
        assertThat(store.load()).isEqualTo(saved)

        store.savePosition(0, 7)
        assertThat(store.load()).isEqualTo(saved.copy(index = 0, positionMs = 7))
    }
}

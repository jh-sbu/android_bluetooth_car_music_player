package dev.carplayer

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.carplayer.car.AutoPlayer
import dev.carplayer.library.MediaIds
import dev.carplayer.playback.PlaybackService
import dev.carplayer.playback.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests against the real PlaybackService on a device, using the generated fixture
 * tracks. Media keys are injected with `cmd media_session dispatch`, which goes through the same
 * active-session routing the Bluetooth AVRCP stack uses for the car's buttons.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixtures = Fixtures(context)
    private val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    private lateinit var ids: List<Long>
    private lateinit var controller: MediaController

    @Before
    fun setUp() = runBlocking<Unit> {
        ids = fixtures.install()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        context.appGraph.carSettings.setAutoPlay(true)
        controller = onMain { MediaController.Builder(context, token).buildAsync().await() }
        onMain {
            controller.stop()
            controller.clearMediaItems()
            controller.shuffleModeEnabled = false
            // Keep the test tones quiet on the phone speaker.
            controller.volume = 0.2f
        }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        onMain {
            controller.stop()
            controller.clearMediaItems()
            controller.release()
        }
        fixtures.remove()
    }

    @Test
    fun libraryIncludesTaggedFixtures() = runBlocking<Unit> {
        val tracks = context.appGraph.trackSource.tracks().filter { it.id in ids }

        assertThat(tracks.map { it.title }).containsExactlyElementsIn(fixtures.titles)
        tracks.forEach {
            assertThat(it.artist).isEqualTo("CarPlayer Fixtures")
            assertThat(it.album).isEqualTo("Test Tones")
            assertThat(it.durationMs).isIn(com.google.common.collect.Range.closed(11_500L, 12_500L))
        }
        assertThat(tracks.map { it.trackNumber }).containsExactly(1, 2, 3)
    }

    @Test
    fun playsAndPublishesMetadataToTheSystemSession() = runBlocking<Unit> {
        onMain {
            // Only media IDs, like a browsing head unit would send; the session resolves URIs.
            controller.setMediaItems(ids.map { MediaItem.Builder().setMediaId(MediaIds.track(it)).build() })
            controller.prepare()
            controller.play()
        }
        awaitOnMain("playing") { controller.isPlaying }
        val start = onMain { controller.currentPosition }
        delay(1_500)
        assertThat(onMain { controller.currentPosition }).isGreaterThan(start + 500)
        assertThat(onMain { controller.mediaMetadata.title.toString() }).isEqualTo("Fixture Tone A")

        // The platform MediaSession is what AVRCP reads track info from.
        val sessions = shell("dumpsys media_session")
        val ours = sessions.substringAfter("package=${context.packageName}", "")
        assertThat(ours).isNotEmpty()
        assertThat(ours.substringBefore("package=")).contains("Fixture Tone A")
    }

    @Test
    fun respondsToMediaButtons() = runBlocking<Unit> {
        onMain {
            controller.setMediaItems(ids.map { MediaItem.Builder().setMediaId(MediaIds.track(it)).build() })
            controller.prepare()
            controller.play()
        }
        awaitOnMain("playing") { controller.isPlaying }

        shell("cmd media_session dispatch pause")
        awaitOnMain("paused") { !controller.isPlaying }

        shell("cmd media_session dispatch play")
        awaitOnMain("playing again") { controller.isPlaying }

        shell("cmd media_session dispatch next")
        awaitOnMain("second track") { controller.currentMediaItemIndex == 1 }
        assertThat(onMain { controller.mediaMetadata.title.toString() }).isEqualTo("Fixture Tone D")

        // Early in a track, "previous" goes to the previous track rather than restarting.
        shell("cmd media_session dispatch previous")
        awaitOnMain("first track") { controller.currentMediaItemIndex == 0 }
    }

    @Test
    fun playWithEmptyQueueResumesSavedQueueAndPosition() = runBlocking<Unit> {
        context.appGraph.snapshotStore.save(
            PlaybackSnapshot(ids.map(MediaIds::track), index = 2, positionMs = 4_000, shuffle = false),
        )

        onMain { controller.play() }

        awaitOnMain("resumed") { controller.isPlaying }
        onMain {
            assertThat(controller.mediaItemCount).isEqualTo(3)
            assertThat(controller.currentMediaItemIndex).isEqualTo(2)
            assertThat(controller.currentPosition).isAtLeast(4_000L)
            assertThat(controller.mediaMetadata.title.toString()).isEqualTo("Fixture Tone E")
        }
    }

    @Test
    fun autoPlayStartsPlayback() = runBlocking<Unit> {
        context.appGraph.snapshotStore.save(
            PlaybackSnapshot(ids.map(MediaIds::track), index = 1, positionMs = 0, shuffle = false),
        )

        val started = onMain { AutoPlayer(context).onCarConnected(waitForCarAudio = false) }

        assertThat(started).isTrue()
        assertThat(onMain { controller.mediaMetadata.title.toString() }).isEqualTo("Fixture Tone D")
        assertThat(context.appGraph.eventLog.entries().last().message).startsWith("Playback started: Fixture Tone D")
    }

    @Test
    fun autoPlayRespectsSetting() = runBlocking<Unit> {
        context.appGraph.carSettings.setAutoPlay(false)

        val started = onMain { AutoPlayer(context).onCarConnected(waitForCarAudio = false) }

        assertThat(started).isFalse()
        assertThat(onMain { controller.isPlaying }).isFalse()
    }

    @Test
    fun browseTreeIsServedToBrowsers() = runBlocking<Unit> {
        val browser = onMain { MediaBrowser.Builder(context, token).buildAsync().await() }
        try {
            onMain {
                val root = browser.getLibraryRoot(null).await().value!!
                assertThat(root.mediaId).isEqualTo(MediaIds.ROOT)

                val songs = browser.getChildren(MediaIds.SONGS, 0, Int.MAX_VALUE, null).await().value!!
                assertThat(songs.map { it.mediaId }).containsAtLeastElementsIn(ids.map(MediaIds::track))

                val albums = browser.getChildren(MediaIds.ALBUMS, 0, Int.MAX_VALUE, null).await().value!!
                // Match by ID: the phone may hold other copies of the fixtures in another album.
                val albumId = context.appGraph.trackSource.track(ids.first())!!.albumId
                val testAlbum = albums.single { it.mediaId == MediaIds.album(albumId) }
                assertThat(testAlbum.mediaMetadata.title.toString()).isEqualTo("Test Tones")
                val albumTracks = browser.getChildren(testAlbum.mediaId, 0, 100, null).await().value!!
                assertThat(albumTracks.map { it.mediaMetadata.title.toString() }).containsExactlyElementsIn(fixtures.titles).inOrder()

                // Paging, as AVRCP browsing requests it.
                val page = browser.getChildren(testAlbum.mediaId, 1, 2, null).await().value!!
                assertThat(page.map { it.mediaMetadata.title.toString() }).containsExactly("Fixture Tone E")
            }
        } finally {
            onMain { browser.release() }
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun awaitOnMain(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) {
                while (!onMain { condition() }) delay(100)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val state = onMain { "state=${controller.playbackState} error=${controller.playerError}" }
            throw AssertionError("Timed out waiting for $what ($state)", e)
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
}

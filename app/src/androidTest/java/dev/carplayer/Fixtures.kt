package dev.carplayer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Installs the generated fixture tracks (scripts/make-fixtures.sh) into MediaStore as files owned
 * by the app, so tests go through the same MediaStore path as real music without needing
 * READ_MEDIA_AUDIO or touching the user's library. Removed again by [remove].
 */
class Fixtures(private val context: Context) {

    val titles = listOf("Fixture Tone A", "Fixture Tone D", "Fixture Tone E")
    private val files = listOf("01-a-major.mp3", "02-d-major.mp3", "03-e-major.mp3")
    private val inserted = mutableListOf<Uri>()

    fun install(): List<Long> {
        remove()
        val resolver = context.contentResolver
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (file in files) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$DIR/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            inserted += uri
            resolver.openOutputStream(uri)!!.use { out -> assets.open(file).use { it.copyTo(out) } }
            // Clearing IS_PENDING makes MediaProvider scan the file and fill in tags.
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        }
        awaitScanned()
        context.appGraph.trackSource.invalidate()
        return inserted.map { ContentUris.parseId(it) }
    }

    fun remove() {
        val resolver = context.contentResolver
        inserted.forEach { resolver.delete(it, null, null) }
        inserted.clear()
        // Leftovers from a crashed earlier run.
        resolver.delete(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf("Music/$DIR/"),
        )
        context.appGraph.trackSource.invalidate()
    }

    private fun awaitScanned() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val scanned = inserted.count { uri ->
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION),
                    null,
                    null,
                    null,
                )?.use { it.moveToFirst() && it.getString(0) in titles && it.getLong(1) > 0 } == true
            }
            if (scanned == inserted.size) return
            SystemClock.sleep(200)
        }
        error("MediaStore didn't scan the fixtures in time")
    }

    companion object {
        const val DIR = "CarPlayerTest"
    }
}

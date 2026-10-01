package dev.carplayer.car

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A small persistent log of car connection events, shown in the app. When testing in the car
 * there's no laptop attached to read logcat, so this is how you find out what happened.
 */
class EventLog(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(context.getSharedPreferences("event_log", Context.MODE_PRIVATE))

    data class Entry(val timeMs: Long, val message: String)

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        // Newlines would break the storage format; messages are one-liners anyway.
        val line = "${clock()}\t${message.replace('\n', ' ')}"
        val kept = raw().takeLast(MAX_ENTRIES - 1)
        prefs.edit { putString(KEY, (kept + line).joinToString("\n")) }
    }

    fun entries(): List<Entry> = raw().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        line.substring(0, tab).toLongOrNull()?.let { Entry(it, line.substring(tab + 1)) }
    }

    fun clear() = prefs.edit { remove(KEY) }

    fun changes(): Flow<List<Entry>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(entries()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(entries())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun raw(): List<String> = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    companion object {
        const val TAG = "CarPlayer"
        const val MAX_ENTRIES = 200
        private const val KEY = "entries"
    }
}

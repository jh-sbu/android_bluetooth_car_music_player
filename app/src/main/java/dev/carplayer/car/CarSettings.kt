package dev.carplayer.car

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class CarConfig(
    val address: String?,
    val name: String?,
    /** CompanionDeviceManager association ID, or null if the car isn't associated. */
    val associationId: Int?,
    val autoPlay: Boolean,
)

class CarSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("car", Context.MODE_PRIVATE))

    fun get(): CarConfig = CarConfig(
        address = prefs.getString(KEY_ADDRESS, null),
        name = prefs.getString(KEY_NAME, null),
        associationId = prefs.getInt(KEY_ASSOCIATION, NONE).takeIf { it != NONE },
        autoPlay = prefs.getBoolean(KEY_AUTO_PLAY, true),
    )

    fun setCar(address: String, name: String?, associationId: Int) = prefs.edit {
        putString(KEY_ADDRESS, address)
        putString(KEY_NAME, name)
        putInt(KEY_ASSOCIATION, associationId)
    }

    fun clearCar() = prefs.edit {
        remove(KEY_ADDRESS)
        remove(KEY_NAME)
        remove(KEY_ASSOCIATION)
    }

    fun setAutoPlay(enabled: Boolean) = prefs.edit { putBoolean(KEY_AUTO_PLAY, enabled) }

    fun changes(): Flow<CarConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(get()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(get())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val NONE = -1
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_ASSOCIATION = "association_id"
        const val KEY_AUTO_PLAY = "auto_play"
    }
}

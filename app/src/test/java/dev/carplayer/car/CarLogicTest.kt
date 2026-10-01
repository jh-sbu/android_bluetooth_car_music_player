package dev.carplayer.car

import android.content.Context
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarLogicTest {

    private val car = "00:11:22:AA:BB:CC"

    @Test
    fun onlyA2dpCountsAsCarAudio() {
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, car, car)).isTrue()
        // HFP (phone-call audio) connects first on many cars and must not trigger playback.
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_SCO, car, car)).isFalse()
        assertThat(CarAudioRoute.isCarOutput(TYPE_BUILTIN_SPEAKER, "", car)).isFalse()
    }

    @Test
    fun a2dpAddressMatching() {
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, car.lowercase(), car)).isTrue()
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, "XX:XX:XX:XX:BB:CC", car)).isTrue()
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, "XX:XX:XX:XX:BB:CD", car)).isFalse()
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, "66:55:44:33:22:11", car)).isFalse()
        // No address to compare against: any A2DP sink will do.
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, "", car)).isTrue()
        assertThat(CarAudioRoute.isCarOutput(TYPE_BLUETOOTH_A2DP, car, null)).isTrue()
    }

    @Test
    fun eventLogKeepsNewestEntries() {
        var now = 0L
        val log = EventLog(
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE),
            clock = { now },
        )
        repeat(EventLog.MAX_ENTRIES + 5) {
            now = it.toLong()
            log.log("event $it\nwith newline")
        }

        val entries = log.entries()
        assertThat(entries).hasSize(EventLog.MAX_ENTRIES)
        assertThat(entries.first()).isEqualTo(EventLog.Entry(5, "event 5 with newline"))
        assertThat(entries.last().message).isEqualTo("event ${EventLog.MAX_ENTRIES + 4} with newline")

        log.clear()
        assertThat(log.entries()).isEmpty()
    }

    @Test
    fun carSettingsDefaultsAndUpdates() {
        val settings = CarSettings(ApplicationProvider.getApplicationContext<Context>())
        assertThat(settings.get()).isEqualTo(CarConfig(null, null, null, autoPlay = true))

        settings.setCar(car, "HandsFreeLink", 3)
        settings.setAutoPlay(false)
        assertThat(settings.get()).isEqualTo(CarConfig(car, "HandsFreeLink", 3, autoPlay = false))

        settings.clearCar()
        assertThat(settings.get()).isEqualTo(CarConfig(null, null, null, autoPlay = false))
    }
}

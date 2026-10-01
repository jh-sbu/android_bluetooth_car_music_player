package dev.carplayer

import android.app.Application
import android.content.Context
import dev.carplayer.car.CarSettings
import dev.carplayer.car.EventLog
import dev.carplayer.library.LibraryTree
import dev.carplayer.library.MediaStoreTrackSource
import dev.carplayer.playback.PlaybackSnapshotStore

class CarPlayerApp : Application() {
    val graph by lazy { AppGraph(this) }
}

/** Process-wide singletons; small enough that a DI framework isn't worth it. */
class AppGraph(context: Context) {
    val trackSource = MediaStoreTrackSource(context.contentResolver)
    val libraryTree = LibraryTree(trackSource)
    val snapshotStore = PlaybackSnapshotStore(context)
    val carSettings = CarSettings(context)
    val eventLog = EventLog(context)
}

val Context.appGraph: AppGraph get() = (applicationContext as CarPlayerApp).graph

package dev.carplayer.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.carplayer.car.CarConfig
import dev.carplayer.car.EventLog
import dev.carplayer.car.PairedDevice
import dev.carplayer.library.Track
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOG_TIME = DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    hasMusicPermission: Boolean,
    onRequestMusicPermission: () -> Unit,
    onChooseCar: () -> Unit,
    onForgetCar: () -> Unit,
    pairedDevices: List<PairedDevice>?,
    onCarPicked: (PairedDevice) -> Unit,
    onDismissPicker: () -> Unit,
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val car by viewModel.car.collectAsStateWithLifecycle()
    val simulating by viewModel.simulating.collectAsStateWithLifecycle()
    var showLog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showLog) "Car event log" else "CarPlayer") },
                navigationIcon = {
                    if (showLog) {
                        IconButton(onClick = { showLog = false }) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (showLog) {
                        TextButton(onClick = viewModel::clearLog) { Text("Clear") }
                    } else {
                        IconButton(onClick = { viewModel.loadLibrary(refresh = true) }) {
                            Icon(Icons.Default.Refresh, "Rescan music")
                        }
                        IconButton(onClick = { showLog = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, "Car event log")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!showLog) {
                MiniPlayer(
                    nowPlaying = nowPlaying,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                    onShuffle = viewModel::toggleShuffle,
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        if (showLog) {
            val events by viewModel.events.collectAsStateWithLifecycle()
            EventLogList(events, modifier)
        } else {
            LazyColumn(modifier) {
                item {
                    CarCard(
                        car = car,
                        simulating = simulating,
                        onChooseCar = onChooseCar,
                        onForgetCar = onForgetCar,
                        onAutoPlayChanged = viewModel::setAutoPlay,
                        onSimulate = viewModel::simulateCarConnection,
                    )
                }
                if (!hasMusicPermission) {
                    item { PermissionCard(onRequestMusicPermission) }
                } else {
                    trackList(tracks, nowPlaying.mediaId, viewModel)
                }
            }
        }
    }

    if (pairedDevices != null) {
        CarPickerDialog(pairedDevices, onCarPicked, onDismissPicker)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackList(
    tracks: List<Track>?,
    currentMediaId: String?,
    viewModel: PlayerViewModel,
) {
    when {
        tracks == null -> item {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        tracks.isEmpty() -> item {
            Text(
                "No music found. Copy audio files to the phone's Music folder, then tap rescan.",
                Modifier.padding(16.dp),
            )
        }
        else -> {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${tracks.size} songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.shuffleAll(tracks) }) {
                        Icon(Icons.Default.Shuffle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Shuffle all")
                    }
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                val current = track.mediaId == currentMediaId
                ListItem(
                    headlineContent = {
                        Text(
                            track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.Bold else null,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(track.artist, track.album).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = { Text(DateUtils.formatElapsedTime(track.durationMs / 1000)) },
                    modifier = Modifier.clickable { viewModel.play(tracks, index) },
                )
            }
        }
    }
}

@Composable
private fun CarCard(
    car: CarConfig,
    simulating: Boolean,
    onChooseCar: () -> Unit,
    onForgetCar: () -> Unit,
    onAutoPlayChanged: (Boolean) -> Unit,
    onSimulate: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (car.associationId == null) {
                        Text("No car set up", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pair the car in Bluetooth settings first, then choose it here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(car.name ?: "Car", style = MaterialTheme.typography.titleMedium)
                        Text(car.address.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (car.associationId == null) {
                    Button(onClick = onChooseCar) { Text("Choose car") }
                } else {
                    TextButton(onClick = onForgetCar) { Text("Forget") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Play music when the car connects", Modifier.weight(1f))
                Switch(checked = car.autoPlay, onCheckedChange = onAutoPlayChanged)
            }
            OutlinedButton(onClick = onSimulate, enabled = !simulating, modifier = Modifier.fillMaxWidth()) {
                Text(if (simulating) "Simulating…" else "Simulate car connection")
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CarPlayer needs access to the music on this phone.")
            Button(onClick = onRequest) { Text("Allow access") }
        }
    }
}

@Composable
private fun CarPickerDialog(
    devices: List<PairedDevice>,
    onPick: (PairedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Which one is the car?") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired Bluetooth devices. Pair with the car in system Bluetooth settings first.")
            } else {
                LazyColumn {
                    items(devices, key = { it.address }) { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.address) },
                            leadingContent = { if (device.looksLikeCar) Icon(Icons.Default.DirectionsCar, "Car") },
                            modifier = Modifier.clickable { onPick(device) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MiniPlayer(
    nowPlaying: NowPlaying,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = {
                    if (nowPlaying.durationMs > 0) (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        nowPlaying.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    nowPlaying.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconToggleButton(checked = nowPlaying.shuffle, onCheckedChange = { onShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        "Shuffle",
                        tint = if (nowPlaying.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous") }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (nowPlaying.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun EventLogList(events: List<EventLog.Entry>, modifier: Modifier) {
    if (events.isEmpty()) {
        Text(
            "Nothing yet. Car connections, auto-play attempts and their outcomes show up here.",
            modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier) {
        items(events.asReversed()) { entry ->
            ListItem(
                overlineContent = {
                    Text(LOG_TIME.format(Instant.ofEpochMilli(entry.timeMs).atZone(ZoneId.systemDefault())))
                },
                headlineContent = { Text(entry.message) },
            )
        }
    }
}

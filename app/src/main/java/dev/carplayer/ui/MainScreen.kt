package dev.carplayer.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.carplayer.car.CarConfig
import dev.carplayer.car.EventLog
import dev.carplayer.car.PairedDevice
import dev.carplayer.library.Track
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val LOG_TIME = DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")

private enum class Page { Library, Car, Log }

private enum class Tab(val label: String) {
    NowPlaying("Now Playing"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
}

/** An album, artist or playlist opened from its tab. */
private data class Detail(val title: String, val tracks: List<Track>)

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
    val library by viewModel.library.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val car by viewModel.car.collectAsStateWithLifecycle()
    val simulating by viewModel.simulating.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(Page.Library) }
    var tab by rememberSaveable { mutableStateOf(Tab.NowPlaying) }
    var albumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var artistName by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Hoisted so each tab keeps its scroll position while you switch between tabs.
    val songsList = rememberLazyListState()
    val albumsList = rememberLazyListState()
    val artistsList = rememberLazyListState()
    val playlistsList = rememberLazyListState()

    val detail: Detail? = library?.let { lib ->
        when (tab) {
            Tab.Albums -> lib.albums.firstOrNull { it.id == albumId }?.let { Detail(it.title, it.tracks) }
            Tab.Artists -> lib.artists.firstOrNull { it.name == artistName }?.let { Detail(it.name, it.tracks) }
            Tab.Playlists -> lib.playlists.firstOrNull { it.id == playlistId }?.let { Detail(it.name, it.tracks) }
            else -> null
        }
    }
    val closeDetail = {
        when (tab) {
            Tab.Albums -> albumId = null
            Tab.Artists -> artistName = null
            Tab.Playlists -> playlistId = null
            else -> {}
        }
    }

    BackHandler(enabled = page != Page.Library || detail != null) {
        if (page != Page.Library) page = Page.Library else closeDetail()
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val addToNowPlaying: (List<Track>) -> Unit = { tracks ->
        viewModel.addToNowPlaying(tracks)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                if (tracks.size == 1) "Added \u201c${tracks.single().title}\u201d to Now Playing"
                else "Added ${count(tracks.size, "song")} to Now Playing",
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            page == Page.Log -> "Car event log"
                            page == Page.Car -> "Car"
                            detail != null -> detail.title
                            else -> "CarPlayer"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (page != Page.Library || detail != null) {
                        IconButton(onClick = { if (page != Page.Library) page = Page.Library else closeDetail() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    when (page) {
                        Page.Log -> TextButton(onClick = viewModel::clearLog) { Text("Clear") }
                        Page.Car -> {}
                        Page.Library -> {
                            IconButton(onClick = { viewModel.loadLibrary(refresh = true) }) {
                                Icon(Icons.Default.Refresh, "Rescan music")
                            }
                            IconButton(onClick = { page = Page.Car }) {
                                Icon(Icons.Default.DirectionsCar, "Car setup")
                            }
                            IconButton(onClick = { page = Page.Log }) {
                                Icon(Icons.AutoMirrored.Filled.List, "Car event log")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (page == Page.Library) {
                MiniPlayer(
                    nowPlaying = nowPlaying,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                    onShuffle = viewModel::toggleShuffle,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (page) {
            Page.Log -> {
                val events by viewModel.events.collectAsStateWithLifecycle()
                EventLogList(events, modifier)
            }
            Page.Car -> Column(modifier.verticalScroll(rememberScrollState())) {
                CarCard(
                    car = car,
                    simulating = simulating,
                    onChooseCar = onChooseCar,
                    onForgetCar = onForgetCar,
                    onAutoPlayChanged = viewModel::setAutoPlay,
                    onSimulate = viewModel::simulateCarConnection,
                )
            }
            Page.Library -> Column(modifier) {
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                    Tab.entries.forEach {
                        Tab(
                            selected = it == tab,
                            // Tapping the open tab again goes back to its list.
                            onClick = { if (it == tab) closeDetail() else tab = it },
                            text = { Text(it.label, maxLines = 1) },
                        )
                    }
                }
                val content = Modifier.weight(1f).fillMaxWidth()
                val lib = library
                when {
                    tab == Tab.NowPlaying -> NowPlayingTab(queue, nowPlaying.index, viewModel::skipTo, content)
                    !hasMusicPermission -> Box(content) { PermissionCard(onRequestMusicPermission) }
                    lib == null -> Box(content.padding(32.dp), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator()
                    }
                    lib.songs.isEmpty() -> Text(
                        "No music found. Copy audio files to the phone's Music folder, then tap rescan.",
                        content.padding(16.dp),
                    )
                    detail != null -> TrackList(
                        tracks = detail.tracks,
                        currentMediaId = nowPlaying.mediaId,
                        onPlay = viewModel::play,
                        onShuffle = viewModel::shuffleAll,
                        onAdd = addToNowPlaying,
                        modifier = content,
                    )
                    tab == Tab.Songs -> TrackList(
                        tracks = lib.songs,
                        currentMediaId = nowPlaying.mediaId,
                        onPlay = viewModel::play,
                        onShuffle = viewModel::shuffleAll,
                        onAdd = addToNowPlaying,
                        modifier = content,
                        state = songsList,
                        shuffleLabel = "Shuffle all",
                    )
                    tab == Tab.Albums -> LazyColumn(content, albumsList) {
                        items(lib.albums, key = { it.id }) { album ->
                            LibraryRow(
                                headline = album.title,
                                supporting = listOfNotNull(album.artist, count(album.tracks.size, "song")).joinToString(" · "),
                                onClick = { albumId = album.id },
                                onAddToNowPlaying = { addToNowPlaying(album.tracks) },
                            )
                        }
                    }
                    tab == Tab.Artists -> LazyColumn(content, artistsList) {
                        items(lib.artists, key = { it.name }) { artist ->
                            LibraryRow(
                                headline = artist.name,
                                supporting = "${count(artist.albumCount, "album")} · ${count(artist.tracks.size, "song")}",
                                onClick = { artistName = artist.name },
                                onAddToNowPlaying = { addToNowPlaying(artist.tracks) },
                            )
                        }
                    }
                    tab == Tab.Playlists -> if (lib.playlists.isEmpty()) {
                        Text(
                            "No playlists found. Playlist files (.m3u) copied to the phone's Music folder show up here " +
                                "after a rescan.",
                            content.padding(16.dp),
                        )
                    } else {
                        LazyColumn(content, playlistsList) {
                            items(lib.playlists, key = { it.id }) { playlist ->
                                LibraryRow(
                                    headline = playlist.name,
                                    supporting = count(playlist.tracks.size, "song"),
                                    onClick = { playlistId = playlist.id },
                                    onAddToNowPlaying = { addToNowPlaying(playlist.tracks) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pairedDevices != null) {
        CarPickerDialog(pairedDevices, onCarPicked, onDismissPicker)
    }
}

private fun count(n: Int, noun: String) = "$n ${if (n == 1) noun else "${noun}s"}"

/**
 * A list of songs. Tapping one replaces Now Playing with this whole list, starting at that song;
 * long-pressing one offers to add just that song to Now Playing.
 */
@Composable
private fun TrackList(
    tracks: List<Track>,
    currentMediaId: String?,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onAdd: (List<Track>) -> Unit,
    modifier: Modifier,
    state: LazyListState = rememberLazyListState(),
    shuffleLabel: String = "Shuffle",
) {
    LazyColumn(modifier, state) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(count(tracks.size, "song"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { onShuffle(tracks) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Default.Shuffle, null)
                    Spacer(Modifier.width(8.dp))
                    Text(shuffleLabel)
                }
            }
        }
        // No keys: a playlist can list the same song twice.
        itemsIndexed(tracks) { index, track ->
            LibraryRow(
                headline = track.title,
                supporting = listOfNotNull(track.artist, track.album).joinToString(" · "),
                trailing = DateUtils.formatElapsedTime(track.durationMs / 1000),
                highlighted = track.mediaId == currentMediaId,
                onClick = { onPlay(tracks, index) },
                onAddToNowPlaying = { onAdd(listOf(track)) },
            )
        }
    }
}

/** A song, album, artist or playlist row: tap to open or play, hold for "Add to Now Playing". */
@Composable
private fun LibraryRow(
    headline: String,
    supporting: String?,
    onClick: () -> Unit,
    onAddToNowPlaying: () -> Unit,
    trailing: String? = null,
    highlighted: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = {
                Text(
                    headline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (highlighted) FontWeight.Bold else null,
                    color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            trailingContent = trailing?.let { { Text(it) } },
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "Add to Now Playing",
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = DpOffset(16.dp, 0.dp)) {
            DropdownMenuItem(
                text = { Text("Add to Now Playing") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = {
                    menuOpen = false
                    onAddToNowPlaying()
                },
            )
        }
    }
}

@Composable
private fun NowPlayingTab(
    queue: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    if (queue.isEmpty()) {
        Text(
            "Nothing queued. Press play to pick up where you left off, or choose something from " +
                "Songs, Albums, Artists or Playlists.",
            modifier.padding(16.dp),
        )
        return
    }
    val position = queue.indexOfFirst { it.index == currentIndex }
    // Item 0 is the header, so this opens with the previous song peeking above the current one.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = position.coerceAtLeast(0))
    LazyColumn(modifier, state) {
        item {
            Text(
                if (position >= 0) "Song ${position + 1} of ${queue.size}" else count(queue.size, "song"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        itemsIndexed(queue) { i, entry ->
            val current = i == position
            ListItem(
                leadingContent = {
                    Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                        if (current) {
                            Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("${i + 1}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                headlineContent = {
                    Text(
                        entry.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (current) FontWeight.Bold else null,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = entry.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                modifier = Modifier.clickable { onSelect(entry.index) },
            )
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

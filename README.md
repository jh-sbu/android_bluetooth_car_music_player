# CarPlayer

A local-music player for Pixel phones that starts playing on its own when the phone connects to
the car over Bluetooth. Built for, and tuned for, a 2013 Honda Accord (A2DP audio + AVRCP remote
control / track info; the head unit shows up as **HandsFreeLink**).

## How it works

| Piece | What it does |
| --- | --- |
| `playback/PlaybackService` | Media3 `MediaLibraryService` + ExoPlayer. Its media session is what the Bluetooth stack talks to: the car's play/pause/skip buttons arrive as AVRCP commands, and title/artist/album on the head unit come from the session metadata. Pauses when A2DP drops ("audio becoming noisy") so the engine turning off doesn't start the phone speaker. |
| Playback resumption | `play` with nothing queued (a Bluetooth play button with the app killed, or auto-play) restores the last queue and position. The first time, it shuffles the whole library. |
| `car/CarAssociation` | Registers the paired car with `CompanionDeviceManager`. That is what gets the app woken when the car connects, **and** what exempts it from Android's "no foreground services from the background" rule. |
| `car/CarPresenceService` | Bound by the system when the car's Bluetooth link comes up. Hands off to `AutoPlayer`. |
| `car/AutoPlayer` | Waits (up to 60 s) for the car's **A2DP** output to exist (the link and the hands-free profile come up first; playing before A2DP would use the phone speaker), waits 1.5 s more so the head unit can switch source, then plays. |
| `car/EventLog` | Persistent log of connection / auto-play events, visible in the app (list icon, top right). You won't have logcat in the car, so check this after a drive. |
| `car/BluetoothLinkReceiver` | Diagnostics only: logs raw link up/down for the car, so you can tell "phone never saw the car" apart from "CompanionDeviceManager didn't wake us". |

Music comes from MediaStore (anything in the phone's `Music/` etc. that Android flags as music).
Playlists are the playlist files (`.m3u` etc.) that MediaStore has indexed.

The app has five tabs: **Now Playing** (the queue; tap a song to jump to it), **Songs**,
**Albums**, **Artists** and **Playlists**. Tapping a song replaces Now Playing with the list it's in
(all songs, the album, the artist or the playlist), starting at that song. Holding down on a song,
album, artist or playlist offers **Add to Now Playing**, which appends it to the queue. Car setup
is behind the car icon at the top right.
The browse tree (All songs / Albums) is exposed to Bluetooth browsing clients too, although a 2013
head unit probably doesn't browse.

## Build and install

Requires JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`). The Gradle wrapper downloads
everything else.

```sh
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk (3.4 MB)
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs without keystore setup. Debug and
release can be installed over each other.

## Tests

```sh
./gradlew :app:testDebugUnitTest            # JVM/Robolectric: MediaStore mapping, browse tree,
                                            # queue restore, A2DP route matching, settings, log
./gradlew :app:connectedDebugAndroidTest    # on a connected phone; plays quiet test tones
```

The device tests (`PlaybackServiceTest`) run against the real service. They put the fixture tracks
into MediaStore as app-owned files (no permissions needed, the user's library is untouched, and
the files are removed afterwards). They then check that playback runs, that the **system** media
session shows the track metadata (what AVRCP sends to the car), and that media keys from
`cmd media_session dispatch` (the same routing the car's buttons take) play, pause and skip. They
also cover resuming a saved queue and position from an empty player, the auto-play flow, and the
browse tree.

### Fixtures

`scripts/make-fixtures.sh` generates three 12-second tagged MP3s (soft A/D/E major chords, album
"Test Tones", artist "CarPlayer Fixtures") into `fixtures/` and `app/src/androidTest/assets/`.
Requires ffmpeg with libmp3lame.

To have something to play on the phone:

```sh
adb shell mkdir -p /sdcard/Music/CarPlayerFixtures
adb push fixtures/*.mp3 /sdcard/Music/CarPlayerFixtures/
# remove later: adb shell rm -r /sdcard/Music/CarPlayerFixtures
```

## Checks before the first car trip (at home, laptop is fine)

1. Install, open, **Allow access**. The songs should list in the **Songs** tab.
2. Tap the car icon (top right), then **Simulate car connection**. Music should start, and the event log should show
   `Simulated car connection` → `Playback started: …`.
3. Kill the app (swipe it away), then press play on any Bluetooth headset or run
   `adb shell cmd media_session dispatch play`. Music should resume where it left off.
4. Car icon → **Choose car**: pick the Accord (`HandsFreeLink`) from the paired list, then confirm the
   system dialog. The phone must have been **paired with the car before**. The car doesn't need to
   be nearby for this step: the system matches the already-paired device and doesn't scan.
   If it isn't paired yet, do step 4 in the car after pairing.

## In the car

1. Phone in pocket, screen locked, app not open. Start the car.
2. Expected: within a few seconds of the head unit showing the phone as connected, music starts,
   and the head unit shows title and artist. Steering-wheel/head-unit next/prev/pause work.
3. Engine off: playback pauses (it doesn't continue on the phone speaker).
4. Afterwards open the app → event log. A good run looks like:
   `Bluetooth link to car connected` → `Car connected (presence event)` →
   `Car audio (A2DP) ready after N ms` → `Playback started: …`.

If something goes wrong, the log tells you where it stopped:

| Log shows | Likely cause / what to try |
| --- | --- |
| Nothing at all | Car not set up in the app, or Bluetooth off. |
| `Bluetooth link…connected` but no `Car connected` | CompanionDeviceManager didn't wake the app. Forget the car in the app and choose it again. |
| `Car audio (A2DP) didn't connect within 60s` | In system Bluetooth settings → HandsFreeLink → make sure **Media audio** is on. |
| `Playback started` but no sound | The head unit isn't on its Bluetooth Audio source. Some units only switch when asked: press AUX/Audio source until "BT Audio". |
| Sound but no title/artist on the display | Try Developer options → **Bluetooth AVRCP version** → 1.4 (or 1.3), then reconnect. Older Honda units can be picky about newer AVRCP versions. |
| Buttons do nothing | Same AVRCP version setting as above. |

Toggle **Play music when the car connects** off if you only want the car's own play button to
start things (resumption from a killed app still works).

## Layout

```
app/src/main/java/dev/carplayer/
  CarPlayerApp.kt           process-wide singletons (AppGraph)
  library/                  Track model, MediaStore songs/playlists, album/artist grouping, browse tree
  playback/                 PlaybackService, queue persistence
  car/                      CDM association, presence service, auto-play, A2DP wait, event log
  ui/                       Compose UI (library tabs, car setup, event log)
scripts/make-fixtures.sh    fixture generator
fixtures/                   generated test tracks
```

Versions: AGP 9.4.1 (built-in Kotlin, Kotlin 2.4.20), Gradle 9.8.0, Media3 1.11.1, compileSdk 37,
targetSdk 36, minSdk 33.

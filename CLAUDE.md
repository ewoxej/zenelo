# Zenelo

Android audio player for the FiiO JM21 (Android 13, 4.7" screen). Design: Figma "Zenelo" (SVG export was
`~/Downloads/div.sc-host.svg`) — dark theme, mustard `#d4a24c` + celadon `#9fd18b`, IBM Plex Sans / Mono.
All palette values live in `ZeneloColors` (ui/theme/Theme.kt); take new colors from the SVG, not by eye.
Design screens: Now Playing (lyrics ← cover → queue pager), Folder browser, Track list swipes,
Swipe settings (sub-screen of Settings), Favorites, Notification player.

## Decisions
- Kotlin + Jetpack Compose, single activity, single `app` module (split into core/feature modules later if needed).
- Media3: ExoPlayer inside `PlaybackService` (MediaSessionService); UI talks to it via `PlayerController` (MediaController).
  mediaId = absolute file path.
- Sideloaded, not Play Store → `MANAGE_EXTERNAL_STORAGE` + plain `java.io.File` for the folder browser (no MediaStore/SAF).
- DSD (.dsf/.dff), ALAC, APE, WavPack via the media3 FFmpeg extension, decoded to PCM (no native DSD/DoP).
- Loudness normalization: ReplayGain tags if present, else EBU R128 measured in background and cached in Room;
  applied by `NormalizationAudioProcessor` (target −18 LUFS).
- Manual DI via `AppContainer` in `ZeneloApp`. Settings in DataStore, favorites/playlists/loudness in Room.
- Swipes: `SwipeableRow` with short/long threshold per direction → four configurable `SwipeSlot`s.
  A direction without options isn't captured (queue rows: left only, right goes to the pager).
  In pointer code read `positionChange()` before `consume()` — a consumed change reports zero.
- Queue UI edits entries by stable id (index into the queue's paths), not by position.
- Loudness: `LoudnessAnalyzer` (BS.1770, unit-tested against the reference sine; matches ffmpeg
  ebur128) measures files without ReplayGain tags in `LoudnessWorker` and for the next queued tracks.
- Tag edits go through `TagWriter` (deferred for the playing file, like cover embeds).
- File name patterns (`FilenamePattern`, unit-tested; `[...]` = optional part) are the fallback for cover / lyrics lookups
  when tags are missing or find nothing.
- Tags: jaudiotagger in Android mode (`TagReader`). Its `setImageFromData` throws on Android, so FLAC
  pictures go through `FlacTag.createArtworkField`; Ogg/Opus covers are never embedded.
- Library index: `tracks` table, filled by `LibraryIndexer` (WorkManager `IndexWorker` on app start,
  plus on-demand for the folder on screen).
- Covers & lyrics (`MetadataFetcher`): online only on Wi-Fi. Covers: embedded → folder image →
  cached → Deezer/iTunes/MusicBrainz (+ Last.fm if the user set an API key). Downloaded covers are
  embedded into files without art. Lyrics: sibling .lrc → tags → LRCLIB. Background `FetchWorker`
  on unmetered network.
- Never `replaceMediaItem` the playing item (ExoPlayer re-buffers): covers found mid-track go to
  `AppContainer.coverOverride` (UI only).
- All tag / cover writes go through `PendingWrites`. The playing file is written when its track
  ends (transition, queue end, service stop, or next app start); the request is stored in the
  `pending_writes` table. Meanwhile the edit shows everywhere at once: the `tracks` row is updated
  in place (old mtime, so indexing won't read the old tags back), `PlayQueue.invalidate` refreshes
  the queue / Now Playing, `Thumbnails.override` + `coverOverride` the covers.
- Now Playing is not a nav destination but a `PlayerSheet` over the scaffold (ZeneloRoot). Collapsed,
  the sheet's top is the mini player, resting above the tab bar (tab bar drawn over the sheet; the
  Scaffold's bottomBar only reserves their measured height). Dragging moves the sheet under the
  finger, crossfades mini player → Now Playing and slides the tab bar away. Now Playing is composed
  only while any of it shows; the mini player stays composed while a finger is down (removing it
  would cancel the drag it started). Outside the Scaffold → provide LocalContentColor yourself.
- Multi-select (browser: folders + files, queue): long-press selects; the check mark side is a
  setting (`SelectionMarkerSide`). Queue drag handles swallow their down event so holding one
  still doesn't long-press the row.
- Playback state survives restarts: `PlayQueue` writes the queue (paths, shuffle order, current,
  shuffle, repeat) to `files/queue.txt` 500 ms after each change (`SavedQueue`), the position to
  `queue.txt.position` (on pause, seek, every 10 s, service stop); `PlaybackService.onCreate`
  calls `queue.restore()`, which loads it paused. Never save an empty queue (the pre-restore state).
- Reorderable lists (queue, patterns): keep ONE state object for the list for the composable's
  lifetime — the reorder library keeps its first onMove lambda, so re-created state breaks drags.
- Queue: `PlayQueue` owns the full queue; ExoPlayer only holds a window (10 back, current, ~30
  ahead) that slides on each transition. Never put a whole folder into the player: with 7000 items
  every timeline update re-sends everything through the media session and stalls the main thread.
  Shuffle and repeat-all live in `PlayQueue` (player shuffle order is identity; repeat-all uses
  virtual positions that wrap). The UI edits the queue directly (same process), not via the controller.

## Roadmap (not done yet)
1. FFmpeg decoder dependency (verify DSD support in Jellyfin's prebuilt, else build from source).
2. Crossfade (two ExoPlayers behind a forwarding Player), 0 = gapless.
3. Playlist detail screen + "add to playlist" picker.
4. Bundle IBM Plex fonts in `res/font`.

## Build
SDK: `/opt/homebrew/share/android-commandlinetools` (set in `local.properties`). JDK 17 from Homebrew:
```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. Judge performance on `assembleRelease` (R8, signed
with the debug key), not on debug — Compose debug builds are several times slower.

Releases: GitHub Actions `Release` workflow (manual, input `version` → tag `v<version>`, APK attached to
the GitHub release). It signs with the key from secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` → env `ZENELO_KEYSTORE*`); `-PversionName=1.2.3` sets versionCode 10203.
Local builds without those env vars stay signed with the debug key.

Emulator: AVD `zenelo_jm21` (720×1280 @ 320dpi ≈ the JM21's 360×640dp). Grant file access with
`adb shell appops set app.zenelo MANAGE_EXTERNAL_STORAGE allow`.

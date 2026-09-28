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
- Tags: jaudiotagger in Android mode (`TagReader`). Its `setImageFromData` throws on Android, so FLAC
  pictures go through `FlacTag.createArtworkField`; Ogg/Opus covers are never embedded.
- Library index: `tracks` table, filled by `LibraryIndexer` (WorkManager `IndexWorker` on app start,
  plus on-demand for the folder on screen).
- Covers & lyrics (`MetadataFetcher`): online only on Wi-Fi. Covers: embedded → folder image →
  cached → Deezer/iTunes/MusicBrainz (+ Last.fm if the user set an API key). Downloaded covers are
  embedded into files without art. Lyrics: sibling .lrc → tags → LRCLIB. Background `FetchWorker`
  on unmetered network.
- Never rewrite the playing file and never `replaceMediaItem` the playing item (ExoPlayer re-buffers):
  covers found mid-track go to `AppContainer.coverOverride` (UI only); the embed waits for track change.
- Queue: `PlayQueue` owns the full queue; ExoPlayer only holds a window (10 back, current, ~30
  ahead) that slides on each transition. Never put a whole folder into the player: with 7000 items
  every timeline update re-sends everything through the media session and stalls the main thread.
  Shuffle and repeat-all live in `PlayQueue` (player shuffle order is identity; repeat-all uses
  virtual positions that wrap). The UI edits the queue directly (same process), not via the controller.

## Roadmap (not done yet)
1. FFmpeg decoder dependency (verify DSD support in Jellyfin's prebuilt, else build from source).
2. R128 analyzer (WorkManager) → `LoudnessDao` for files without ReplayGain tags.
3. Pending cover embeds for the playing track live in memory; persist them so a process kill doesn't drop them.
4. Crossfade (two ExoPlayers behind a forwarding Player), 0 = gapless.
5. Playlist detail screen + "add to playlist" picker; queue reorder (sh.calvin.reorderable).
6. Bundle IBM Plex fonts in `res/font`.

## Build
SDK: `/opt/homebrew/share/android-commandlinetools` (set in `local.properties`). JDK 17 from Homebrew:
```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. Judge performance on `assembleRelease` (R8, signed
with the debug key), not on debug — Compose debug builds are several times slower.

Emulator: AVD `zenelo_jm21` (720×1280 @ 320dpi ≈ the JM21's 360×640dp). Grant file access with
`adb shell appops set app.zenelo MANAGE_EXTERNAL_STORAGE allow`.

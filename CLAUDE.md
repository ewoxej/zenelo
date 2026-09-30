# Zenelo

Android audio player for the FiiO JM21 (Android 13, 4.7" screen). Design: Figma "Zenelo" (SVG export was
`~/Downloads/div.sc-host.svg`) — dark theme, mustard `#d4a24c` + celadon `#9fd18b`, IBM Plex Sans / Mono.
All palette values live in `ZeneloColors` (ui/theme/Theme.kt); take new colors from the SVG, not by eye.
Design screens: Now Playing (lyrics ← cover → queue pager), Folder browser, Track list swipes,
Swipe settings (sub-screen of Settings), Favorites, Notification player. Home + library pages
(Home, Customize home, Albums, Artists, All tracks, Recently played, Sort menu): bundled HTML
`~/Downloads/Player Home JM21.html` (unpack its `__bundler/manifest` + template to view it).

## Decisions
- Kotlin + Jetpack Compose, single activity, single `app` module (split into core/feature modules later if needed).
- Media3: ExoPlayer inside `PlaybackService` (MediaSessionService); UI talks to it via `PlayerController` (MediaController).
  mediaId = absolute file path.
- Sideloaded, not Play Store → `MANAGE_EXTERNAL_STORAGE` + plain `java.io.File` for the folder browser (no MediaStore/SAF).
- DSD (.dsf/.dff), ALAC, APE, WavPack via the media3 FFmpeg extension, decoded to PCM (no native DSD/DoP).
- Loudness normalization: ReplayGain tags if present, else EBU R128 measured in background and cached in Room;
  applied by `NormalizationAudioProcessor` (target −18 LUFS) + user pre-amp (−6…+6 dB). The gain is
  bounded by the peak (ReplayGain `*_PEAK` tags, else our measured peak) so it never hard-clips
  (`LoudnessRepository.limitToPeak`, unit-tested). Mode / pre-amp changes re-apply to the playing track.
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
- MP3 seeking: the players use `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING` (exact seeks). The default
  for MP3s without a Xing / VBRI table assumes constant bitrate: on VBR files the length was a guess
  (12 of 30 min) and seeks landed near the real end, so the track "skipped" soon after. Such files
  have no player duration until their end: `Mp3Scan` (unit-tested) counts their frames at indexing,
  the MediaItem carries that length (`MediaMetadata.durationMs`), and the UI / `Crossfader` fall back to it.
- Library index: `tracks` table, filled by `LibraryIndexer` (WorkManager `IndexWorker` on app start,
  plus on-demand for the folder on screen).
- Covers & lyrics (`MetadataFetcher`): online only on Wi-Fi. Covers: embedded → folder image →
  cached → Deezer/iTunes/MusicBrainz (+ Last.fm if the user set an API key). Downloaded covers are
  embedded into files without art. Online search is by tags (album artist + album, else the song);
  names guessed from the path (file name patterns) only when the tags have no artist — then the
  cover is shown but never embedded (its source ends in " (by path)"). `Text.matches` (unit-tested)
  is equality or a word-boundary prefix, not "contains": folder names like "Music" / "Lyrics" once
  matched "VedicDhvani Music" and got its cover written into files. Lyrics: sibling .lrc → tags → LRCLIB. Background `FetchWorker`
  on unmetered network. LRCLIB queries go from the tags as they are to cleaned-up variants
  (`LyricsSearch`, unit-tested: "Song - Live" / "(feat. …)" / "[site.net]" dropped, first artist of
  a multi-artist tag): exact get → searches by title × artist → free-text search. A record counts only
  when title and artist match after clean-up; same length (±3 s) → synced, else the closest version's
  text as plain (its timing wouldn't fit).
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
  would cancel the drag it started). Touches inside the sheet arrive in its moving coordinates: the pull-down adds
  `PlayerSheet.drawnOffsetPx` (the translation as drawn) — without it the sheet lagged the finger
  and flings read as zero speed, so collapsing needed a pull to mid-screen. Outside the Scaffold → provide LocalContentColor yourself.
- Multi-select (browser: folders + files, queue): long-press selects; the check mark side is a
  setting (`SelectionMarkerSide`). Queue drag handles swallow their down event so holding one
  still doesn't long-press the row.
- Playback state survives restarts: `PlayQueue` writes the queue (paths, shuffle order, current,
  shuffle, repeat) to `files/queue.txt` 500 ms after each change (`SavedQueue`), the position to
  `queue.txt.position` (on pause, seek, every 10 s, service stop); `PlaybackService.onCreate`
  calls `queue.restore()`, which loads it paused. Never save an empty queue (the pre-restore state).
  The process outlives the service (a paused app in the background loses its service once
  the paused foreground ends, see below): `PlayQueue.detach` writes the queue at once and empties it in memory, so the next
  service's `restore()` reads it back instead of keeping a queue its new empty player never got.
- Navigation: the bottom bar is a setting (`tabs`, ≤5 `Section`s, may be empty → Home is the only
  page) and so is Home (`home`: each section Off / Icon / Grid / List, ordered). Tabs are pager
  pages; other sections open as `section/{name}` routes with a back arrow. Settings must stay
  reachable (`Navigation.settingsReachable`): `SettingsRepository.setNavigation` refuses otherwise.
  The last tab is remembered (`lastTab`); MainScaffold waits for settings before composing.
  First start of this version (DataStore migration `FirstNavigation`, while no `tabs` is stored):
  if the database already existed (an upgrade), the old fixed bar `Navigation.LEGACY_TABS`
  (Folders, Favorites, Playlists, Settings) is written, else `DEFAULT_TABS`.
- Library pages (`ui/library`) read `Library` (albums by `albumKey`, artists by album artist else
  artist, split into names by `ArtistSplitter` — separators + never-split names are settings, unit-tested —
  and keyed by `artistKey` (lowercase); grouped in Kotlin, not SQL; all tracks, plays) through the activity-scoped `LibraryViewModel`; sorting in
  `LibrarySort` (unit-tested). Per-page sort (`SortPage`) and grid/list view live in settings; the
  browser uses the same sort menu. No release-date sort: tags don't carry a year in the index.
- "Recently played": `PlaybackService` counts a play after 30 s (half of tracks under a minute) into
  the `plays` table; the last 7 days are shown, one row per track.
- Crossfade (`Crossfader`, setting `crossfadeMs`, 0 = gapless): not a forwarding Player. The main
  ExoPlayer stays the only one the queue / session / notification see; a "tail" ExoPlayer (no audio
  focus, own normalization processor) loads the current track at the fade point 4 s ahead, then
  plays the old track's end fading out while main skips to the next track fading in. Only natural
  ends fade; pause / seek / skip cut the fade; a track that reaches the fade zone by a seek (tail
  not ready) plays to its end without fading. Pending writes wait for the fade's end (the tail
  still reads the old file). Gains are set before the audio is processed (processors apply the
  gain when they process, and players buffer ahead): the tail's when it's prepared, main's for the
  next track just before the switch (`gainOf`, worked out while the tail loads) — else the fade
  started at the wrong loudness and jumped. A tail not READY at the fade point (slow stream) means no
  fade for that track, not a late one (a hole, then the old track back). `PlaybackService` logs
  transitions (with reason), seeks, fades (begin with both gains / done / cut short / skipped) and
  player errors under the `Zenelo` tag: `adb logcat -s Zenelo` when chasing an unexpected skip / rewind.
- Paused, Media3 1.5 drops the service's foreground and Android stops an idle background service
  after ~1 min ("Stopping service due to app idle"; the notification and soon the process went):
  `PlaybackService.onUpdateNotification` keeps the foreground for 30 min of pause (`PAUSED_FOREGROUND_MS`).
- `ZeneloSlider` (seek bar, crossfade, pre-amp) reacts to taps and horizontal drags only: a touch
  that becomes a vertical gesture (scrolling Settings, swiping Now Playing down) must not set a value.
- Playlists: `playlist/{id}` screen (play, drag to reorder, remove, rename, delete). "Add to
  playlist" anywhere goes through `AppContainer.playlistPicker`; the root shows the picker dialog.
  Entries are rewritten as a whole on reorder / removal (`PlaylistDao.replace`).
  M3U / M3U8 (`M3u`, unit-tested; `PlaylistFiles`): import from the Playlists screen, export from a
  playlist's ⋮, both via the system picker (SAF). `documentPath` maps local-storage picker URIs to
  real paths, so relative entries resolve and exports write paths relative to the playlist file
  (same volume only). Entries that don't resolve are matched in the index by their path's tail.
- Settings: the main screen lists pages (`SettingsPage`: Playback, Interface, Other, About), each
  a `settings/page/{page}` route. Favorites can import an M3U too (`PlaylistFiles.importToFavorites`,
  tracks already there are kept).
- Backup (`Backup`, format `BackupFormat` = one JSON, unit-tested with org.json as a test dep):
  DataStore values (raw, typed), favorites, playlists, plays. Restore replaces all of them.
- Fonts: IBM Plex (OFL) TTFs in `res/font`, built from the Home design bundle's woff2 subsets
  (fontTools: variable Sans instanced per weight, subsets merged). Mono has no bold (synthesized).
- Reorderable lists (queue, patterns, playlists): keep ONE state object for the list for the composable's
  lifetime — the reorder library keeps its first onMove lambda, so re-created state breaks drags.
- Queue: `PlayQueue` owns the full queue; ExoPlayer only holds a window (10 back, current, ~30
  ahead) that slides on each transition. Never put a whole folder into the player: with 7000 items
  every timeline update re-sends everything through the media session and stalls the main thread.
  Shuffle and repeat-all live in `PlayQueue` (player shuffle order is identity; repeat-all uses
  virtual positions that wrap). Shuffle mode is a setting (`ShuffleMode`, orders in `Shuffle`,
  unit-tested): Tracks (uniform), Albums (albums shuffled, by track number inside), Smart (each
  track artist spread evenly, neighbours never share an artist where avoidable, plays of the last
  48 h last). Changing the mode reshuffles a shuffled queue. "Play" on a whole list (no tapped track: `startIndex` null) under
  shuffle starts at random — any track / a random album's first track / a track not played lately. The UI edits the queue directly (same process), not via the controller.

- mStream (stage 1 done): `mstream/` — `MStreamClient` (API, JWT in `x-access-token`; album art needs
  it too, despite the docs), `MStreamSync` mirrors `POST /api/v1/sync/manifest` (skipped when the
  revision is unchanged; `ServerSyncWorker` on app start / login / "Sync now") into `tracks` as
  `mstream://<vpath>/<rel>` rows + `remote_tracks` (art file, rating, lyrics flag). `MStreamPaths`
  for those paths — never `File(path)` them (`AudioFile.forPath`). `LibraryMerge` (unit-tested)
  builds the library for the chosen `LibrarySource` (setting; title tap → `SourceMenu`): "All" hides
  server twins of local files (same title/artist/album/number, or file name + size). Albums are
  grouped in Kotlin now (`Library.albumsOf`). Playback: MediaItem URI `mstream://server?p=…`,
  `RemoteMedia` (ResolvingDataSource) turns it into `<server>/media/…` + token. Server tracks:
  hasArtwork=false, covers/lyrics from the server first (`MetadataFetcher`), never embedded / tag-
  edited / deleted / loudness-measured. Cleartext HTTP allowed (home servers). Login + token are in
  settings but not in backups.
- mStream stage 2: folder browser root "mStream" = virtual `/mstream/<vpath>/…` (`RemoteFolders` maps
  it to the index's `mstream://` dirs; listings come from the synced index, no requests). Each sync
  round (`MStreamSync.sync`): push the outbox (`pending_ratings`, `play_outbox`), library (manifest),
  ratings via `db/rated` (not in the manifest revision) → favorites (8–10 = favorite; tracks with a
  pending change keep ours), server playlists → read-only `playlists.remote` copies (not in backups,
  not in the picker), server recent plays → `plays`. The other way: a favorites watcher in
  `AppContainer` → `onFavoritesChanged` (like = 10, unlike clears), counted plays → `onPlayed`;
  both sent at once, else on the next sync. Favorites ↔ ratings run under one mutex.
  Sync rounds run on app start / login / "Sync now", every 3 h in the background (periodic
  `ServerSyncWorker`, any network), and when the app comes to the front after 15 min
  (`LibraryWork.syncServerIfStale` from `MainActivity.onStart`).
- mStream stage 3: `MStreamFiles` — "Download" (SwipeAction.DOWNLOAD: menus, selection, folder ⋮,
  Now Playing) saves originals to the download folder (setting, default `/mstream` at the storage
  root) in the server's layout; they're indexed at once and are ordinary local files (download mark;
  "All" hides their server twin). Queue: `downloads` table drained by `DownloadWorker`. Queue
  auto-download (`MStreamDownloads.start`, follows `PlayQueue.state`, not track changes — those
  come before a new queue is published) fills an app-private cache evicted by size (LRU by use).
  `RemoteMedia` opens a download / cached copy first, else streams `/media`, or `/transcode` per
  `TranscodeMode` (default MP3: the server streams transcodes without a length and ignores Range,
  so only MP3's frame-index seeking works; Opus / AAC can't seek). It logs "open … from / streamed".
  `MStreamDownloads.progress` drives `DownloadPopup` (track i of n, bytes) instead of a "Downloading…" message.
- Local ↔ server (DB v12, `ServerLinks`, `server_links`): a local file that is also on the server —
  a download (linked when saved, and files at the server's path in the download folder), or the
  user's own file matched by `LibraryMerge.twins` (same tags or file name + size, and same length
  ±3 s) — counts as downloaded (download mark, no "Download", Sonic Path / Play similar / server
  playlists through it). A match is re-checked on every refresh (`LibraryMerge.stillSame`: same
  length and still the same title, file name or artist + album + number), so fixing a tag keeps it
  and a wrong match drops (downloads stay until the file is gone). Refresh is triggered by the cheap
  `TrackDao.observeChanges`, not by reading all rows per change. Through the links: "All" hides the
  server copy; a song is a favorite when liked under either path (`FavoriteDao.toggle(…, copies)`,
  `observeIsFavoriteAny`; never moved between paths — a dropped link must lose nothing) and that
  syncs with the server rating; plays and now playing of the copy count for the server track;
  Recently played / Favorites show one row, the local copy; the queue takes the local copy
  (`PlayQueue.resolvePath`) and `RemoteMedia` opens it too.
- Offline (`online/Network`): server tracks with no copy here (linked, downloaded, cached) leave the
  library, Favorites and Recently played (`Library` filters; `rememberPlayableNow`); in playlists,
  the queue and Sonic Path they're faded (`rememberUnplayable`) and `PlaybackService.skipUnplayable`
  skips them; a playlist with nothing playable is faded (`rememberUnplayablePlaylists`). Server
  features hide: Download, Auto DJ toggle, Sonic Path / Play similar (`MStreamSonicPath.available`),
  "Sounds like", the browser's mStream root; Auto DJ waits for the network without a message.
  Auto DJ / Sonic Path / Play similar are the server's (its analysis): local files only through their link.
- Genres (DB v11): `tracks.genre` from tags (`Genres.split`, unit-tested: "A; B" / "A/B" / ID3 numbers)
  and from the manifest's `genres` (the stored revision is prefixed `v<MANIFEST_VERSION>:` so a
  change in what we keep forces one full read). A local copy without a genre takes its server
  twin's (`LibraryMerge.visible`). `Section.GENRES`, `genre/{key}` page; `Library.genresOf`.
- Server playlists are editable: `PlaylistDao` marks `dirty` on any edit of a remote playlist,
  deletes leave a tombstone (`deleted`); `MStreamSync.pushPlaylists` (watcher in `AppContainer`,
  and each sync before pulling) renames / saves whole / deletes on the server. `replaceRemote`
  updates clean copies in place (same id) and leaves dirty ones alone. Server playlists only take
  server tracks: `serverCopies` maps local twins / downloads, others are skipped with a message.
  Picker work runs in `appScope` (the dialog's scope dies with it).
- Also: now playing announced (`stats/now-playing`, every 5 min while a server track plays),
  "Play similar" (`discovery/local/similar/tracks`), "Sounds like" artists on artist pages
  (`similar/artists`, else Last.fm's). Auto DJ filters as the web app: genres (whitelist /
  blacklist), length window, skip words (`AutoDjRules.hasSkipWord`), libraries (`ignoreVPaths`),
  rolling / locked sound seed.
- mStream stage 4: `MStreamAutoDj` (setting `autoDj`; Now Playing ⋮ toggle; its settings are `SettingsPage.AUTO_DJ`,
  a sub-page opened from the mStream page like `DOWNLOADS`; `listed = false` keeps them off the main Settings screen) — when the queue's last track
  starts, `db/random-songs` picks more, the body built as the web app's `_buildAutoDjBody` (ignoreList
  cursor, artist cooldown, BPM windows / Camelot neighbours via `AutoDjRules` (unit-tested), similar
  artists, `similarTo` = last picks else the playing track); a track the user started resets the
  anchors; picks with a local twin are queued as the local copy. Logs "Auto DJ asks {body}".
  Server out of reach: one message, then quiet retries every 30 s while the same track plays.
  `MStreamSonicPath` + `SonicPathScreen` (route `sonicpath`): ends set from track menus / Now Playing
  ("Sonic path from / to here", local tracks → server twin), `discovery/local/path`, play / queue / save.

## Roadmap (not done yet)
1. FFmpeg decoder dependency (verify DSD support in Jellyfin's prebuilt, else build from source).
2. mStream server support (github.com/IrosTheBeggar/mStream; API in its `docs/openapi.yaml`). Decisions:
   one server; login URL + credentials (JWT in `x-access-token`); server tracks mirrored into `tracks`
   from `POST /api/v1/sync/manifest` as `mstream://<vpath>/<rel>`; duplicates (same tags, or file name
   + size) prefer the local file; icons: cloud = server only, arrow = downloaded, local = none;
   "mStream" root in the file browser; title tap → source menu All / Local / mStream; server covers
   and lyrics when missing locally; rating 8–10 of 0–10 = favorite, like sets 10, unlike clears;
   downloads to a configurable folder (default `/mstream` at the storage root), queue auto-download
   = separate cache with a limit; transcoding (`/transcode`) off by default; Auto DJ
   (`db/random-songs`) and Sonic Path (`discovery/local/path`) as on the server. Stages:
   (1) connect, sync, icons, dedupe, source menu, streaming, covers/lyrics; (2) file explorer,
   ratings ↔ favorites, server playlists / recent; (3) transcoding, downloads, queue auto-download;
   (4) Auto DJ, Sonic Path; (5) Quick Connect (iroh tunnel, `mstr1:` code — Rust via NDK). Stages 1–4 are done; Quick Connect is postponed.

## Build
SDK: `/opt/homebrew/share/android-commandlinetools` (set in `local.properties`). JDK 17 from Homebrew:
```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. Judge performance on `assembleRelease` (R8, signed
with the debug key), not on debug — Compose debug builds are several times slower.

Version: the `VERSION` file at the repo root (e.g. `1.2.0`) is the only place it's set: Gradle reads it
(versionName, versionCode 10200), and so does the `Release` workflow. `-PversionName=` still overrides.
Releases: GitHub Actions `Release` workflow (manual; tag `v<VERSION>`, APK attached to the GitHub
release; fails if that tag exists, i.e. VERSION wasn't bumped). It signs with the key from secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` → env `ZENELO_KEYSTORE*`).
Local builds without those env vars stay signed with the debug key.
`-PappIdSuffix=.debug` (any build type) makes "Zenelo test" (`app.zenelo.debug`), installable beside
the real app on a phone; use it with `assembleRelease` when speed matters (scrolling, crashes).

Emulator: AVD `zenelo_jm21` (720×1280 @ 320dpi ≈ the JM21's 360×640dp). Grant file access with
`adb shell appops set app.zenelo MANAGE_EXTERNAL_STORAGE allow`.

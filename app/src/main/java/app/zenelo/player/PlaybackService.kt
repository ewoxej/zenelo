package app.zenelo.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.RemoteMedia
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.zenelo.MainActivity
import app.zenelo.R
import app.zenelo.ZeneloApp
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.NormalizationMode
import app.zenelo.library.Library
import app.zenelo.player.audio.NormalizationAudioProcessor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the ExoPlayer and the MediaSession. Media3 builds the media notification from the session;
 * heart and shuffle are the two custom actions from the design. Crossfade: [Crossfader].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as ZeneloApp).container }
    private val normalization = NormalizationAudioProcessor()

    private lateinit var player: ExoPlayer
    private lateinit var crossfader: Crossfader
    private var session: MediaSession? = null
    private var currentIsFavorite = false
    private var favoriteJob: Job? = null
    private var metadataJob: Job? = null
    private var positionJob: Job? = null
    private var listenJob: Job? = null

    /** Time the current track has actually played, for counting it in "Recently played". */
    private var listenedMs = 0L
    private var playCounted = false

    private fun savePosition(now: Boolean = false) =
        container.queue.savePosition(player.currentMediaItem?.mediaId, player.currentPosition, now)

    /**
     * MP3 seeks by an index of the frames built while reading (exact). The default for MP3s
     * without a Xing / VBRI seek table assumes a constant bitrate: on VBR files it guessed the
     * length (12 min for a 30 min mix) and seeks landed minutes off — near the real end, so the
     * track "jumped" to the next one (or, repeating, to its own start) soon after.
     */
    private fun mediaSources() = DefaultMediaSourceFactory(
        remoteMedia.dataSources(this),
        DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
    )

    /** Opens mStream tracks with the current login; kept up to date from settings. */
    private val remoteMedia by lazy { RemoteMedia(this, container.mstream, container.mstreamFiles) { container.settings.settings.first().mstream } }

    override fun onCreate() {
        super.onCreate()
        scope.launch { container.settings.settings.map { it.mstream }.distinctUntilChanged().collect { remoteMedia.account = it } }
        scope.launch {
            container.settings.settings.map { Triple(it.transcodeMode, it.transcodeCodec, it.transcodeBitrate) }.distinctUntilChanged()
                .collect { remoteMedia.transcode = it }
        }
        player = ExoPlayer.Builder(this, ZeneloRenderersFactory(this, normalization))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setMediaSourceFactory(mediaSources())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(PlayerListener())
        container.queue.attach(player)
        crossfader = Crossfader(
            main = player,
            scope = scope,
            buildTail = { processor ->
                // No audio focus of its own: it would take it from the main player.
                ExoPlayer.Builder(this, ZeneloRenderersFactory(this, processor))
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                    .setMediaSourceFactory(mediaSources())
                    .build()
            },
            currentGainDb = { normalization.gainDb },
            setMainGainDb = { normalization.gainDb = it },
            gainOf = { id -> upcomingGain?.takeIf { it.first == id }?.second },
            onUpcoming = { id ->
                scope.launch {
                    val track = container.db.tracks().get(id)
                    upcomingGain = id to (if (track == null) 0f else gainFor(track))
                }
            },
            // The old track's file was read until the fade ended: write what waited for it now.
            onFadeEnd = { flushPendingWrites() },
        ).also { it.start() }
        scope.launch {
            container.settings.settings.map { it.crossfadeMs }.distinctUntilChanged().collect { crossfader.fadeMs = it }
        }
        // Mode / pre-amp changes apply to the playing track at once (its gain is otherwise set on track change).
        scope.launch {
            container.settings.settings.map { it.normalization to it.preampDb }.distinctUntilChanged().drop(1).collect {
                val track = player.currentMediaItem?.mediaId?.let { container.db.tracks().get(it) }
                normalization.gainDb = if (track == null) 0f else gainFor(track)
            }
        }
        // Last session's queue, paused where it was.
        container.queue.restore()
        positionJob = scope.launch {
            while (true) {
                delay(POSITION_SAVE_MS)
                if (player.isPlaying) savePosition()
            }
        }
        listenJob = scope.launch {
            while (true) {
                delay(LISTEN_TICK_MS)
                if (player.isPlaying) {
                    countListening()
                    // Server tracks show on the server's now-playing list (throttled there).
                    val path = player.currentMediaItem?.mediaId
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { container.mstreamSync.onNowPlaying(path) }
                }
            }
        }

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        updateCustomLayout()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** When playback was paused (elapsed realtime), 0 while it's meant to play. */
    private var pausedAt = 0L
    private var pausedJob: Job? = null

    /**
     * Paused, Media3 (1.5) takes the service out of the foreground, and Android stops a background
     * app's non-foreground service about a minute later ("Stopping service due to app idle"): the
     * notification went away and the process died soon after — the app "closed by itself". So a
     * paused queue keeps the foreground (and its notification) for [PAUSED_FOREGROUND_MS], like
     * Media3 1.7's foreground timeout; after that it may go.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val recentlyPaused = pausedAt != 0L && player.mediaItemCount > 0 &&
            SystemClock.elapsedRealtime() - pausedAt < PAUSED_FOREGROUND_MS
        super.onUpdateNotification(session, startInForegroundRequired || recentlyPaused)
    }

    private fun onPlayWhenReady(playWhenReady: Boolean) {
        pausedJob?.cancel()
        if (playWhenReady) {
            pausedAt = 0L
            return
        }
        pausedAt = SystemClock.elapsedRealtime()
        pausedJob = scope.launch {
            delay(PAUSED_FOREGROUND_MS)
            // Still paused: let the service leave the foreground (the notification can be swiped away).
            session?.let { onUpdateNotification(it, false) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    /**
     * Tag edits / covers that waited for a file to stop playing. Runs in the app scope so a quick
     * skip (which cancels the per-track job) can't cut a file write short.
     */
    private fun flushPendingWrites() {
        container.appScope.launch { container.pendingWrites.flush() }
    }

    /** Counts a play once the track has played long enough (see [Library.playThresholdMs]). */
    private fun countListening() {
        if (playCounted) return
        listenedMs += LISTEN_TICK_MS
        val path = player.currentMediaItem?.mediaId ?: return
        if (listenedMs >= Library.playThresholdMs(player.duration.coerceAtLeast(0L))) {
            playCounted = true
            val played = listenedMs
            val duration = player.duration.coerceAtLeast(0L)
            container.appScope.launch {
                container.library.recordPlay(path)
                container.mstreamSync.onPlayed(path, played, duration)
            }
        }
    }

    override fun onDestroy() {
        positionJob?.cancel()
        listenJob?.cancel()
        savePosition(now = true)
        // Nothing plays any more: every waiting write can go to its file.
        container.nowPlaying.value = null
        flushPendingWrites()
        crossfader.release()
        container.queue.detach()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun updateCustomLayout() {
        val favorite = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_favorite))
            .setIconResId(if (currentIsFavorite) R.drawable.ic_notif_heart_filled else R.drawable.ic_notif_heart)
            .setSessionCommand(CMD_FAVORITE)
            .build()
        val shuffleOn = player.shuffleModeEnabled
        val shuffle = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_shuffle))
            .setIconResId(if (shuffleOn) R.drawable.ic_notif_shuffle_on else R.drawable.ic_notif_shuffle)
            .setSessionCommand(CMD_SHUFFLE)
            .build()
        session?.setCustomLayout(listOf(favorite, shuffle))
    }

    private fun onTrackChanged(item: MediaItem?) {
        val path = item?.mediaId
        // Also on repeat-one: every time round is a play of its own.
        listenedMs = 0
        playCounted = false
        container.nowPlaying.value = path
        // During a crossfade the old file still plays from the tail player: its writes wait for the fade's end.
        if (!crossfader.fading) flushPendingWrites()
        metadataJob?.cancel()
        metadataJob = scope.launch {
            val track = path?.let { container.db.tracks().get(it) ?: if (MStreamPaths.isRemote(it)) null else container.indexer.indexOne(it) }
            normalization.gainDb = if (track == null) 0f else gainFor(track)
            measureUpcoming()
            if (track == null) return@launch
            if (!track.hasArtwork && item.mediaMetadata.artworkUri == null) {
                // Shown by the UI only: replacing the playing item would make ExoPlayer re-buffer.
                container.metadata.coverFor(track, allowNetwork = true)?.let { container.coverOverride.value = path to it.absolutePath }
            }
            container.metadata.lyricsFor(track, allowNetwork = true)
        }
        // Follow the favorite flag so a heart tapped in the app shows up in the notification too.
        favoriteJob?.cancel()
        favoriteJob = scope.launch {
            val flow = if (path == null) flowOf(false) else container.db.favorites().observeIsFavoriteAny(listOf(path) + container.serverLinks.current.copiesOf(path))
            flow.collect {
                currentIsFavorite = it
                updateCustomLayout()
            }
        }
    }

    /** ReplayGain tags first, then our own measurement; 0 dB until the track has been measured. */
    private suspend fun gainFor(track: TrackEntity): Float {
        val settings = container.settings.settings.first()
        if (settings.normalization == NormalizationMode.OFF) return 0f
        val gain = container.loudness.gainFor(
            track,
            album = settings.normalization == NormalizationMode.ALBUM,
            targetLufs = TARGET_LUFS,
            preampDb = settings.preampDb,
        ) ?: 0f
        return gain.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
    }

    /** Measures the next queued tracks while this one plays, so their gain is known when they start. */
    private fun measureUpcoming() {
        val queue = container.queue.state.value
        val upcoming = (1..2).filter { it < queue.size }.map { queue.pathAt(it) }
        if (upcoming.isEmpty()) return
        // Server tracks can't be measured here (streamed); their ReplayGain tags still apply.
        scope.launch(Dispatchers.IO) { upcoming.filterNot { MStreamPaths.isRemote(it) }.forEach { container.loudness.ensureMeasured(it) } }
    }

    private var offlineSkips = 0

    /** The next track's gain, worked out while the crossfade's tail loads (see [Crossfader]). */
    @Volatile
    private var upcomingGain: Pair<String, Float>? = null

    /**
     * Offline, a server track with no copy on the device can't play: go on to the next track (the
     * queue's window follows). Returns whether it skipped. A queue with nothing playable pauses.
     */
    private fun skipUnplayable(): Boolean {
        val path = player.currentMediaItem?.mediaId ?: return false
        if (container.network.online.value || container.mstreamFiles.playsOffline(path)) {
            offlineSkips = 0
            return false
        }
        if (!player.hasNextMediaItem() || offlineSkips >= MAX_OFFLINE_SKIPS) {
            Log.i(TAG, "offline: nothing playable ahead, paused on ${path.substringAfterLast('/')}")
            offlineSkips = 0
            player.pause()
            return false
        }
        offlineSkips++
        Log.i(TAG, "offline: skipping ${path.substringAfterLast('/')} (server only)")
        player.seekToNextMediaItem()
        return true
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Why tracks change and positions jump: `adb logcat -s Zenelo` when chasing a skip / rewind.
            Log.i(TAG, "track ${mediaItem?.mediaId?.substringAfterLast('/')} reason=${transitionReason(reason)} fading=${crossfader.fading}")
            onTrackChanged(mediaItem)
            skipUnplayable()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = onPlayWhenReady(playWhenReady)

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error ${error.errorCodeName} on ${player.currentMediaItem?.mediaId?.substringAfterLast('/')}", error)
            if (skipUnplayable()) player.prepare()
        }

        // The notification's shuffle button shows the mode, whoever changed it.
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateCustomLayout()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePosition()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                val kind = if (reason == Player.DISCONTINUITY_REASON_SEEK) "seek" else "seek adjusted"
                Log.i(TAG, "$kind ${oldPosition.positionMs} -> ${newPosition.positionMs} ms (item ${oldPosition.mediaItemIndex} -> ${newPosition.mediaItemIndex})")
            }
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) savePosition()
        }

        // The last track of the queue finished: its file is free, write what waited for it.
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                container.nowPlaying.value = null
                flushPendingWrites()
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(CMD_FAVORITE)
                .add(CMD_SHUFFLE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                ACTION_FAVORITE -> toggleFavorite()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Controllers strip the URI from MediaItems; restore it from requestMetadata. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item
            }.toMutableList(),
        )
    }

    private fun toggleFavorite() {
        val item = player.currentMediaItem ?: return
        val metadata = player.mediaMetadata
        scope.launch {
            container.db.favorites().toggle(
                FavoriteEntity(
                    path = item.mediaId,
                    kind = FavoriteKind.TRACK,
                    title = metadata.title?.toString() ?: File(item.mediaId).nameWithoutExtension,
                    subtitle = metadata.artist?.toString(),
                ),
                container.serverLinks.current.copiesOf(item.mediaId),
            )
        }
    }

    private fun transitionReason(reason: Int) = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist"
        else -> reason.toString()
    }

    companion object {
        private const val TAG = "Zenelo"

        /** A paused queue keeps the service in the foreground this long (see [onUpdateNotification]). */
        private const val PAUSED_FOREGROUND_MS = 30 * 60 * 1000L

        /** Offline skips in a row before giving up (a queue of server tracks only). */
        private const val MAX_OFFLINE_SKIPS = 200
        const val ACTION_FAVORITE = "app.zenelo.FAVORITE"
        const val ACTION_SHUFFLE = "app.zenelo.SHUFFLE"
        val CMD_FAVORITE = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
        val CMD_SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)

        /** ReplayGain 2.0 reference level. */
        const val TARGET_LUFS = -18f
        const val MAX_GAIN_DB = 12f

        /** How often the position is saved while playing (a kill loses at most this much). */
        const val POSITION_SAVE_MS = 10_000L

        const val LISTEN_TICK_MS = 1_000L
    }
}

@OptIn(UnstableApi::class)
private class ZeneloRenderersFactory(
    context: Context,
    private val normalization: NormalizationAudioProcessor,
) : DefaultRenderersFactory(context) {

    init {
        // Picks up the FFmpeg decoder (ALAC, DSD, APE...) once the extension is added.
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // Float output bypasses custom processors for hi-res input, so it stays off while
        // normalization is on. TODO: revisit for a bit-perfect mode on the JM21.
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(normalization))
        .build()
}

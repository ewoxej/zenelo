package app.zenelo.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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
import app.zenelo.player.audio.NormalizationAudioProcessor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the ExoPlayer and the MediaSession. Media3 builds the media notification from the session;
 * heart and shuffle are the two custom actions from the design.
 *
 * TODO crossfade: ExoPlayer has no built-in crossfade. Plan: two ExoPlayer instances behind a
 *  forwarding Player, ramping volumes over `ZeneloSettings.crossfadeMs`; disabled (gapless) at 0.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as ZeneloApp).container }
    private val normalization = NormalizationAudioProcessor()

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var currentIsFavorite = false
    private var favoriteJob: Job? = null
    private var metadataJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this, ZeneloRenderersFactory(this, normalization))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(PlayerListener())
        container.queue.attach(player)

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
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
        val shuffle = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_shuffle))
            .setIconResId(R.drawable.ic_notif_shuffle)
            .setSessionCommand(CMD_SHUFFLE)
            .build()
        session?.setCustomLayout(listOf(favorite, shuffle))
    }

    private fun onTrackChanged(item: MediaItem?) {
        val path = item?.mediaId
        container.nowPlaying.value = path
        metadataJob?.cancel()
        metadataJob = scope.launch {
            container.metadata.flushPendingEmbeds()
            val track = path?.let { container.db.tracks().get(it) ?: container.indexer.indexOne(it) }
            normalization.gainDb = if (track == null) 0f else gainFor(track)
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
            val flow = if (path == null) flowOf(false) else container.db.favorites().observeIsFavorite(path)
            flow.collect {
                currentIsFavorite = it
                updateCustomLayout()
            }
        }
    }

    /** ReplayGain tags first (album gain in album mode), then our own loudness measurement. */
    private suspend fun gainFor(track: TrackEntity): Float {
        val mode = container.settings.settings.first().normalization
        if (mode == NormalizationMode.OFF) return 0f
        val tagged = if (mode == NormalizationMode.ALBUM) track.albumGainDb ?: track.trackGainDb else track.trackGainDb
        if (tagged != null) return tagged.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        val measured = container.db.loudness().get(track.path)
            ?.takeIf { it.fileModified == File(track.path).lastModified() }
            ?: return 0f
        return (TARGET_LUFS - measured.integratedLufs).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onTrackChanged(mediaItem)
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
            )
        }
    }

    companion object {
        const val ACTION_FAVORITE = "app.zenelo.FAVORITE"
        const val ACTION_SHUFFLE = "app.zenelo.SHUFFLE"
        val CMD_FAVORITE = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
        val CMD_SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)

        /** ReplayGain 2.0 reference level. */
        const val TARGET_LUFS = -18f
        const val MAX_GAIN_DB = 12f
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

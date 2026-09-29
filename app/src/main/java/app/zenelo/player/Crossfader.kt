package app.zenelo.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.zenelo.player.audio.NormalizationAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfade between tracks. The session's [main] player stays the only one the queue, the media
 * session and the notification know; a second "tail" player plays the last seconds of the ending
 * track:
 *
 * - a few seconds before the fade, the tail player loads the current track at the fade point;
 * - at the fade point it starts (fading out) while [main] skips to the next track (fading in);
 *   the old track keeps sounding from the tail player, so main's switch is inaudible.
 *
 * Only natural track ends crossfade: pausing, seeking or skipping during a fade ends it at once.
 * Off (gapless, ExoPlayer's own transition) when the setting is 0, with repeat-one, or when the
 * track is too short.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val main: ExoPlayer,
    private val scope: CoroutineScope,
    /** Builds the tail player (no audio focus of its own) around its normalization processor. */
    private val buildTail: (NormalizationAudioProcessor) -> ExoPlayer,
    /** Normalization gain of the track playing on [main] now. */
    private val currentGainDb: () -> Float,
    /** The fade finished or was cut: the old track's file is free again. */
    private val onFadeEnd: () -> Unit,
) {
    @Volatile
    var fadeMs: Int = 0

    /** True from the fade point until the old track has faded out. */
    var fading = false
        private set

    private val tailNormalization = NormalizationAudioProcessor()
    private var tail: ExoPlayer? = null
    private var prepared: Pair<String, Long>? = null
    private var watchJob: Job? = null
    private var fadeJob: Job? = null

    /** Our own skip to the next track, not the user's: don't cut the fade for it. */
    private var switching = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && fading && !switching && main.playbackState != Player.STATE_BUFFERING) cut()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (fading && !switching && reason == Player.DISCONTINUITY_REASON_SEEK) cut()
        }
    }

    fun start() {
        main.addListener(listener)
        watchJob = scope.launch {
            while (isActive) delay(check())
        }
    }

    fun release() {
        watchJob?.cancel()
        main.removeListener(listener)
        cut()
        tail?.release()
        tail = null
    }

    /** Looks at the playing track; returns when to look again (often only near the fade point). */
    private fun check(): Long {
        val fade = fadeMs.toLong()
        if (fade <= 0 || fading) return IDLE_TICK_MS
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return IDLE_TICK_MS
        val item = main.currentMediaItem ?: return IDLE_TICK_MS
        // MP3s without a seek table have no player duration until their end: use the index's (exact).
        val duration = main.duration.takeIf { it != C.TIME_UNSET } ?: item.mediaMetadata.durationMs ?: return IDLE_TICK_MS
        if (duration < fade * 2 + MIN_REST_MS) return IDLE_TICK_MS
        val fadeAt = duration - fade
        val untilFade = fadeAt - main.currentPosition
        if (untilFade > PREPARE_LEAD_MS) return (untilFade - PREPARE_LEAD_MS).coerceAtMost(IDLE_TICK_MS).coerceAtLeast(FINE_TICK_MS)
        val key = item.mediaId to fadeAt
        if (untilFade <= 0) {
            // Only a track that played up to the fade point fades. Past it without the tail ready
            // (a seek into the last seconds, a restored position there): no skip, it plays to its end.
            if (prepared == key) begin(fade)
            return IDLE_TICK_MS
        }
        if (prepared != key) prepareTail(item, fadeAt)
        return if (untilFade < 1_000) FINE_TICK_MS else 250
    }

    private fun prepareTail(item: MediaItem, fadeAt: Long) {
        val player = tail ?: buildTail(tailNormalization).also { tail = it }
        player.volume = 1f
        player.playWhenReady = false
        player.setMediaItem(item, fadeAt)
        player.prepare()
        prepared = item.mediaId to fadeAt
    }

    private fun begin(fade: Long) {
        val player = tail ?: return
        fading = true
        tailNormalization.gainDb = currentGainDb()
        player.play()
        main.volume = 0f
        switching = true
        main.seekToNextMediaItem()
        switching = false
        val started = SystemClock.uptimeMillis()
        fadeJob = scope.launch {
            while (isActive) {
                val f = ((SystemClock.uptimeMillis() - started).toFloat() / fade).coerceIn(0f, 1f)
                // Equal power: the sum stays about as loud as either track alone.
                main.volume = sin(f * PI / 2).toFloat()
                player.volume = cos(f * PI / 2).toFloat()
                if (f >= 1f) break
                delay(FADE_STEP_MS)
            }
            finish()
        }
    }

    /** Ends a fade now (pause, seek, skip): the old tail stops, the new track at full volume. */
    private fun cut() {
        if (!fading) return
        fadeJob?.cancel()
        finish()
    }

    private fun finish() {
        tail?.run {
            stop()
            clearMediaItems()
        }
        prepared = null
        main.volume = 1f
        fading = false
        onFadeEnd()
    }

    private companion object {
        const val IDLE_TICK_MS = 1_000L
        const val FINE_TICK_MS = 20L
        const val FADE_STEP_MS = 30L
        /** The tail player loads (and buffers) this long before the fade. */
        const val PREPARE_LEAD_MS = 4_000L
        /** A track must last at least two fades plus this to crossfade. */
        const val MIN_REST_MS = 2_000L
    }
}

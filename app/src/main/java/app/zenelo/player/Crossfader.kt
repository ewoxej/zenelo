package app.zenelo.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.zenelo.data.settings.FadeMode
import app.zenelo.player.audio.NormalizationAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Crossfade between tracks. The session's [main] player stays the only one the queue, the media
 * session and the notification know; a second "tail" player plays the last seconds of the ending
 * track:
 *
 * - a few seconds before the fade, the tail player loads the current track a little ahead of main,
 *   plays it muted and is lined up with main by position: a little faster or slower (far off, a
 *   seek). Not seeks alone (200–350 ms each on a phone, too uneven), and not the AudioTrack's own
 *   speed: that took another audio path, and on Bluetooth its position was ~250 ms off what was
 *   heard while ExoPlayer's matched main's;
 * - at the fade point the two only swap volumes (tail up, main muted) while [main] skips to the
 *   next track (fading in). Both played the same moment, so nothing repeats: starting the tail at
 *   the fade point instead repeated what main had already sent out — a quarter second and more on
 *   Bluetooth, "the last word twice".
 *
 * The fades are ramps in the players' normalization processors, counted in their audio — the tail's
 * from the fade point's place in the track, main's from its seek — not volume steps from the main
 * thread, which a busy UI held up for seconds: the old track went on at full volume, then stopped.
 *
 * [FadeMode.FADE_OUT] instead: no tail, no overlap — [main]'s own volume goes down over the track's
 * last seconds (set from its position each tick, so a late tick can't hold the volume up; the
 * player's volume acts on what is heard now, not on audio buffered ahead), then the next track
 * starts as usual at full volume.
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
    /** Sets [main]'s normalization gain (for the next track, just before main switches to it). */
    private val setMainGainDb: (Float) -> Unit,
    /** [main]'s processor: the next track's fade-in is counted in its audio. */
    private val mainNormalization: NormalizationAudioProcessor,
    /** The gain for a track (by media id) if already known; asked for the next track at the fade. */
    private val gainOf: (String) -> Float?,
    /** The tail is being prepared: work out the next track's gain meanwhile (see [gainOf]). */
    private val onUpcoming: (String) -> Unit,
    /** The fade finished or was cut: the old track's file is free again. */
    private val onFadeEnd: () -> Unit,
) {
    @Volatile
    var fadeMs: Int = 0

    @Volatile
    var mode: FadeMode = FadeMode.CROSSFADE

    /** Fade-out mode: the track (and fade point) seen playing before its fade point, so it may fade. */
    private var outArmed: Pair<String, Long>? = null

    /** Fade-out mode: [main]'s volume is turned down. */
    private var outFading = false

    /** True from the fade point until the old track has faded out. */
    var fading = false
        private set

    private val tailNormalization = NormalizationAudioProcessor()
    private var tail: ExoPlayer? = null
    private var prepared: Pair<String, Long>? = null

    /** Where the tail was loaded (or last moved) to, in the track. */
    private var tailFrom = 0L

    /** The tail plays, muted, and is being lined up with main. */
    private var syncing = false

    /** Tail positions aren't trusted until then (uptime): a player just started reports a guess. */
    private var settleAt = 0L

    /** How far main gets while the tail seeks: learnt from each correction. */
    private var seekLeadMs = SEEK_LEAD_MS
    private var lastJump = 0L
    private var lastSyncLog = 0L

    /** A fade given up (tail not loaded in time): that track plays to its end. */
    private var skipped: Pair<String, Long>? = null
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
            // Main moved: the lined-up tail is wrong now (loaded again if still near the end).
            if (!fading && reason == Player.DISCONTINUITY_REASON_SEEK) dropTail()
            // Fade-out mode: a seek (back, or into the last seconds) plays at full volume; only a
            // track that plays up to its fade point fades.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                outArmed = null
                restoreOut()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The next track starts at full volume.
            if (!fading) restoreOut()
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
        restoreOut()
        tail?.release()
        tail = null
    }

    /** Looks at the playing track; returns when to look again (often only near the fade point). */
    private fun check(): Long {
        val fade = fadeMs.toLong()
        if (fade <= 0 || mode != FadeMode.FADE_OUT) restoreOut()
        if (fade <= 0 || fading) return IDLE_TICK_MS
        if (mode == FadeMode.FADE_OUT) return fadeOutStep(fade)
        if (!main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) {
            dropTail()
            return IDLE_TICK_MS
        }
        if (!main.isPlaying) {
            // Paused (or loading): the muted tail waits too, and is lined up again after.
            if (syncing) tail?.pause()
            return IDLE_TICK_MS
        }
        val item = main.currentMediaItem ?: return IDLE_TICK_MS
        // MP3s without a seek table have no player duration until their end: use the index's (exact).
        val duration = main.duration.takeIf { it != C.TIME_UNSET } ?: item.mediaMetadata.durationMs ?: return IDLE_TICK_MS
        if (duration < fade * 2 + MIN_REST_MS) return IDLE_TICK_MS
        val fadeAt = duration - fade
        val position = main.currentPosition
        val untilFade = fadeAt - position
        if (untilFade > PREPARE_LEAD_MS) {
            // Far from the end (again, e.g. repeat or a seek back): a skipped fade may be tried again.
            skipped = null
            return (untilFade - PREPARE_LEAD_MS).coerceAtMost(IDLE_TICK_MS).coerceAtLeast(FINE_TICK_MS)
        }
        val key = item.mediaId to fadeAt
        if (untilFade <= 0) {
            // Only a track that played up to the fade point fades, with the tail lined up. Else (a
            // seek into the last seconds, a slow stream) it plays to its end: no fade this time.
            if (prepared == key) {
                val player = tail
                val off = player?.let { it.currentPosition - main.currentPosition }
                if (syncing && player != null && player.isPlaying && off != null && kotlin.math.abs(off) <= MAX_SWAP_OFF_MS) begin(fade, player) else {
                    // Swapping now would repeat (or skip) that much of the track: no fade instead.
                    Log.i(TAG, "crossfade skipped: the end of ${item.mediaId.substringAfterLast('/')} wasn't lined up in time (${off ?: "?"} ms off)")
                    dropTail()
                    skipped = key
                }
            }
            return IDLE_TICK_MS
        }
        if (skipped == key) return IDLE_TICK_MS
        val player = tail
        if (prepared != key || player == null) {
            val from = maxOf(fadeAt - SYNC_MS, position + PREPARE_AHEAD_MS)
            if (fadeAt - from < MIN_SYNC_MS) {
                skipped = key
                return IDLE_TICK_MS
            }
            prepareTail(item, from, fadeAt)
            return FINE_TICK_MS
        }
        if (!syncing) {
            // Started (muted) as main gets to where it was loaded.
            if (player.playbackState == Player.STATE_READY && position >= tailFrom - START_LEAD_MS) {
                player.play()
                syncing = true
                settleAt = android.os.SystemClock.uptimeMillis() + SETTLE_MS
            }
            return FINE_TICK_MS
        }
        if (!player.isPlaying) {
            if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                player.play()
                settleAt = android.os.SystemClock.uptimeMillis() + SETTLE_MS
            }
            return FINE_TICK_MS
        }
        if (android.os.SystemClock.uptimeMillis() >= settleAt) lineUp(player, fadeAt)
        return FINE_TICK_MS
    }

    /**
     * Lines the muted tail up with main (both positions are what is heard now): far off, a seek (it
     * learns how far main gets meanwhile); close, a little faster or slower until they meet — seeks
     * alone took 200–330 ms on a phone, too unevenly to land within a few ms.
     */
    private fun lineUp(player: ExoPlayer, fadeAt: Long) {
        val behind = main.currentPosition - player.currentPosition
        // The last jump's leftover teaches how far main gets while the tail seeks.
        if (lastJump != 0L) {
            // Half way: one odd reading (a slow start) used to throw it off.
            seekLeadMs = (seekLeadMs + behind / 2).coerceIn(0, MAX_SEEK_LEAD_MS)
            lastJump = 0L
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSyncLog >= SYNC_LOG_MS) {
            lastSyncLog = now
            Log.i(TAG, "crossfade: lining up, tail $behind ms behind main at speed ${"%.3f".format(player.playbackParameters.speed)}")
        }
        if (kotlin.math.abs(behind) <= SEEK_ABOVE_MS) {
            // Muted, so a changed speed isn't heard: 100 ms behind = 10 % faster.
            val speed = if (kotlin.math.abs(behind) <= IN_SYNC_MS) 1f
            else (1f + behind / 1000f * NUDGE).coerceIn(1f - MAX_NUDGE, 1f + MAX_NUDGE)
            if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
            return
        }
        val to = player.currentPosition + behind + seekLeadMs
        if (to >= fadeAt - LAST_MOVE_MS) return
        Log.i(TAG, "crossfade: tail ${behind} ms behind main, moved (lead ${seekLeadMs} ms)")
        player.setPlaybackSpeed(1f)
        tailFrom = to
        tailNormalization.fadeOut(fadeMs.toLong(), afterMs = fadeAt - to)
        player.seekTo(to)
        lastJump = behind
        settleAt = android.os.SystemClock.uptimeMillis() + SETTLE_MS
    }

    /** Fade-out mode: [main]'s volume for where it is; returns when to look again. */
    private fun fadeOutStep(fade: Long): Long {
        if (!main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE) return IDLE_TICK_MS
        val item = main.currentMediaItem ?: return IDLE_TICK_MS
        val duration = main.duration.takeIf { it != C.TIME_UNSET } ?: item.mediaMetadata.durationMs ?: return IDLE_TICK_MS
        if (duration < fade * 2 + MIN_REST_MS) return IDLE_TICK_MS
        val fadeAt = duration - fade
        val position = main.currentPosition
        val key = item.mediaId to fadeAt
        if (position < fadeAt) {
            outArmed = key
            restoreOut()
            return (fadeAt - position).coerceAtMost(IDLE_TICK_MS).coerceAtLeast(OUT_TICK_MS)
        }
        if (outArmed != key) return IDLE_TICK_MS
        if (!outFading) Log.i(TAG, "fade out: ${item.mediaId.substringAfterLast('/')} over its last $fade ms")
        outFading = true
        // Squared: half way it's a quarter of the amplitude (−12 dB), an audible fade, not a late drop.
        val left = ((duration - position).toFloat() / fade).coerceIn(0f, 1f)
        main.volume = left * left
        return OUT_TICK_MS
    }

    private fun restoreOut() {
        if (!outFading) return
        outFading = false
        main.volume = 1f
    }

    private fun nextId(): String? =
        main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { main.getMediaItemAt(it).mediaId }

    private fun prepareTail(item: MediaItem, from: Long, fadeAt: Long) {
        val player = tail ?: buildTail(tailNormalization).also {
            tail = it
            // Its position is a guess for a moment after it (re)starts: after a seek's loading too.
            it.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) settleAt = android.os.SystemClock.uptimeMillis() + SETTLE_MS
                }
            })
        }
        // Before prepare: the tail processes (and buffers) its first second while waiting, with the
        // gain set now — the last fade's track's gain would play at full volume, then jump.
        tailNormalization.gainDb = currentGainDb()
        // Full volume up to the fade point's place in the track, then the fade-out.
        tailNormalization.fadeOut(fadeMs.toLong(), afterMs = fadeAt - from)
        nextId()?.let(onUpcoming)
        // Muted until the fade point: it only follows main till then.
        player.volume = 0f
        player.playWhenReady = false
        player.setPlaybackSpeed(1f)
        player.setMediaItem(item, from)
        player.prepare()
        prepared = item.mediaId to fadeAt
        tailFrom = from
        syncing = false
        lastJump = 0L
    }

    /** No fade coming (main moved, a repeat, the end of the queue): the tail stops. */
    private fun dropTail() {
        if (fading || prepared == null) return
        tail?.run {
            stop()
            clearMediaItems()
        }
        prepared = null
        syncing = false
        tailNormalization.clearFade()
    }

    private fun begin(fade: Long, player: ExoPlayer) {
        fading = true
        syncing = false
        tailNormalization.gainDb = currentGainDb()
        player.setPlaybackSpeed(1f)
        Log.i(TAG, "crossfade: tail ${player.currentPosition - main.currentPosition} ms off main")
        fadeJob = scope.launch {
            // Same moment of the same track on both: swap them at once. (A ramp between the two, a
            // few ms apart, cancelled frequencies out — a dip and phasing, worse than a quick swap.)
            // Main first: the two a moment apart is a short dip, not both at once (louder, a click).
            main.volume = 0f
            player.volume = 1f
            // The next track's gain before main switches: main processes its first second right after
            // the seek, before the service's own (async) gain update would land.
            val next = nextId()
            val nextGain = next?.let(gainOf)
            Log.i(TAG, "crossfade: out at ${"%.1f".format(currentGainDb())} dB, in ${next?.substringAfterLast('/')} at ${nextGain?.let { "%.1f".format(it) } ?: "?"} dB")
            nextGain?.let(setMainGainDb)
            // Counted from the seek's flush: the next track's first frame.
            mainNormalization.fadeIn(fade)
            switching = true
            main.seekToNextMediaItem()
            switching = false
            // Applied on the playback thread after the seek: main's old track (still buffered) stays
            // muted, the next one starts from the ramp's silence.
            main.volume = 1f
            // The tail fades out by itself; then it stops.
            delay(fade + TAIL_MARGIN_MS)
            Log.i(TAG, "crossfade done")
            finish()
        }
    }

    /** Ends a fade now (pause, seek, skip): the old tail stops, the new track at full volume. */
    private fun cut() {
        if (!fading) return
        Log.i(TAG, "crossfade cut short")
        fadeJob?.cancel()
        finish()
    }

    private fun finish() {
        tail?.run {
            stop()
            clearMediaItems()
        }
        prepared = null
        syncing = false
        tailNormalization.clearFade()
        // Cut short: the new track at full volume at once.
        mainNormalization.clearFade()
        main.volume = 1f
        fading = false
        onFadeEnd()
    }

    private companion object {
        const val TAG = "Zenelo"
        const val IDLE_TICK_MS = 1_000L
        const val FINE_TICK_MS = 20L
        /** The tail stops this long after its fade-out (its audio runs a little behind its processing). */
        const val TAIL_MARGIN_MS = 500L
        /** Fade-out mode: volume steps this often. */
        const val OUT_TICK_MS = 30L
        /** The tail player loads (and buffers) this long before the fade. */
        const val PREPARE_LEAD_MS = 7_000L
        /** It plays muted from this long before the fade, lined up with main meanwhile. */
        const val SYNC_MS = 5_000L
        /** Less time than this to line up: no fade. */
        const val MIN_SYNC_MS = 1_500L
        /** Loaded at least this far ahead of main (it needs the time to load). */
        const val PREPARE_AHEAD_MS = 800L
        /** Started this much before main gets there (players take a moment to start). */
        const val START_LEAD_MS = 100L
        /** A started / moved player's position is trusted after this. */
        const val SETTLE_MS = 300L
        /** Close enough: normal speed. */
        const val IN_SYNC_MS = 8L
        /** Further off than this, a seek; nearer, the speed. */
        const val SEEK_ABOVE_MS = 700L
        const val SYNC_LOG_MS = 500L
        /** Speed change per second off, and its limit. */
        const val NUDGE = 1f
        const val MAX_NUDGE = 0.15f
        /** The tail isn't moved this close to the fade point: it must be playing at the swap. */
        const val LAST_MOVE_MS = 500L
        /** First guess at how far main gets while the tail seeks. */
        const val SEEK_LEAD_MS = 100L
        const val MAX_SEEK_LEAD_MS = 1_000L
        /** Further apart than this at the fade point: no fade (it would repeat or skip a bit). */
        const val MAX_SWAP_OFF_MS = 60L
        /** A track must last at least two fades plus this to crossfade. */
        const val MIN_REST_MS = 2_000L
    }
}

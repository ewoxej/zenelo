package app.zenelo.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.zenelo.library.AudioFile
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ByteArray? = null,
    /** Cover file set by the service for tracks without embedded art (folder image or download). */
    val artworkFile: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/**
 * UI-side handle to [PlaybackService]. Connected while the activity is started.
 *
 * Transport goes through the MediaController; queue edits go to [queue] directly (same process),
 * which keeps the player's playlist small. Playback position lives in its own [position] flow: it
 * ticks four times a second, and folding it into [state] would recompose every screen that shows
 * the current track.
 */
class PlayerController(
    private val context: Context,
    val queue: PlayQueue,
    private val coverOverride: StateFlow<Pair<String, String>?>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var coverJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).buildAsync()
        future = pending
        scope.launch {
            val c = runCatching { pending.await() }.getOrNull() ?: return@launch
            controller = c
            c.addListener(listener)
            refresh()
            coverJob?.cancel()
            coverJob = scope.launch { coverOverride.collect { refresh() } }
            startTicker()
        }
    }

    fun release() {
        ticker?.cancel()
        coverJob?.cancel()
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        _state.update { it.copy(connected = false) }
    }

    /**
     * Plays [files] from [startIndex]. [shuffle]: true shuffles (random start), false plays in
     * order, null keeps the user's current shuffle mode.
     */
    fun playFiles(files: List<AudioFile>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (files.isEmpty()) return
        val start = if (shuffle == true) files.indices.random() else startIndex
        queue.play(files, start, shuffle)
    }

    /** Returns the track's position in the queue as shown (current track = 1). */
    fun addToQueue(file: AudioFile): Int = queue.add(file)

    fun playNext(file: AudioFile) = queue.playNext(file)

    /** [offset] from the current track, as in the queue list. */
    fun removeFromQueue(offset: Int) = queue.remove(offset)

    fun skipTo(offset: Int) = queue.skipTo(offset)

    fun togglePlay() = controller?.run { if (isPlaying) pause() else play() }

    fun next() = controller?.seekToNext()

    fun previous() = controller?.seekToPrevious()

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }

    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.takeIf { it.isPlaying }?.let { _position.value = it.currentPosition }
                delay(250)
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        val item = c.currentMediaItem
        _position.value = c.currentPosition
        _state.value = PlayerUiState(
            connected = true,
            mediaId = item?.mediaId,
            title = (metadata.title ?: metadata.displayTitle)?.toString(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            artwork = metadata.artworkData,
            artworkFile = coverOverride.value?.takeIf { it.first == item?.mediaId }?.second
                ?: metadata.artworkUri?.takeIf { it.scheme == "file" }?.path,
            isPlaying = c.isPlaying,
            durationMs = c.duration.coerceAtLeast(0L),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }
}

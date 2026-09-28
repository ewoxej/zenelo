package app.zenelo.player

import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import app.zenelo.data.db.TrackDao
import app.zenelo.data.db.TrackEntity
import app.zenelo.library.AudioFile
import app.zenelo.library.MetadataFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the queue UI needs: the current track and everything after it, without copying 7000 entries. */
data class QueueSnapshot(
    private val paths: List<String>,
    private val order: List<Int>,
    /** Index in [order] of the current track. */
    private val current: Int,
    private val repeatAll: Boolean,
) {
    /** Current track + upcoming ones (the whole queue again under repeat-all). */
    val size: Int get() = if (order.isEmpty()) 0 else if (repeatAll) order.size else order.size - current

    /** Path at [offset] from the current track (0 = current). */
    fun pathAt(offset: Int): String = paths[order[(current + offset) % order.size]]

    companion object {
        val EMPTY = QueueSnapshot(emptyList(), emptyList(), 0, false)
    }
}

/**
 * The play queue. ExoPlayer only ever holds a small window of it (a few previous tracks, the
 * current one and the next ~30), extended as playback moves on. With the whole folder in the
 * player, a 7000-track queue made every timeline update re-send 7000 items through the media
 * session, stalling the main thread for seconds.
 *
 * Shuffle and repeat-all are implemented here, over the full queue:
 * - shuffle: [order] is a permutation (current track first when shuffle turns on). The player's
 *   shuffle flag is kept only as the user-visible state; its shuffle order is the identity, so it
 *   plays the window as laid out.
 * - repeat-all: positions are "virtual" (they keep counting past the end and wrap modulo the queue
 *   size), so the window just continues from the start; the player never reaches its own end.
 */
@OptIn(UnstableApi::class)
class PlayQueue(private val tracks: TrackDao, private val fetcher: MetadataFetcher) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var player: ExoPlayer? = null

    private var paths: List<String> = emptyList()
    private var order: List<Int> = emptyList()

    /** Virtual position of the current track (see class doc). */
    private var current = 0L

    /** Virtual position of every item in the player, index-aligned with the player's playlist. */
    private val window = ArrayList<Long>()

    /** Shuffle state we last applied; flag changes from anywhere else trigger a reorder. */
    private var shuffled = false

    private val infoCache = LruCache<String, TrackEntity>(2000)

    private val _state = MutableStateFlow(QueueSnapshot.EMPTY)
    val state: StateFlow<QueueSnapshot> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scope.launch { mutex.withLock { onTransition() } }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled != shuffled) scope.launch { mutex.withLock { reorder(shuffleModeEnabled) } }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            scope.launch { mutex.withLock { rebuildAroundCurrent() } }
        }
    }

    fun attach(exoPlayer: ExoPlayer) {
        player = exoPlayer
        exoPlayer.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
        exoPlayer.addListener(listener)
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
    }

    /** Tags for queue rows; cached, loaded from the index on demand. */
    suspend fun info(path: String): TrackEntity? = infoCache.get(path) ?: tracks.get(path)?.also { infoCache.put(path, it) }

    fun cachedInfo(path: String): TrackEntity? = infoCache.get(path)

    /**
     * Replaces the queue. [shuffle]: true / false to set it, null to keep the current mode
     * (the start track plays first either way).
     */
    fun play(files: List<AudioFile>, startIndex: Int, shuffle: Boolean?) = launchLocked {
        val p = player ?: return@launchLocked
        if (files.isEmpty()) return@launchLocked
        val start = startIndex.coerceIn(files.indices)
        val shuffleOn = shuffle ?: p.shuffleModeEnabled
        paths = files.map { it.path }
        order = if (shuffleOn) listOf(start) + (paths.indices - start).shuffled() else paths.indices.toList()
        current = order.indexOf(start).toLong()

        val positions = windowAround(current)
        val items = buildItems(positions)
        window.clear()
        window.addAll(positions)
        shuffled = shuffleOn
        p.shuffleModeEnabled = shuffleOn
        p.setMediaItems(items, positions.indexOf(current), 0L)
        p.prepare()
        p.play()
        publish()
    }

    /** Appends to the end of the queue. Returns its position as shown in the queue (current = 1). */
    fun add(file: AudioFile): Int {
        if (player == null) return 0
        val position = _state.value.size + 1
        launchLocked {
            if (order.isEmpty()) return@launchLocked playNow(file)
            normalizeCurrent()
            paths = paths + file.path
            order = order + paths.lastIndex
            rebuildAroundCurrent()
        }
        return position
    }

    /** Inserts right after the current track. */
    fun playNext(file: AudioFile) = launchLocked {
        if (order.isEmpty()) return@launchLocked playNow(file)
        normalizeCurrent()
        paths = paths + file.path
        order = order.toMutableList().apply { add(current.toInt() + 1, paths.lastIndex) }
        rebuildAroundCurrent()
    }

    /** Removes the entry at [offset] from the current track (0 = current; ignored). */
    fun remove(offset: Int) = launchLocked {
        if (offset <= 0 || order.isEmpty()) return@launchLocked
        normalizeCurrent()
        val at = ((current + offset) % order.size).toInt()
        order = order.toMutableList().apply { removeAt(at) }
        if (at < current) current--
        rebuildAroundCurrent()
    }

    /** Jumps to the entry at [offset] from the current track. */
    fun skipTo(offset: Int) = launchLocked {
        val p = player ?: return@launchLocked
        if (offset == 0 || order.isEmpty()) return@launchLocked
        current += offset
        val positions = windowAround(current)
        val items = buildItems(positions)
        window.clear()
        window.addAll(positions)
        p.setMediaItems(items, positions.indexOf(current), 0L)
        p.play()
        publish()
    }

    private suspend fun playNow(file: AudioFile) {
        val p = player ?: return
        paths = listOf(file.path)
        order = listOf(0)
        current = 0
        window.clear()
        window.add(0)
        p.setMediaItems(buildItems(listOf(0L)), 0, 0L)
        p.prepare()
        p.play()
        publish()
    }

    /** Track changed (end of track, next/previous, notification): move the window along. */
    private suspend fun onTransition() {
        val p = player ?: return
        if (window.isEmpty() || order.isEmpty()) return
        current = window.getOrNull(p.currentMediaItemIndex) ?: return
        publish()

        val index = p.currentMediaItemIndex
        if (index > HISTORY) {
            p.removeMediaItems(0, index - HISTORY)
            repeat(index - HISTORY) { window.removeAt(0) }
        }

        val ahead = window.size - 1 - p.currentMediaItemIndex
        if (ahead >= AHEAD_MIN) return
        val last = window.last()
        val positions = (last + 1 until minOf(last + 1 + (AHEAD - ahead), end())).toList()
        if (positions.isEmpty()) return
        val items = buildItems(positions)
        if (window.lastOrNull() != last) return
        p.addMediaItems(items)
        window.addAll(positions)
    }

    private suspend fun reorder(shuffleOn: Boolean) {
        if (order.isEmpty()) {
            shuffled = shuffleOn
            return
        }
        normalizeCurrent()
        val playing = order[current.toInt()]
        // A fresh order every time shuffle turns on, with the playing track first.
        order = if (shuffleOn) listOf(playing) + order.filter { it != playing }.shuffled() else order.sorted()
        current = order.indexOf(playing).toLong()
        shuffled = shuffleOn
        rebuildAroundCurrent()
    }

    /**
     * Keeps the playing item untouched (replacing it would interrupt playback) and lays out
     * history and upcoming tracks around it from the current [order].
     */
    private suspend fun rebuildAroundCurrent() {
        val p = player ?: return
        if (order.isEmpty() || p.mediaItemCount == 0) return
        if (!repeatAll()) normalizeCurrent()
        val before = (maxOf(0L, current - HISTORY) until current).toList()
        val after = (current + 1 until minOf(current + 1 + AHEAD, end())).toList()
        val beforeItems = buildItems(before)
        val afterItems = buildItems(after)

        val index = p.currentMediaItemIndex
        if (index + 1 < p.mediaItemCount) p.removeMediaItems(index + 1, p.mediaItemCount)
        if (index > 0) p.removeMediaItems(0, index)
        p.addMediaItems(afterItems)
        p.addMediaItems(0, beforeItems)
        window.clear()
        window.addAll(before)
        window.add(current)
        window.addAll(after)
        publish()
    }

    private fun windowAround(position: Long): List<Long> =
        (maxOf(0L, position - HISTORY) until minOf(position + 1 + AHEAD, end())).toList()

    /** Exclusive end of virtual positions: unbounded under repeat-all. */
    private fun end(): Long = if (repeatAll()) Long.MAX_VALUE / 2 else order.size.toLong()

    private fun repeatAll() = player?.repeatMode == Player.REPEAT_MODE_ALL

    private fun normalizeCurrent() {
        if (order.isNotEmpty()) current %= order.size
    }

    private fun pathAt(position: Long): String = paths[order[(position % order.size).toInt()]]

    /** MediaItems for window positions, with tags and local covers (no network). */
    private suspend fun buildItems(positions: List<Long>): List<MediaItem> {
        if (positions.isEmpty()) return emptyList()
        val wanted = positions.map(::pathAt)
        val missing = wanted.distinct().filter { infoCache.get(it) == null }
        if (missing.isNotEmpty()) tracks.getMany(missing).forEach { infoCache.put(it.path, it) }
        val infos = wanted.mapNotNull { infoCache.get(it) }
        val covers = fetcher.localCovers(infos)
        return wanted.map { path ->
            val name = path.substringAfterLast('/')
            AudioFile(path, name, name.substringAfterLast('.', "").lowercase(), 0L)
                .toMediaItem(infoCache.get(path), covers[path])
        }
    }

    private fun publish() {
        _state.value = if (order.isEmpty()) {
            QueueSnapshot.EMPTY
        } else {
            QueueSnapshot(paths, order, (current % order.size).toInt(), repeatAll())
        }
    }

    private fun launchLocked(block: suspend () -> Unit) {
        scope.launch { mutex.withLock { block() } }
    }

    private companion object {
        /** Previous tracks kept for the "previous" button. */
        const val HISTORY = 10

        /** Upcoming tracks in the player; topped up when fewer than [AHEAD_MIN] remain. */
        const val AHEAD = 30
        const val AHEAD_MIN = 10
    }
}

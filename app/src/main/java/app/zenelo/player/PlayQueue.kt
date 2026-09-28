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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the queue UI needs, without copying 7000 entries. Entries are addressed by offset from the
 * current track (negative = already played) and identified by a stable id for edits.
 */
data class QueueSnapshot(
    private val paths: List<String>,
    private val order: List<Int>,
    /** Index in [order] of the current track = number of already played entries. */
    val history: Int,
    private val repeatAll: Boolean,
    /** Bumped when cached tags change, so rows reload them. */
    val revision: Int = 0,
) {
    /** Current track + upcoming ones (the whole queue again, wrapping, under repeat-all). */
    val size: Int get() = if (order.isEmpty()) 0 else if (repeatAll) order.size else order.size - history

    /** Everything in queue order: played, current, upcoming (no wrapping). */
    val total: Int get() = order.size

    val currentId: Int? get() = order.getOrNull(history)

    fun idAt(offset: Int): Int = order[Math.floorMod(history + offset, order.size)]

    fun pathAt(offset: Int): String = paths[idAt(offset)]

    fun pathOf(id: Int): String = paths[id]

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
 *
 * The queue (with its shuffle order, the current track, shuffle and repeat) is saved to [stateFile]
 * after every change, the playback position next to it; [restore] brings it back, paused, when
 * the service starts.
 */
@OptIn(UnstableApi::class)
class PlayQueue(private val tracks: TrackDao, private val fetcher: MetadataFetcher, private val stateFile: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val positionFile = File(stateFile.path + ".position")
    private var saveJob: Job? = null
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

    private var revision = 0

    /** Reloads a file's tags after an edit; queue rows and Now Playing pick them up via [state]. */
    fun invalidate(path: String) = launchLocked {
        infoCache.remove(path)
        tracks.get(path)?.let { infoCache.put(path, it) }
        revision++
        publish()
    }

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

    /**
     * Brings back the saved queue, paused at the saved position (service start). Does nothing if
     * the player already has items or nothing was saved.
     */
    fun restore() = launchLocked {
        val p = player ?: return@launchLocked
        if (p.mediaItemCount > 0 || order.isNotEmpty()) return@launchLocked
        val saved = withContext(Dispatchers.IO) { SavedQueue.read(stateFile, positionFile) } ?: return@launchLocked
        paths = saved.paths
        order = saved.order
        current = saved.current.toLong()
        // Flags first: the window depends on repeat, and matching [shuffled] keeps the order as saved.
        shuffled = saved.shuffle
        p.shuffleModeEnabled = saved.shuffle
        p.repeatMode = saved.repeatMode
        val positions = windowAround(current)
        val items = buildItems(positions)
        window.clear()
        window.addAll(positions)
        p.setMediaItems(items, positions.indexOf(current), saved.positionMs)
        p.prepare()
        publish()
    }

    /** Remembers where playback is in the current track; [now] writes on the calling thread (service shutdown). */
    fun savePosition(path: String?, positionMs: Long, now: Boolean = false) {
        if (path == null) return
        val write = { runCatching { SavedQueue.writePosition(positionFile, path, positionMs) } }
        if (now) write() else io.launch { write() }
    }

    /** Appends to the end of the queue. Returns the first one's position as shown in the queue (current = 1). */
    fun add(files: List<AudioFile>): Int {
        if (player == null || files.isEmpty()) return 0
        val position = _state.value.size + 1
        launchLocked {
            if (order.isEmpty()) return@launchLocked playNow(files)
            normalizeCurrent()
            val first = paths.size
            paths = paths + files.map { it.path }
            order = order + (first until paths.size)
            rebuildAroundCurrent()
        }
        return position
    }

    /** Inserts right after the current track, in the given order. */
    fun playNext(files: List<AudioFile>) = launchLocked {
        if (files.isEmpty()) return@launchLocked
        if (order.isEmpty()) return@launchLocked playNow(files)
        normalizeCurrent()
        val first = paths.size
        paths = paths + files.map { it.path }
        order = order.toMutableList().apply { addAll(current.toInt() + 1, (first until paths.size).toList()) }
        rebuildAroundCurrent()
    }

    /** Removes the given entries; the current track always stays. */
    fun remove(ids: Set<Int>) = launchLocked {
        val keep = currentId() ?: return@launchLocked
        if (ids.none { it != keep }) return@launchLocked
        normalizeCurrent()
        order = order.filter { it == keep || it !in ids }
        current = order.indexOf(keep).toLong()
        rebuildAroundCurrent()
    }

    /** Stops playback and empties the queue (nothing to restore next launch either). */
    fun stop() = launchLocked {
        val p = player ?: return@launchLocked
        p.stop()
        p.clearMediaItems()
        paths = emptyList()
        order = emptyList()
        current = 0
        window.clear()
        saveJob?.cancel()
        publish()
        withContext(Dispatchers.IO) {
            stateFile.delete()
            positionFile.delete()
        }
    }

    /** Leaves only the current track. */
    fun clear() = launchLocked {
        val keep = currentId() ?: return@launchLocked
        order = listOf(keep)
        current = 0
        rebuildAroundCurrent()
    }

    /** Moves [id] in front of [beforeId] (null = to the end). The current track doesn't move. */
    fun move(id: Int, beforeId: Int?) = launchLocked {
        val keep = currentId() ?: return@launchLocked
        if (id == keep || id == beforeId || id !in order) return@launchLocked
        normalizeCurrent()
        val next = order.toMutableList().apply { remove(id) }
        val at = beforeId?.let { next.indexOf(it) }?.takeIf { it >= 0 } ?: next.size
        next.add(at, id)
        order = next
        current = order.indexOf(keep).toLong()
        rebuildAroundCurrent()
    }

    /** Moves [id] right after the current track. */
    fun playNext(id: Int) = launchLocked {
        val keep = currentId() ?: return@launchLocked
        if (id == keep || id !in order) return@launchLocked
        normalizeCurrent()
        val next = order.toMutableList().apply { remove(id) }
        next.add(next.indexOf(keep) + 1, id)
        order = next
        current = order.indexOf(keep).toLong()
        rebuildAroundCurrent()
    }

    /** Jumps to the entry [id] (played or upcoming). */
    fun skipTo(id: Int) = launchLocked {
        val p = player ?: return@launchLocked
        val position = order.indexOf(id).takeIf { it >= 0 } ?: return@launchLocked
        if (id == currentId()) return@launchLocked
        current = position.toLong()
        val positions = windowAround(current)
        val items = buildItems(positions)
        window.clear()
        window.addAll(positions)
        p.setMediaItems(items, positions.indexOf(current), 0L)
        p.play()
        publish()
    }

    private fun currentId(): Int? = order.getOrNull(Math.floorMod(current, order.size.coerceAtLeast(1).toLong()).toInt())

    private suspend fun playNow(files: List<AudioFile>) {
        val p = player ?: return
        paths = files.map { it.path }
        order = paths.indices.toList()
        current = 0
        val positions = windowAround(0)
        window.clear()
        window.addAll(positions)
        p.setMediaItems(buildItems(positions), 0, 0L)
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
            QueueSnapshot(paths, order, (current % order.size).toInt(), repeatAll(), revision)
        }
        scheduleSave()
    }

    /** Writes the queue shortly after it changes (a burst of edits is written once). */
    private fun scheduleSave() {
        // Never save an empty queue: that's the state before [restore] and would wipe the saved one.
        if (order.isEmpty()) return
        val snapshot = SavedQueue(paths, order, (current % order.size).toInt(), shuffled, player?.repeatMode ?: Player.REPEAT_MODE_OFF)
        saveJob?.cancel()
        saveJob = io.launch {
            delay(500)
            runCatching { snapshot.write(stateFile) }
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

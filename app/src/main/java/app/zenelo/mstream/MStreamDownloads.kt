package app.zenelo.mstream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.zenelo.data.db.DownloadEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.LibraryIndexer
import app.zenelo.player.PlayQueue
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Download" for server tracks (a queue in the `downloads` table, worked through by
 * `DownloadWorker`), and the queue cache: the next queued server tracks fetched ahead of playing.
 */
class MStreamDownloads(
    private val context: Context,
    private val db: ZeneloDatabase,
    private val settings: SettingsRepository,
    private val client: MStreamClient,
    private val files: MStreamFiles,
    private val indexer: LibraryIndexer,
    private val queue: PlayQueue,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<String?>(null)

    /** The track being downloaded now, if any. */
    val current: StateFlow<String?> = _current.asStateFlow()

    /**
     * A run of downloads for the popup: the track being fetched ([title], [bytes] of [size], -1 =
     * unknown), [done] of [count] finished; [finished] once the queue is empty (then [dismiss]).
     */
    data class Progress(
        val title: String?,
        val bytes: Long = 0,
        val size: Long = -1,
        val done: Int = 0,
        val count: Int = 1,
        val failed: Int = 0,
        val finished: Boolean = false,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** The popup went away after showing a finished run: the next run counts from zero. */
    fun dismiss() {
        if (_progress.value?.finished == true) _progress.value = null
    }

    val pending = db.downloads().observePending(MAX_ATTEMPTS)
    val failed = db.downloads().observeFailed(MAX_ATTEMPTS)

    /** Queues server tracks for download (local ones and those already downloaded are skipped). */
    suspend fun enqueue(paths: List<String>): Int {
        val wanted = paths.distinct().filter { MStreamPaths.isRemote(it) && !files.isDownloaded(it) && files.downloaded(it) == null }
        db.downloads().add(wanted.map { DownloadEntity(it) })
        return wanted.size
    }

    /** Works through the download queue (from the worker); returns false if it stopped on a network error. */
    suspend fun drain(): Boolean {
        // A finished run still on screen: this one starts over.
        if (_progress.value?.finished == true) _progress.value = null
        val failedBefore = db.downloads().observeFailed(MAX_ATTEMPTS).first()
        while (true) {
            val account = settings.settings.first().mstream ?: return true
            val next = db.downloads().next(MAX_ATTEMPTS)
            if (next == null) {
                _progress.value?.let { p ->
                    val failed = db.downloads().observeFailed(MAX_ATTEMPTS).first() - failedBefore
                    _progress.value = p.copy(bytes = 0, size = -1, failed = failed.coerceAtLeast(0), finished = true)
                }
                return true
            }
            _current.value = next.path
            val done = _progress.value?.done ?: 0
            val title = db.tracks().get(next.path)?.title ?: MStreamPaths.fileName(next.path)
            val count = done + db.downloads().pendingCount(MAX_ATTEMPTS)
            _progress.value = Progress(title, done = done, count = count)
            try {
                val file = files.download(account, client, next.path) { bytes, size ->
                    _progress.value = Progress(title, bytes, size, done, count)
                }
                // Indexed now: the library shows (and plays) it as a local, downloaded file at once.
                indexer.indexOne(file.path)
                db.downloads().remove(next.path)
                _progress.value = Progress(title, done = done + 1, count = count)
            } catch (e: java.io.IOException) {
                db.downloads().failed(next.path)
                if (!online()) {
                    _progress.value = null
                    return false
                }
            } finally {
                _current.value = null
            }
        }
    }

    suspend fun clearFailed() = db.downloads().clear()

    /**
     * "Download" anywhere: queues the server tracks among [paths] and starts. Returns a message to
     * show when nothing was queued; else null — the download popup ([progress]) shows at once.
     */
    suspend fun request(paths: List<String>): String? {
        val remote = paths.count(MStreamPaths::isRemote)
        if (remote == 0) return "Already on the device"
        val queued = enqueue(paths)
        if (queued == 0) return "Already downloaded"
        val running = _progress.value?.takeIf { !it.finished }
        _progress.value = running?.copy(count = running.count + queued)
            ?: Progress(db.tracks().get(paths.first(MStreamPaths::isRemote))?.title, count = queued)
        LibraryWork.downloadNow(context)
        return null
    }

    /**
     * Auto-download: keeps the next [app.zenelo.data.settings.ZeneloSettings.autoDownloadAhead]
     * server tracks of the queue in the cache, one at a time. Follows the queue itself (not track
     * changes, which arrive before a new queue is published); a change restarts the round.
     */
    fun start() {
        scope.launch {
            combine(queue.state, settings.settings) { snapshot, s ->
                if (!s.autoDownload || s.mstream == null) emptyList()
                else (1 until minOf(snapshot.size, s.autoDownloadAhead + 1)).map { snapshot.pathAt(it) }.filter(MStreamPaths::isRemote)
            }.distinctUntilChanged().collectLatest { ahead -> cacheAhead(ahead) }
        }
    }

    private suspend fun cacheAhead(ahead: List<String>) {
        if (ahead.isEmpty()) return
        val s = settings.settings.first()
        val account = s.mstream ?: return
        if (!online() || (s.autoDownloadWifiOnly && !unmetered())) return
        for (path in ahead) {
            if (files.localCopy(path) != null) continue
            runCatching { files.cache(account, client, path, s.cacheLimitMb * 1024L * 1024L) }
        }
    }

    private fun online(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun unmetered(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun capabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

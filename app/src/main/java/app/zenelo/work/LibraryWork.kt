package app.zenelo.work

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.zenelo.ZeneloApp
import app.zenelo.data.settings.NormalizationMode
import app.zenelo.library.MetadataFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background library pass:
 * 1. [IndexWorker] reads tags of new/changed files (no network).
 * 2. [FetchWorker] downloads missing covers (one lookup per album, then embeds them) and lyrics.
 *    Runs only on an unmetered network, and re-checks Wi-Fi itself.
 * 3. [LoudnessWorker] measures loudness of tracks without ReplayGain tags (battery not low).
 *
 * Both are idempotent: when WorkManager stops them (10 min cap) the next run continues where
 * this one left off.
 */
object LibraryWork {
    private const val INDEX = "library-index"
    private const val FETCH = "library-fetch"
    private const val FETCH_PERIODIC = "library-fetch-periodic"

    private const val LOUDNESS_PERIODIC = "library-loudness-periodic"
    private const val SERVER_SYNC = "mstream-sync"
    private const val SERVER_SYNC_PERIODIC = "mstream-sync-periodic"

    /** A sync older than this is repeated when the app comes to the front. */
    private val STALE_MS = TimeUnit.MINUTES.toMillis(15)
    private const val DOWNLOADS = "mstream-downloads"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private val batteryOk = Constraints.Builder().setRequiresBatteryNotLow(true).build()

    private val wifi = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** On app start: index now, fetch when on Wi-Fi, and again twice a day. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        // REPLACE, not KEEP: the chain's fetch step waits for Wi-Fi (maybe for days), and while it
        // waits KEEP skipped the whole chain, index included — new files weren't indexed on start.
        // Every step picks up where it stopped, so restarting them costs nothing.
        wm.beginUniqueWork(INDEX, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<IndexWorker>().build())
            .then(listOf(fetchRequest(), loudnessRequest()))
            .enqueue()
        wm.enqueueUniqueWork(SERVER_SYNC, ExistingWorkPolicy.KEEP, serverSyncRequest(force = false))
        wm.enqueueUniquePeriodicWork(
            FETCH_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FetchWorker>(12, TimeUnit.HOURS).setConstraints(wifi).build(),
        )
        wm.enqueueUniquePeriodicWork(
            LOUDNESS_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LoudnessWorker>(12, TimeUnit.HOURS).setConstraints(batteryOk).build(),
        )
        // The server changes meanwhile (new files, ratings / playlists from its web app, other
        // devices' plays), and what we couldn't send offline waits in the outbox: a round every
        // few hours even while the app stays in the background. Not logged in, it returns at once.
        wm.enqueueUniquePeriodicWork(
            SERVER_SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ServerSyncWorker>(3, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }

    /** The app came to the front: sync again if the last round is older than [STALE_MS]. */
    fun syncServerIfStale(context: Context) {
        val sync = (context.applicationContext as ZeneloApp).container.mstreamSync
        if (System.currentTimeMillis() - sync.lastSyncAt < STALE_MS) return
        WorkManager.getInstance(context).enqueueUniqueWork(SERVER_SYNC, ExistingWorkPolicy.KEEP, serverSyncRequest(force = false))
    }

    /** The mStream server's library, now (after logging in, "Sync now"); [force] ignores its revision. */
    fun syncServer(context: Context, force: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(SERVER_SYNC, ExistingWorkPolicy.REPLACE, serverSyncRequest(force))
    }

    /** Works through the mStream download queue (appended after a run in progress). */
    fun downloadNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DOWNLOADS,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(online).build(),
        )
    }

    private fun serverSyncRequest(force: Boolean) = OneTimeWorkRequestBuilder<ServerSyncWorker>()
        .setConstraints(online)
        .setInputData(workDataOf(ServerSyncWorker.FORCE to force))
        .build()

    /** "Scan library now" in settings. */
    fun scanNow(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(INDEX, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<IndexWorker>().build())
            .then(listOf(fetchRequest(), loudnessRequest()))
            .enqueue()
    }

    private fun fetchRequest() = OneTimeWorkRequestBuilder<FetchWorker>().setConstraints(wifi).build()

    private fun loudnessRequest() = OneTimeWorkRequestBuilder<LoudnessWorker>().setConstraints(batteryOk).build()

    fun observeRunning(context: Context) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(INDEX)

    /** What the library pass is doing right now, for settings; null when nothing runs. */
    sealed interface Stage {
        /** Reading tags: [done] of [total] new or changed files (0 / 0 while walking the folders). */
        data class Scanning(val done: Int, val total: Int) : Stage
        /** Covers / lyrics: [done] of [total] albums and tracks to look up. */
        data class Fetching(val done: Int, val total: Int) : Stage
        data object Measuring : Stage
    }

    /**
     * The running stage of the "library-index" chain. Covers / lyrics and loudness wait for Wi-Fi and
     * battery and can take long: only a stage that actually runs counts, a waiting one isn't shown.
     */
    fun stageOf(infos: List<WorkInfo>): Stage? {
        fun of(worker: Class<*>) = infos.firstOrNull { worker.name in it.tags }
        val index = of(IndexWorker::class.java)
        if (index != null && (index.state == WorkInfo.State.RUNNING || index.state == WorkInfo.State.ENQUEUED)) {
            return Stage.Scanning(index.progress.getInt(PROGRESS_DONE, 0), index.progress.getInt(PROGRESS_TOTAL, 0))
        }
        of(FetchWorker::class.java)?.takeIf { it.state == WorkInfo.State.RUNNING }?.let {
            return Stage.Fetching(it.progress.getInt(PROGRESS_DONE, 0), it.progress.getInt(PROGRESS_TOTAL, 0))
        }
        if (of(LoudnessWorker::class.java)?.state == WorkInfo.State.RUNNING) return Stage.Measuring
        return null
    }

    internal const val PROGRESS_DONE = "done"
    internal const val PROGRESS_TOTAL = "total"
}

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Environment.isExternalStorageManager()) return Result.success()
        val container = (applicationContext as ZeneloApp).container
        val home = container.settings.settings.first().homeFolder?.let(::File)
        container.indexer.indexAll(container.fileBrowser.roots().map { it.dir }, first = home) { done, total ->
            setProgress(workDataOf(LibraryWork.PROGRESS_DONE to done, LibraryWork.PROGRESS_TOTAL to total))
        }
        return Result.success()
    }
}

class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZeneloApp).container
        val fetcher = container.metadata
        if (!Environment.isExternalStorageManager() || !container.settings.settings.first().onlineFetch) return Result.success()

        // Local files only. Server tracks get the server's cover / lyrics (then the public sources)
        // when they're shown or played; looking up a whole server library from here took hours —
        // and their rows never have embedded art, so they'd come round on every run.
        val local = { t: app.zenelo.data.db.TrackEntity -> !app.zenelo.mstream.MStreamPaths.isRemote(t.path) }
        val groups = container.db.tracks().withoutArtwork().filter(local).groupBy { fetcher.coverKey(it) }
        val retryBefore = System.currentTimeMillis() - MetadataFetcher.RETRY_AFTER_MS
        val needingLyrics = container.db.tracks().needingLyrics(retryBefore).filter(local)
        val total = groups.size + needingLyrics.size
        var done = 0
        suspend fun step() {
            done++
            if (done % 10 == 0 || done == total) setProgress(workDataOf(LibraryWork.PROGRESS_DONE to done, LibraryWork.PROGRESS_TOTAL to total))
        }
        setProgress(workDataOf(LibraryWork.PROGRESS_DONE to 0, LibraryWork.PROGRESS_TOTAL to total))

        // Covers: one lookup per album; the result is embedded into every track of it lacking art.
        for ((_, group) in groups) {
            if (isStopped || !fetcher.onlineAllowed()) return Result.success()
            step()
            val cover = fetcher.coverFor(group.first(), allowNetwork = true) ?: continue
            if (fetcher.folderImage(group.first().dir) == cover) continue // folder art is shown, not embedded
            if (!fetcher.coverIsConfident(group.first())) continue // guessed from the path: shown, not embedded
            fetcher.embed(group, cover, replace = false)
        }

        // Lyrics, politely paced. A lookup that gets no answer at all (null: LRCLIB down or the
        // network gone) is retried next run; several in a row end this one instead of timing out
        // track after track.
        var failures = 0
        for (track in needingLyrics) {
            if (isStopped || !fetcher.onlineAllowed()) return Result.success()
            step()
            if (fetcher.lyricsFor(track, allowNetwork = true) == null) {
                if (++failures >= MAX_FAILURES) {
                    android.util.Log.w("Zenelo", "lyrics: no answer from LRCLIB $failures times in a row, stopping until the next run")
                    return Result.success()
                }
            } else {
                failures = 0
            }
            delay(300)
        }
        return Result.success()
    }

    private companion object {
        const val MAX_FAILURES = 8
    }
}

class LoudnessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Environment.isExternalStorageManager()) return Result.success()
        val container = (applicationContext as ZeneloApp).container
        if (container.settings.settings.first().normalization == NormalizationMode.OFF) return Result.success()
        for (track in container.db.tracks().needingLoudness()) {
            if (isStopped) break
            container.loudness.ensureMeasured(track)
        }
        return Result.success()
    }
}

/** Mirrors the mStream server's library (see [app.zenelo.mstream.MStreamSync]); retried on failure. */
class ServerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZeneloApp).container
        return try {
            container.mstreamSync.sync(force = inputData.getBoolean(FORCE, false))
            Result.success()
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val FORCE = "force"
    }
}

/** Downloads the queued server tracks ([app.zenelo.mstream.MStreamDownloads]); retried when the network drops. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZeneloApp).container
        return if (container.mstreamDownloads.drain()) Result.success() else Result.retry()
    }
}

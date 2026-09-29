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
        wm.beginUniqueWork(INDEX, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build())
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
        data object Fetching : Stage
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
        if (of(FetchWorker::class.java)?.state == WorkInfo.State.RUNNING) return Stage.Fetching
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

        // Covers: one lookup per album; the result is embedded into every track of it lacking art.
        val groups = container.db.tracks().withoutArtwork().groupBy { fetcher.coverKey(it) }
        for ((_, group) in groups) {
            if (isStopped || !fetcher.onlineAllowed()) return Result.success()
            val cover = fetcher.coverFor(group.first(), allowNetwork = true) ?: continue
            if (fetcher.folderImage(group.first().dir) == cover) continue // folder art is shown, not embedded
            if (!fetcher.coverIsConfident(group.first())) continue // guessed from the path: shown, not embedded
            fetcher.embed(group, cover, replace = false)
        }

        // Lyrics, politely paced.
        val retryBefore = System.currentTimeMillis() - MetadataFetcher.RETRY_AFTER_MS
        for (track in container.db.tracks().needingLyrics(retryBefore)) {
            if (isStopped || !fetcher.onlineAllowed()) return Result.success()
            fetcher.lyricsFor(track, allowNetwork = true)
            delay(300)
        }
        return Result.success()
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

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
import androidx.work.WorkerParameters
import app.zenelo.ZeneloApp
import app.zenelo.library.MetadataFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background library pass:
 * 1. [IndexWorker] reads tags of new/changed files (no network).
 * 2. [FetchWorker] downloads missing covers (one lookup per album, then embeds them) and lyrics.
 *    Runs only on an unmetered network, and re-checks Wi-Fi itself.
 *
 * Both are idempotent: when WorkManager stops them (10 min cap) the next run continues where
 * this one left off.
 */
object LibraryWork {
    private const val INDEX = "library-index"
    private const val FETCH = "library-fetch"
    private const val FETCH_PERIODIC = "library-fetch-periodic"

    private val wifi = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** On app start: index now, fetch when on Wi-Fi, and again twice a day. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.beginUniqueWork(INDEX, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build())
            .then(OneTimeWorkRequestBuilder<FetchWorker>().setConstraints(wifi).build())
            .enqueue()
        wm.enqueueUniquePeriodicWork(
            FETCH_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FetchWorker>(12, TimeUnit.HOURS).setConstraints(wifi).build(),
        )
    }

    /** "Scan library now" in settings. */
    fun scanNow(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(INDEX, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<IndexWorker>().build())
            .then(OneTimeWorkRequestBuilder<FetchWorker>().setConstraints(wifi).build())
            .enqueue()
    }

    fun observeRunning(context: Context) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(INDEX)
}

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Environment.isExternalStorageManager()) return Result.success()
        val container = (applicationContext as ZeneloApp).container
        container.indexer.indexAll(container.fileBrowser.roots().map { it.dir })
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
            fetcher.embed(group, cover, replace = false)
        }

        // Lyrics, politely paced.
        val retryBefore = System.currentTimeMillis() - MetadataFetcher.RETRY_AFTER_MS
        for (track in container.db.tracks().needingLyrics(retryBefore)) {
            if (isStopped || !fetcher.onlineAllowed()) return Result.success()
            if (track.title == null) continue
            fetcher.lyricsFor(track, allowNetwork = true)
            delay(300)
        }
        return Result.success()
    }
}

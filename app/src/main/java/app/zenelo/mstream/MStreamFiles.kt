package app.zenelo.mstream

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Server tracks on the device:
 * - downloads ("Download"): kept for good, as ordinary files under [downloadDir] with the server's
 *   folder layout (`<dir>/<vpath>/<rel>`); indexed like any local file, so the library then plays
 *   (and marks) them as downloaded;
 * - the queue cache (auto-download): app-private, evicted by size, least recently played first.
 *
 * The player plays a local copy when there is one ([localCopy]), else streams.
 */
class MStreamFiles(context: Context, base: OkHttpClient, private val links: ServerLinks) {
    private val client = base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val cacheDir = File(context.cacheDir, "mstream").apply { mkdirs() }

    /** Pushed from settings ([setDownloadDir]); read by the player and [ServerLinks]. */
    @Volatile
    var downloadDir: File = defaultDownloadDir()
        private set

    /** Sets the download folder; files already in it are linked to their server tracks by [ServerLinks]. */
    fun setDownloadDir(dir: File) {
        downloadDir = dir
        links.downloadDir = dir
    }

    fun downloadTarget(path: String): File = File(downloadDir, MStreamPaths.serverPath(path))

    fun downloaded(path: String): File? = downloadTarget(path).takeIf { it.isFile }

    /** A server track with a local copy — downloaded or the user's own (from memory: fine in lists). */
    fun isDownloaded(path: String?): Boolean = links.current.localOf(path) != null

    fun cached(path: String): File? = cacheFile(path).takeIf { it.isFile }

    /** Names in the queue cache (read once, then kept up to date): [isCached] without disk access. */
    private val cachedNames: MutableSet<String> by lazy {
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()).apply {
            cacheDir.list()?.filterNot { it.endsWith(PART) }?.let(::addAll)
        }
    }

    /** A server track in the queue cache (from memory: fine in lists). */
    fun isCached(path: String): Boolean = cacheFile(path).name in cachedNames

    /** A server track that plays without the network: a local copy, downloaded or cached. */
    fun playsOffline(path: String): Boolean =
        !MStreamPaths.isRemote(path) || links.current.localOf(path) != null || isCached(path) || downloaded(path) != null

    /**
     * A local copy of a server track: its linked file (a download, or the user's own copy), else
     * one in the download folder, else in the queue cache (using it keeps it from eviction longest).
     */
    fun localCopy(path: String): File? {
        if (!MStreamPaths.isRemote(path)) return null
        links.current.localOf(path)?.let(::File)?.takeIf { it.isFile }?.let { return it }
        downloaded(path)?.let { return it }
        return cached(path)?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** Downloads [path] to [downloadDir] (for good); [progress] gets bytes so far and the size (-1 unknown). */
    suspend fun download(account: MStreamAccount, mstream: MStreamClient, path: String, progress: (Long, Long) -> Unit = { _, _ -> }): File =
        fetch(mstream.mediaUrl(account, MStreamPaths.serverPath(path)).toString(), account.token, downloadTarget(path), progress)
            .also { links.onDownloaded(it, path) }

    /** Downloads [path] into the queue cache, then trims the cache to [limitBytes]. */
    suspend fun cache(account: MStreamAccount, mstream: MStreamClient, path: String, limitBytes: Long): File {
        val file = fetch(mstream.mediaUrl(account, MStreamPaths.serverPath(path)).toString(), account.token, cacheFile(path))
        cachedNames.add(file.name)
        evict(limitBytes, keep = file)
        return file
    }

    fun cacheBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cachedNames.clear()
    }

    private fun evict(limitBytes: Long, keep: File) {
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(PART) }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limitBytes) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
            cachedNames.remove(f.name)
        }
    }

    private suspend fun fetch(url: String, token: String, target: File, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        if (target.isFile) return@withContext target
        target.parentFile?.mkdirs()
        val part = File(target.path + PART)
        val request = Request.Builder().url(url).header("x-access-token", token).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MStreamException("Download failed (${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Couldn't save ${target.name}")
        }
        target
    }

    private fun cacheFile(path: String): File {
        val server = MStreamPaths.serverPath(path)
        val ext = server.substringAfterLast('.', "").take(8)
        val hash = MessageDigest.getInstance("SHA-1").digest(server.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, if (ext.isEmpty()) hash else "$hash.$ext")
    }

    companion object {
        private const val PART = ".part"

        /** `mstream` at the root of the internal storage. */
        fun defaultDownloadDir(): File = File(Environment.getExternalStorageDirectory(), "mstream")
    }
}

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
class MStreamFiles(context: Context, base: OkHttpClient) {
    private val client = base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val cacheDir = File(context.cacheDir, "mstream").apply { mkdirs() }

    /** Pushed from settings; read by rows (download mark) and the player. */
    @Volatile
    var downloadDir: File = defaultDownloadDir()

    fun downloadTarget(path: String): File = File(downloadDir, MStreamPaths.serverPath(path))

    fun downloaded(path: String): File? = downloadTarget(path).takeIf { it.isFile }

    fun cached(path: String): File? = cacheFile(path).takeIf { it.isFile }

    /** A downloaded or cached copy of a server track; using a cached one keeps it from eviction longest. */
    fun localCopy(path: String): File? {
        if (!MStreamPaths.isRemote(path)) return null
        downloaded(path)?.let { return it }
        return cached(path)?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** A local file that "Download" put there (download mark in lists). */
    fun isDownload(localPath: String?): Boolean =
        localPath != null && !MStreamPaths.isRemote(localPath) && localPath.startsWith(downloadDir.path + "/")

    /** Downloads [path] to [downloadDir] (for good). */
    suspend fun download(account: MStreamAccount, mstream: MStreamClient, path: String): File =
        fetch(mstream.mediaUrl(account, MStreamPaths.serverPath(path)).toString(), account.token, downloadTarget(path))

    /** Downloads [path] into the queue cache, then trims the cache to [limitBytes]. */
    suspend fun cache(account: MStreamAccount, mstream: MStreamClient, path: String, limitBytes: Long): File {
        val file = fetch(mstream.mediaUrl(account, MStreamPaths.serverPath(path)).toString(), account.token, cacheFile(path))
        evict(limitBytes, keep = file)
        return file
    }

    fun cacheBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun evict(limitBytes: Long, keep: File) {
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(PART) }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limitBytes) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    private suspend fun fetch(url: String, token: String, target: File): File = withContext(Dispatchers.IO) {
        if (target.isFile) return@withContext target
        target.parentFile?.mkdirs()
        val part = File(target.path + PART)
        val request = Request.Builder().url(url).header("x-access-token", token).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MStreamException("Download failed (${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            part.outputStream().use { out -> body.byteStream().copyTo(out) }
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

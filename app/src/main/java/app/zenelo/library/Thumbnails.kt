package app.zenelo.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import app.zenelo.data.db.TrackDao
import app.zenelo.data.db.TrackEntity
import app.zenelo.mstream.MStreamPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Cover thumbnails: embedded art, else the folder image or a downloaded cover. Small (128px) for
 * list rows, large (360px) for grid tiles. Kept in memory and as JPEGs in the cache dir; at most 3
 * decoded at once so fast scrolling through a 7000-file folder doesn't flood the disk.
 */
class Thumbnails(context: Context, private val tracks: TrackDao, private val fetcher: MetadataFetcher) {
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val largeDir = File(context.cacheDir, "thumbs-large").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val largeMemory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Keys known to have no art, so rows don't keep retrying. */
    private val missing = LruCache<String, Boolean>(4000)
    private val gate = Semaphore(3)

    private val _revision = MutableStateFlow(0)

    /** Bumped when a thumbnail was replaced ([override]), so visible rows reload. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun peek(path: String, large: Boolean = false): Bitmap? = (if (large) largeMemory else memory).get(path)

    /** Shows [cover] as [path]'s thumbnail right away (a cover just picked or written). */
    fun override(path: String, cover: File) {
        val bitmap = runCatching { decode(cover.readBytes(), SIZE) }.getOrNull() ?: return
        missing.remove(path)
        memory.put(path, bitmap)
        largeMemory.remove(path)
        _revision.value++
    }

    suspend fun load(path: String, large: Boolean = false): Bitmap? {
        val cache = if (large) largeMemory else memory
        cache.get(path)?.let { return it }
        if (missing.get(path) == true) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val file = File(path)
                val stamp = "$path:${file.lastModified()}"
                val cached = File(if (large) largeDir else dir, sha1(stamp) + ".jpg")
                val bitmap = if (cached.exists()) {
                    BitmapFactory.decodeFile(cached.path)
                } else {
                    val info = tracks.get(path)
                    val bytes = if (MStreamPaths.isRemote(path)) remoteSource(info) else source(file, info)
                    bytes?.let { decode(it, if (large) LARGE else SIZE)?.also { b -> save(b, cached) } }
                }
                if (bitmap != null) cache.put(path, bitmap) else missing.put(path, true)
                bitmap
            }
        }
    }

    private suspend fun source(file: File, info: TrackEntity?): ByteArray? {
        if (info == null || info.hasArtwork) {
            embedded(file)?.let { return it }
        }
        val cover = if (info != null) fetcher.localCovers(listOf(info))[info.path] else fetcher.folderImage(file.parent.orEmpty())
        return cover?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    /** A server track: its album's cached cover, else the server's art (downloaded once, then cached). */
    private suspend fun remoteSource(info: TrackEntity?): ByteArray? {
        val cover = fetcher.coverFor(info ?: return null, allowNetwork = true) ?: return null
        return runCatching { cover.readBytes() }.getOrNull()
    }

    private fun embedded(file: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = size.toFloat() / minOf(decoded.width, decoded.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    }

    private fun save(bitmap: Bitmap, file: File) {
        runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SIZE = 128
        const val LARGE = 360
    }
}

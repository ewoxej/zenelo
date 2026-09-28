package app.zenelo.library

import android.content.Context
import android.os.storage.StorageManager
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object AudioFormats {
    val extensions = setOf(
        "flac", "mp3", "m4a", "mp4", "aac", "alac", "wav", "aif", "aiff",
        "ogg", "oga", "opus", "ape", "wv", "dsf", "dff", "wma",
    )

    fun isAudio(file: File) = file.extension.lowercase() in extensions
}

data class StorageRoot(val name: String, val dir: File, val removable: Boolean) {
    /** Breadcrumb / title label: "SD" or "Internal". */
    val shortName: String get() = if (removable) "SD" else "Internal"
}

data class AudioFile(val path: String, val name: String, val extension: String, val sizeBytes: Long) {
    val title: String get() = name.substringBeforeLast('.')

    companion object {
        fun of(file: File) = AudioFile(file.absolutePath, file.name, file.extension.lowercase(), file.length())
    }
}

/** `stamp` is the directory's mtime at scan time; it changes when entries are added or removed. */
data class DirListing(val dir: File, val stamp: Long, val folders: List<File>, val files: List<AudioFile>)

/**
 * Direct file-system access (the app holds MANAGE_EXTERNAL_STORAGE).
 *
 * Shared storage goes through FUSE, where every stat is a round trip, so listings are cached in
 * memory and revalidated against the directory mtime (one stat) instead of rescanned.
 */
class FileSystemBrowser(private val context: Context) {

    private val cache = LruCache<String, DirListing>(CACHE_SIZE)

    /** Internal storage and SD card(s), in system order. */
    fun roots(): List<StorageRoot> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { volume ->
            val dir = volume.directory ?: return@mapNotNull null
            StorageRoot(volume.getDescription(context), dir, volume.isRemovable)
        }
    }

    /** Last known listing, possibly stale. Cheap, safe on the main thread. */
    fun cached(dir: File): DirListing? = cache.get(dir.absolutePath)

    /** Fresh listing: served from cache when the directory is unchanged, rescanned otherwise. */
    suspend fun list(dir: File): DirListing = withContext(Dispatchers.IO) {
        val stamp = dir.lastModified()
        cache.get(dir.absolutePath)?.takeIf { it.stamp == stamp }
            ?: scan(dir, stamp).also { cache.put(dir.absolutePath, it) }
    }

    /** Warms the cache for folders the user is likely to open next. */
    suspend fun prefetch(dirs: List<File>) = withContext(Dispatchers.IO) {
        for (dir in dirs.take(PREFETCH_LIMIT)) {
            ensureActive()
            if (cache.get(dir.absolutePath) == null) runCatching { list(dir) }
        }
    }

    private fun scan(dir: File, stamp: Long): DirListing {
        val folders = ArrayList<Pair<File, List<String>>>()
        val files = ArrayList<Pair<AudioFile, List<String>>>()
        try {
            Files.newDirectoryStream(dir.toPath()).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (name.startsWith('.')) continue
                    // One stat per entry instead of isDirectory/isFile/length.
                    val attrs = try {
                        Files.readAttributes(path, BasicFileAttributes::class.java)
                    } catch (e: IOException) {
                        continue
                    }
                    when {
                        attrs.isDirectory -> folders += path.toFile() to NaturalOrder.key(name)
                        attrs.isRegularFile -> {
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext in AudioFormats.extensions) {
                                files += AudioFile(path.toString(), name, ext, attrs.size()) to NaturalOrder.key(name)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Unreadable directory: show it as empty.
        }
        return DirListing(
            dir = dir,
            stamp = stamp,
            folders = folders.sortedWith { a, b -> NaturalOrder.compare(a.second, b.second) }.map { it.first },
            files = files.sortedWith { a, b -> NaturalOrder.compare(a.second, b.second) }.map { it.first },
        )
    }

    private companion object {
        const val CACHE_SIZE = 300
        const val PREFETCH_LIMIT = 40
    }
}

/** "Track 2" before "Track 10". Keys are computed once per name, not per comparison. */
private object NaturalOrder {
    private val chunk = Regex("\\d+|\\D+")

    fun key(name: String): List<String> = chunk.findAll(name.lowercase()).map { it.value }.toList()

    fun compare(ca: List<String>, cb: List<String>): Int {
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                xs.length.compareTo(ys.length).takeIf { it != 0 } ?: xs.compareTo(ys)
            } else {
                x.compareTo(y)
            }
            if (r != 0) return r
        }
        return ca.size.compareTo(cb.size)
    }
}

package app.zenelo.library

import app.zenelo.data.db.LyricsEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Keeps the `tracks` table in sync with the files on disk (tags are re-read only when a file changes). */
class LibraryIndexer(db: ZeneloDatabase) {
    private val tracks = db.tracks()
    private val lyrics = db.lyrics()

    /** Indexes the given files if new or changed. Used for the folder on screen, so rows get tags quickly. */
    suspend fun indexFiles(files: List<File>): Int = withContext(Dispatchers.IO) {
        val known = files.map { it.absolutePath }.chunked(QUERY_CHUNK).flatMap { tracks.getMany(it) }.associateBy { it.path }
        val changed = files.filter { f -> known[f.absolutePath]?.let { it.modified != f.lastModified() || it.size != f.length() } ?: true }
        readAndStore(changed)
        changed.size
    }

    suspend fun indexOne(path: String): TrackEntity? = withContext(Dispatchers.IO) {
        val file = File(path).takeIf { it.isFile } ?: return@withContext null
        readAndStore(listOf(file))
        tracks.get(path)
    }

    /**
     * Walks every storage root (skipping hidden folders and the top-level `Android` folder), indexes
     * new/changed files (those under [first], the home folder, before the rest) and drops rows for
     * files that are gone.
     */
    suspend fun indexAll(
        roots: List<File>,
        first: File? = null,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val found = HashMap<String, Pair<Long, Long>>()
        roots.forEach { walk(it, found) }
        ensureActive()

        val stamps = tracks.stamps().associateBy { it.path }
        // Whole seconds: the walk's times (NIO) come in seconds on Android, the stored ones
        // (File.lastModified) in milliseconds — compared exactly, every file looked changed and
        // each scan re-read the whole library.
        val changed = found.filter { (path, stamp) ->
            stamps[path]?.let { it.modified / 1000 != stamp.first / 1000 || it.size != stamp.second } ?: true
        }.keys.map(::File)
            // The home folder first: its folder counts are what the user sees first.
            .sortedBy { first == null || !it.path.startsWith(first.path + "/") }
        val removed = stamps.keys.filter { path -> path !in found && roots.any { path.startsWith(it.path + "/") } }
        removed.chunked(QUERY_CHUNK).forEach { tracks.delete(it) }
        readAndStore(changed, onProgress)
    }

    private suspend fun readAndStore(files: List<File>, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }) {
        for ((index, batch) in files.chunked(BATCH).withIndex()) {
            onProgress(index * BATCH, files.size)
            kotlin.coroutines.coroutineContext.ensureActive()
            val read = batch.map(TagReader::read)
            tracks.upsert(read.map { it.track })
            // Lyrics from tags seed the lyrics table, unless lyrics came from elsewhere already.
            for (data in read) {
                val text = data.lyrics ?: continue
                val existing = lyrics.get(data.track.path)
                if (existing == null || existing.source == SOURCE_TAG || existing.isEmpty) {
                    val synced = Lrc.looksSynced(text)
                    lyrics.upsert(
                        LyricsEntity(
                            path = data.track.path,
                            synced = text.takeIf { synced },
                            plain = text.takeIf { !synced },
                            instrumental = false,
                            source = SOURCE_TAG,
                            fetchedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    private fun walk(root: File, out: MutableMap<String, Pair<Long, Long>>) {
        val rootPath = root.toPath()
        runCatching {
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir == rootPath) return FileVisitResult.CONTINUE
                        val name = dir.fileName.toString()
                        val skip = name.startsWith('.') || (dir.parent == rootPath && name == "Android")
                        return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName.toString()
                        if (attrs.isRegularFile && name.substringAfterLast('.', "").lowercase() in AudioFormats.extensions) {
                            out[file.toString()] = attrs.lastModifiedTime().toMillis() to attrs.size()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
    }

    companion object {
        const val SOURCE_TAG = "tag"
        private const val BATCH = 40
        private const val QUERY_CHUNK = 500
    }
}

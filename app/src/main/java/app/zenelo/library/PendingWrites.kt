package app.zenelo.library

import app.zenelo.data.db.PendingWriteEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.library.TagReader.EditableTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Tag edits and cover embeds. A file that isn't playing is written right away. The playing file is
 * written when its track stops playing ([flush]): until then the change is kept in the
 * `pending_writes` table (so it survives the app being killed) and shown at once — the library
 * row is updated in place and [onVisualChange] refreshes the queue, Now Playing and thumbnails.
 *
 * The in-place row keeps the file's old mtime, so indexing doesn't read the old tags back over it;
 * the real write changes the mtime and the row is re-read from the file.
 */
class PendingWrites(
    db: ZeneloDatabase,
    private val indexer: LibraryIndexer,
    private val nowPlaying: StateFlow<String?>,
    /** path, and the cover file when the cover changed. */
    private val onVisualChange: (String, File?) -> Unit,
) {
    private val pending = db.pendingWrites()
    private val tracks = db.tracks()
    private val mutex = Mutex()

    enum class Result { WRITTEN, DEFERRED, FAILED }

    /** Tags as the user sees them: a pending edit if there is one, else the file's. */
    suspend fun readTags(path: String): EditableTags? = withContext(Dispatchers.IO) {
        pending.get(path)?.tagsJson?.let(::decode) ?: TagReader.readEditable(File(path))
    }

    suspend fun writeTags(path: String, tags: EditableTags): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (path == nowPlaying.value) {
                val existing = pending.get(path)
                pending.upsert(PendingWriteEntity(path, encode(tags), existing?.coverFile))
                showTags(path, tags)
                onVisualChange(path, null)
                return@withContext Result.DEFERRED
            }
            if (!TagReader.writeEditable(File(path), tags)) return@withContext Result.FAILED
            indexer.indexOne(path)
            onVisualChange(path, null)
            Result.WRITTEN
        }
    }

    /** Embeds [cover] (replacing any art). Returns false if the format can't take it. */
    suspend fun embedCover(path: String, cover: File): Result = withContext(Dispatchers.IO) {
        if (!TagReader.canEmbed(path)) return@withContext Result.FAILED
        mutex.withLock {
            if (path == nowPlaying.value) {
                val existing = pending.get(path)
                pending.upsert(PendingWriteEntity(path, existing?.tagsJson, cover.absolutePath))
                onVisualChange(path, cover)
                return@withContext Result.DEFERRED
            }
            if (!TagReader.embedArtwork(File(path), cover.readBytes())) return@withContext Result.FAILED
            indexer.indexOne(path)
            onVisualChange(path, cover)
            Result.WRITTEN
        }
    }

    /** Writes everything waiting for files that are no longer playing. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val playing = nowPlaying.value
            for (write in pending.all()) {
                if (write.path == playing) continue
                val file = File(write.path)
                if (file.isFile) {
                    write.tagsJson?.let(::decode)?.let { TagReader.writeEditable(file, it) }
                    write.coverFile?.let(::File)?.takeIf { it.isFile }?.let { TagReader.embedArtwork(file, it.readBytes()) }
                    indexer.indexOne(write.path)
                    onVisualChange(write.path, write.coverFile?.let(::File))
                }
                pending.delete(write.path)
            }
        }
    }

    /** Updates the library row so lists and the queue show the edit before the file has it. */
    private suspend fun showTags(path: String, tags: EditableTags) {
        val row = tracks.get(path) ?: indexer.indexOne(path) ?: return
        fun String.orNull() = trim().takeIf { it.isNotEmpty() }
        val artist = tags.artist.orNull()
        val album = tags.album.orNull()
        val albumArtist = tags.albumArtist.orNull()
        tracks.upsert(
            listOf(
                row.copy(
                    title = tags.title.orNull(),
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    trackNumber = tags.trackNumber.substringBefore('/').trim().toIntOrNull(),
                    albumKey = TagReader.albumKey(albumArtist ?: artist, album),
                ),
            ),
        )
    }

    private fun encode(tags: EditableTags): String = JSONObject()
        .put("title", tags.title)
        .put("artist", tags.artist)
        .put("album", tags.album)
        .put("albumArtist", tags.albumArtist)
        .put("trackNumber", tags.trackNumber)
        .put("year", tags.year)
        .put("genre", tags.genre)
        .toString()

    private fun decode(json: String): EditableTags? = runCatching {
        val o = JSONObject(json)
        EditableTags(
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            albumArtist = o.optString("albumArtist"),
            trackNumber = o.optString("trackNumber"),
            year = o.optString("year"),
            genre = o.optString("genre"),
        )
    }.getOrNull()
}

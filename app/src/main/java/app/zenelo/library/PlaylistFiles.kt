package app.zenelo.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.ZeneloDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Playlists to and from M3U / M3U8 files picked with the system file picker. Entries that don't
 * resolve to a file (another device's paths) are looked up in the library by their path's tail.
 */
class PlaylistFiles(private val context: Context, private val db: ZeneloDatabase) {
    data class Imported(val id: Long?, val name: String, val found: Int, val missing: Int)

    /** The playlist file's tracks that were found, in order, and how many weren't. */
    private data class Resolved(val name: String, val paths: List<String>, val missing: Int)

    /** Reads [uri] into a new playlist named after the file; no playlist when nothing was found. */
    suspend fun import(uri: Uri): Imported? = withContext(Dispatchers.IO) {
        val r = resolve(uri) ?: return@withContext null
        val id = if (r.paths.isEmpty()) null else db.playlists().create(r.name, r.paths)
        Imported(id, r.name, r.paths.size, r.missing)
    }

    data class Favorited(val name: String, val added: Int, val already: Int, val missing: Int)

    /** Adds [uri]'s tracks to favorites (the ones already there keep their place). */
    suspend fun importToFavorites(uri: Uri): Favorited? = withContext(Dispatchers.IO) {
        val r = resolve(uri) ?: return@withContext null
        val favorites = db.favorites()
        val paths = r.paths.distinct()
        val fresh = paths.filterNot { favorites.isFavorite(it) }
        val tracks = fresh.chunked(900).flatMap { db.tracks().getMany(it) }.associateBy { it.path }
        // In the file's order: the first track ends up newest, on top of the favorites list.
        val now = System.currentTimeMillis()
        favorites.insertAll(
            fresh.mapIndexed { i, path ->
                val t = tracks[path]
                FavoriteEntity(path, FavoriteKind.TRACK, t?.title ?: File(path).nameWithoutExtension, t?.artist, addedAt = now - i)
            },
        )
        Favorited(r.name, fresh.size, paths.size - fresh.size, r.missing)
    }

    private suspend fun resolve(uri: Uri): Resolved? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val entries = M3u.parse(decode(bytes))
        val base = documentPath(uri)?.substringBeforeLast('/')
        val paths = entries.mapNotNull { locate(it.location, base) }
        val name = (displayName(uri) ?: "Imported").substringBeforeLast('.').ifBlank { "Imported" }
        return Resolved(name, paths, entries.size - paths.size)
    }

    /** Writes playlist [id] to [uri] as M3U8; returns the number of tracks. */
    suspend fun export(id: Long, uri: Uri): Int = withContext(Dispatchers.IO) {
        val tracks = db.playlists().observeTracks(id).first()
        val base = documentPath(uri)?.substringBeforeLast('/')
        val text = M3u.write(tracks.map { M3u.Track(it.path, it.artist, it.title, it.durationMs) }, base)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Can't write $uri")
        tracks.size
    }

    private suspend fun locate(location: String, base: String?): String? {
        M3u.resolve(location, base)?.let { if (File(it).isFile) return it }
        val name = M3u.fileName(location).takeIf { it.isNotEmpty() } ?: return null
        val candidates = db.tracks().pathsNamed(name).filter { it.endsWith("/$name", ignoreCase = true) }
        return M3u.bestMatch(location, candidates)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** UTF-8 (M3U8, most M3U today), else Windows-1251: older Russian-locale players wrote that. */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251"))
    }
}

/**
 * The file path behind a picker [uri] where it can be told (local storage documents), for
 * resolving and writing relative playlist paths. Null for other providers.
 */
fun documentPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    return when (uri.authority) {
        "com.android.externalstorage.documents" -> {
            val volume = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val root = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
            "$root/$rel".trimEnd('/')
        }
        "com.android.providers.downloads.documents" -> id.removePrefix("raw:").takeIf { it.startsWith("/") }
        else -> null
    }
}

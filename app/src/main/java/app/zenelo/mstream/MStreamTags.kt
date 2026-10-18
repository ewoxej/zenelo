package app.zenelo.mstream

import app.zenelo.data.db.TrackDao
import app.zenelo.library.Genres
import app.zenelo.library.TagReader
import app.zenelo.library.TagReader.EditableTags
import java.util.concurrent.ConcurrentHashMap

/**
 * Tags of the server's tracks, in the same editor as local files: read from and written into the
 * server's file (the fork's `tags/read` / `tags/write`, allowed when ping says `tagEdit`). After a
 * write the `tracks` row shows the new tags at once — as the manifest sync would fill it, so the
 * next sync (the server rescans the folder) changes nothing more.
 */
class MStreamTags(
    private val client: MStreamClient,
    private val account: suspend () -> MStreamAccount?,
    private val tracks: TrackDao,
    /** The queue / Now Playing show the new tags. */
    private val onChanged: (String) -> Unit,
) {
    /** What each file had when last read: a changed track number keeps its total ("3/12"). */
    private val read = ConcurrentHashMap<String, ServerTags>()

    /** Null: not connected, or a file type the server can't edit. Throws when the server can't be reached. */
    suspend fun read(path: String): EditableTags? {
        val acc = account() ?: return null
        val tags = client.tags(acc, MStreamPaths.serverPath(path)) ?: return null
        read[path] = tags
        return EditableTags(
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            albumArtist = tags.albumArtist,
            trackNumber = tags.track.substringBefore('/'),
            year = tags.year,
            genre = tags.genre,
        )
    }

    /** Writes what changed since [read]; false when it couldn't (the message is the server's). */
    suspend fun write(path: String, tags: EditableTags): Result<Unit> = runCatching {
        val acc = account() ?: throw MStreamException("Not connected to an mStream server")
        val before = read[path] ?: throw MStreamException("Read the tags first")
        val wanted = changes(before, tags)
        if (wanted.isEmpty()) return@runCatching
        val now = client.writeTags(acc, MStreamPaths.serverPath(path), wanted)
        read[path] = now
        show(path, now)
        onChanged(path)
    }

    private suspend fun show(path: String, tags: ServerTags) {
        val row = tracks.get(path) ?: return
        fun String.orNull() = trim().takeIf { it.isNotEmpty() }
        val artist = tags.artist.orNull()
        val album = tags.album.orNull()
        // As MStreamSync fills a server row: no album artist, the key by artist + album.
        tracks.upsert(
            listOf(
                row.copy(
                    title = tags.title.orNull(),
                    artist = artist,
                    album = album,
                    trackNumber = tags.track.substringBefore('/').toIntOrNull(),
                    albumKey = TagReader.albumKey(artist, album),
                    genre = Genres.split(tags.genre).distinct().joinToString("; ").ifEmpty { null },
                ),
            ),
        )
    }

    companion object {
        /**
         * The server's fields that differ (unit-tested): trimmed, "" removes; a changed track
         * number keeps the total the file had ("3/12" → "4/12").
         */
        fun changes(before: ServerTags, tags: EditableTags): Map<String, String> {
            val total = before.track.substringAfter('/', "")
            val number = tags.trackNumber.trim()
            val track = if (number.isNotEmpty() && '/' !in number && total.isNotEmpty()) "$number/$total" else number
            val after = before.copy(
                title = tags.title.trim(),
                artist = tags.artist.trim(),
                album = tags.album.trim(),
                albumArtist = tags.albumArtist.trim(),
                track = if (number == before.track.substringBefore('/')) before.track else track,
                year = tags.year.trim(),
                genre = tags.genre.trim(),
            ).toMap()
            val was = before.toMap()
            return after.filter { (k, v) -> v != was[k] }
        }
    }
}

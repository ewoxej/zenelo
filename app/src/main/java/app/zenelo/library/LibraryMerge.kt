package app.zenelo.library

import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.LibrarySource
import app.zenelo.mstream.MStreamPaths

/**
 * One library out of the local index and the mStream server's tracks. A server track that is
 * also on the device — same title / artist / album / number, or same file name and size, and the
 * same length; or linked (`ServerLinks`) — is left out of "All": the local copy is the one to play. [LibrarySource.MSTREAM] shows the whole
 * server library, twins included.
 */
object LibraryMerge {
    /**
     * [links]: server path → its linked local copy (`ServerLinks`), hidden in "All" like a twin
     * found by tags — also once tag edits made them differ.
     */
    fun visible(all: List<TrackEntity>, source: LibrarySource, links: Map<String, String> = emptyMap()): List<TrackEntity> {
        if (source == LibrarySource.LOCAL) return all.filterNot { MStreamPaths.isRemote(it.path) }
        if (source == LibrarySource.MSTREAM) return all.filter { MStreamPaths.isRemote(it.path) }
        val (remote, local) = all.partition { MStreamPaths.isRemote(it.path) }
        if (remote.isEmpty()) return local
        // Server tracks with a local copy: linked, or twins by tags / file (same length).
        val copies = twins(all) + links
        // A local copy without a genre tag takes its server twin's (the server's tags are often fuller).
        val genreOfLocal = HashMap<String, String>()
        for (r in remote) {
            val g = r.genre ?: continue
            copies[r.path]?.let { genreOfLocal.putIfAbsent(it, g) }
        }
        val merged = if (genreOfLocal.isEmpty()) local else local.map { t ->
            if (t.genre != null) t else genreOfLocal[t.path]?.let { t.copy(genre = it) } ?: t
        }
        return merged + remote.filterNot { it.path in copies }
    }

    /**
     * Local twins of server tracks: server path → local path. Same tags or same file name and size,
     * and the same length (±[LENGTH_SLACK_MS], when both are known): another version isn't a copy.
     */
    fun twins(all: List<TrackEntity>): Map<String, String> {
        val (remote, local) = all.partition { MStreamPaths.isRemote(it.path) }
        if (remote.isEmpty()) return emptyMap()
        val byTags = HashMap<String, TrackEntity>()
        val byFile = HashMap<String, TrackEntity>()
        for (t in local) {
            tagKey(t)?.let { byTags.putIfAbsent(it, t) }
            byFile.putIfAbsent(fileKey(t), t)
        }
        return remote.mapNotNull { r ->
            val l = tagKey(r)?.let(byTags::get)?.takeIf { sameLength(it, r) } ?: byFile[fileKey(r)]?.takeIf { sameLength(it, r) }
            l?.let { r.path to it.path }
        }.toMap()
    }

    /**
     * A link between [local] and [server] still holds: the same length and still the same title, file
     * name, or artist + album + number — so fixing a tag or two keeps it, but a file retagged as
     * another song, or another version, loses it (and may find its real twin).
     */
    fun stillSame(local: TrackEntity, server: TrackEntity): Boolean {
        if (!sameLength(local, server)) return false
        fun n(s: String?) = s?.let(Text::normalize)?.takeIf { it.isNotEmpty() }
        val title = n(local.title)
        if (title != null && title == n(server.title)) return true
        if (local.path.substringAfterLast('/').equals(server.path.substringAfterLast('/'), ignoreCase = true)) return true
        val artist = n(local.artist ?: local.albumArtist)
        return artist != null && artist == n(server.artist ?: server.albumArtist) &&
            n(local.album) == n(server.album) && local.trackNumber != null && local.trackNumber == server.trackNumber
    }

    private fun sameLength(a: TrackEntity, b: TrackEntity): Boolean =
        a.durationMs <= 0 || b.durationMs <= 0 || kotlin.math.abs(a.durationMs - b.durationMs) <= LENGTH_SLACK_MS

    private const val LENGTH_SLACK_MS = 3_000L

    /** Title, artist, album, number: all tagged or no key (untagged files match by name only). */
    fun tagKey(t: TrackEntity): String? {
        val title = t.title?.let(Text::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        val artist = (t.artist ?: t.albumArtist)?.let(Text::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return "$title|$artist|${t.album?.let(Text::normalize).orEmpty()}|${t.trackNumber ?: ""}"
    }

    fun fileKey(t: TrackEntity): String = t.path.substringAfterLast('/').lowercase() + "|" + t.size
}

package app.zenelo.library

import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.LibrarySource
import app.zenelo.mstream.MStreamPaths

/**
 * One library out of the local index and the mStream server's tracks. A server track that is
 * also on the device — same title / artist / album / number, or same file name and size — is
 * left out of "All": the local copy is the one to play. [LibrarySource.MSTREAM] shows the whole
 * server library, twins included.
 */
object LibraryMerge {
    fun visible(all: List<TrackEntity>, source: LibrarySource): List<TrackEntity> {
        if (source == LibrarySource.LOCAL) return all.filterNot { MStreamPaths.isRemote(it.path) }
        if (source == LibrarySource.MSTREAM) return all.filter { MStreamPaths.isRemote(it.path) }
        val (remote, local) = all.partition { MStreamPaths.isRemote(it.path) }
        if (remote.isEmpty()) return local
        val tags = local.mapNotNullTo(HashSet(), ::tagKey)
        val files = local.mapTo(HashSet(), ::fileKey)
        return local + remote.filterNot { r -> tagKey(r)?.let { it in tags } == true || fileKey(r) in files }
    }

    /** Local twins of server tracks: server path → local path. */
    fun twins(all: List<TrackEntity>): Map<String, String> {
        val (remote, local) = all.partition { MStreamPaths.isRemote(it.path) }
        if (remote.isEmpty()) return emptyMap()
        val byTags = HashMap<String, String>()
        val byFile = HashMap<String, String>()
        for (t in local) {
            tagKey(t)?.let { byTags.putIfAbsent(it, t.path) }
            byFile.putIfAbsent(fileKey(t), t.path)
        }
        return remote.mapNotNull { r -> (tagKey(r)?.let(byTags::get) ?: byFile[fileKey(r)])?.let { r.path to it } }.toMap()
    }

    /** Title, artist, album, number: all tagged or no key (untagged files match by name only). */
    fun tagKey(t: TrackEntity): String? {
        val title = t.title?.let(Text::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        val artist = (t.artist ?: t.albumArtist)?.let(Text::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return "$title|$artist|${t.album?.let(Text::normalize).orEmpty()}|${t.trackNumber ?: ""}"
    }

    fun fileKey(t: TrackEntity): String = t.path.substringAfterLast('/').lowercase() + "|" + t.size
}

package app.zenelo.library

import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.PlayEntity
import app.zenelo.data.db.RecentPlay
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import java.util.concurrent.TimeUnit

/**
 * The library views over the `tracks` index: albums, artists, all tracks, and the play history
 * behind "Recently played".
 */
class Library(db: ZeneloDatabase, splitter: Flow<ArtistSplitter>) {
    private val tracks = db.tracks()
    private val plays = db.plays()
    private var pruned = false

    // Indexing re-emits these after every batch of files: at most one update per interval, so a
    // library scan doesn't re-sort 7000 rows many times a second.
    val albums: Flow<List<AlbumRow>> = tracks.observeAlbums().throttleLatest(THROTTLE_MS)
    val allTracks: Flow<List<TrackEntity>> = tracks.observeAll().throttleLatest(THROTTLE_MS)

    private val splitter = splitter.distinctUntilChanged()

    val artists: Flow<List<ArtistRow>> = combine(allTracks, this.splitter, ::artistsOf).flowOn(Dispatchers.Default)

    fun albumTracks(albumKey: String): Flow<List<TrackEntity>> = tracks.observeAlbumTracks(albumKey)

    fun artistTracks(artistKey: String): Flow<List<TrackEntity>> {
        val key = artistKey(artistKey)
        return combine(tracks.observeAll(), splitter) { all, split ->
            all.filter { t -> split.split(t.artistTag).any { artistKey(it) == key } }.sortedWith(ARTIST_TRACK_ORDER)
        }.flowOn(Dispatchers.Default)
    }

    /** Latest play of each track in the last [RECENT_DAYS] days, newest first. */
    fun recent(now: Long = System.currentTimeMillis()): Flow<List<RecentPlay>> =
        plays.observeSince(now - TimeUnit.DAYS.toMillis(RECENT_DAYS))

    suspend fun recordPlay(path: String, at: Long = System.currentTimeMillis()) {
        plays.insert(PlayEntity(path = path, playedAt = at))
        // Old history is never shown: drop it once per run.
        if (!pruned) {
            pruned = true
            plays.prune(at - TimeUnit.DAYS.toMillis(KEEP_DAYS))
        }
    }

    companion object {
        /**
         * Artists by album artist (else track artist), each name of a multi-artist tag on its own:
         * "A; B" counts for A and for B. The name shown is one spelling of the key.
         */
        fun artistsOf(tracks: List<TrackEntity>, splitter: ArtistSplitter): List<ArtistRow> {
            class Acc(var name: String) {
                val albums = HashSet<String>()
                var tracks = 0
                var added = 0L
                var cover: String? = null
                var anyPath: String? = null
            }
            val byKey = LinkedHashMap<String, Acc>()
            for (t in tracks) {
                for (name in splitter.split(t.artistTag)) {
                    val acc = byKey.getOrPut(artistKey(name)) { Acc(name) }
                    if (name > acc.name) acc.name = name
                    t.albumKey?.let(acc.albums::add)
                    acc.tracks++
                    acc.added = maxOf(acc.added, t.modified)
                    if (t.hasArtwork && (acc.cover == null || t.path < acc.cover!!)) acc.cover = t.path
                    if (acc.anyPath == null || t.path < acc.anyPath!!) acc.anyPath = t.path
                }
            }
            return byKey.map { (key, a) -> ArtistRow(key, a.name, a.albums.size, a.tracks, a.added, a.cover ?: a.anyPath!!) }
        }

        private val ARTIST_TRACK_ORDER = compareBy<TrackEntity>({ it.album == null }, { it.album?.lowercase() }, { it.trackNumber == null }, { it.trackNumber }, { it.path })

        const val RECENT_DAYS = 7L
        private const val KEEP_DAYS = 90L
        private const val THROTTLE_MS = 800L

        /** When a play counts: 30 s in, or half of a track shorter than a minute. */
        fun playThresholdMs(durationMs: Long): Long =
            if (durationMs in 1 until 60_000) durationMs / 2 else 30_000
    }
}

/** The tag the artists pages group by: album artist, else track artist. */
val TrackEntity.artistTag: String? get() = albumArtist?.takeIf(String::isNotBlank) ?: artist

fun TrackEntity.toAudioFile(): AudioFile {
    val name = path.substringAfterLast('/')
    return AudioFile(path, name, extension, size, modified)
}

/** Without touching the disk: size and date aren't needed to play or queue it. */
fun RecentPlay.toAudioFile(): AudioFile {
    val name = path.substringAfterLast('/')
    return AudioFile(path, name, name.substringAfterLast('.', "").lowercase(), 0L)
}

/** Emits the first value at once, then the latest one at most every [ms]. */
fun <T> Flow<T>.throttleLatest(ms: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(ms)
}

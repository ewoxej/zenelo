package app.zenelo.library

import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.PlayEntity
import app.zenelo.data.db.RecentPlay
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.LibrarySource
import app.zenelo.mstream.MStreamPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import java.util.concurrent.TimeUnit

/**
 * The library views over the `tracks` index — local files and the mStream server's tracks,
 * merged and filtered by the chosen [LibrarySource] (see [LibraryMerge]): albums, artists, all
 * tracks, and the play history behind "Recently played".
 */
class Library(db: ZeneloDatabase, splitter: Flow<ArtistSplitter>, source: Flow<LibrarySource>, scope: CoroutineScope) {
    private val tracks = db.tracks()
    private val plays = db.plays()
    private var pruned = false
    private val source = source.distinctUntilChanged()

    // Indexing re-emits the table after every batch of files: at most one update per interval, so
    // a library scan doesn't re-sort 7000 rows many times a second. Shared: every page reads it.
    val allTracks: Flow<List<TrackEntity>> = combine(tracks.observeAll().throttleLatest(THROTTLE_MS), this.source, LibraryMerge::visible)
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val albums: Flow<List<AlbumRow>> = allTracks.map(::albumsOf).flowOn(Dispatchers.Default)

    private val splitter = splitter.distinctUntilChanged()

    val artists: Flow<List<ArtistRow>> = combine(allTracks, this.splitter, ::artistsOf).flowOn(Dispatchers.Default)

    fun albumTracks(albumKey: String): Flow<List<TrackEntity>> =
        allTracks.map { all -> all.filter { it.albumKey == albumKey }.sortedWith(ALBUM_TRACK_ORDER) }.flowOn(Dispatchers.Default)

    fun artistTracks(artistKey: String): Flow<List<TrackEntity>> {
        val key = artistKey(artistKey)
        return combine(allTracks, splitter) { all, split ->
            all.filter { t -> split.split(t.artistTag).any { artistKey(it) == key } }.sortedWith(ARTIST_TRACK_ORDER)
        }.flowOn(Dispatchers.Default)
    }

    /** Latest play of each track in the last [RECENT_DAYS] days, newest first (of the chosen source). */
    fun recent(now: Long = System.currentTimeMillis()): Flow<List<RecentPlay>> =
        combine(plays.observeSince(now - TimeUnit.DAYS.toMillis(RECENT_DAYS)), source) { list, src ->
            when (src) {
                LibrarySource.ALL -> list
                LibrarySource.LOCAL -> list.filterNot { MStreamPaths.isRemote(it.path) }
                LibrarySource.MSTREAM -> list.filter { MStreamPaths.isRemote(it.path) }
            }
        }

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
         * Albums: tracks sharing an album key (album artist + album) that have an album tag. The
         * cover comes from a track with embedded art if any; [AlbumRow.remote] when all of them
         * are on the mStream server only.
         */
        fun albumsOf(tracks: List<TrackEntity>): List<AlbumRow> =
            tracks.filter { it.albumKey != null && it.album != null }.groupBy { it.albumKey!! }.map { (key, group) ->
                AlbumRow(
                    key = key,
                    album = group.maxOf { it.album!! },
                    artist = group.mapNotNull { it.albumArtist ?: it.artist }.maxOrNull(),
                    tracks = group.size,
                    durationMs = group.sumOf { it.durationMs },
                    added = group.maxOf { it.modified },
                    coverPath = group.filter { it.hasArtwork }.minOfOrNull { it.path } ?: group.minOf { it.path },
                    remote = group.all { MStreamPaths.isRemote(it.path) },
                )
            }

        private val ALBUM_TRACK_ORDER = compareBy<TrackEntity>({ it.trackNumber == null }, { it.trackNumber }, { it.path })

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

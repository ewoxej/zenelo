package app.zenelo.mstream

import androidx.room.withTransaction
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.PendingRatingEntity
import app.zenelo.data.db.PlayEntity
import app.zenelo.data.db.PlayOutboxEntity
import app.zenelo.data.db.RemoteTrackEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.TagReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Mirrors the server's library into our index: every track as a `tracks` row under its
 * `mstream://` path (so albums, artists, search and playlists see it like a local file) plus a
 * `remote_tracks` row with what only the server knows. Skipped when the server's revision is
 * unchanged; rows the server no longer lists are dropped (unless it's mid-scan).
 */
class MStreamSync(
    private val db: ZeneloDatabase,
    private val settings: SettingsRepository,
    private val client: MStreamClient,
) {
    sealed interface State {
        data object Idle : State
        data class Running(val tracks: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Guards favorites ↔ ratings: the watcher and the sync's reconcile never interleave. */
    private val favorites = Mutex()
    private val pushing = Mutex()

    /**
     * One round with the server: send what waited (ratings, plays), then mirror its library (skipped
     * when unchanged), ratings → favorites, its playlists and recent plays. Returns the number of
     * server tracks, or null when not logged in.
     */
    /** When the last round started (0 = not in this process): the app coming to the front syncs if it's old. */
    @Volatile
    var lastSyncAt = 0L
        private set

    suspend fun sync(force: Boolean = false): Int? = mutex.withLock {
        val account = settings.settings.first().mstream ?: return null
        lastSyncAt = System.currentTimeMillis()
        _state.value = State.Running(0)
        try {
            // Only the library is required; an older server may lack the rest (404): skipped.
            optional("outbox") { pushOutbox(account) }
            pullLibrary(account, force)
            // Ratings aren't part of the library revision: always asked for.
            optional("ratings") {
                val rated = client.rated(account).mapKeys { MStreamPaths.of(it.key) }
                favorites.withLock {
                    db.withTransaction {
                        db.remoteTracks().all().forEach { rt -> if (rt.rating != rated[rt.path]) db.remoteTracks().setRating(rt.path, rated[rt.path]) }
                    }
                    reconcileFavorites()
                }
            }
            optional("playlists") { db.playlists().replaceRemote(client.playlists(account).map { (name, paths) -> name to paths.map(MStreamPaths::of) }) }
            optional("recent plays") { pullRecent(account) }
            _state.value = State.Idle
            return db.remoteTracks().paths().size
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) android.util.Log.w("Zenelo", "mStream sync failed", e)
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private suspend fun pullLibrary(account: MStreamAccount, force: Boolean) {
        val revision = if (force) null else settings.mstreamRevision()
        var page = client.manifest(account, cursor = null, revision = revision) ?: return
        val newRevision = page.revision
        val seen = HashSet<String>()
        var scanning = page.scanning
        while (true) {
            store(page.entries)
            page.entries.mapTo(seen) { MStreamPaths.of(it.filepath) }
            _state.value = State.Running(seen.size)
            val next = page.next ?: break
            page = client.manifest(account, cursor = next, revision = null) ?: break
            scanning = scanning || page.scanning
        }
        // A scan in progress can hide rows for a moment: don't take that for deletions.
        if (!scanning) {
            val gone = db.remoteTracks().paths().filter { it !in seen }
            if (gone.isNotEmpty()) {
                db.withTransaction {
                    gone.chunked(500).forEach {
                        db.tracks().delete(it)
                        db.remoteTracks().delete(it)
                    }
                }
            }
        }
        settings.setMStreamRevision(newRevision)
    }

    /**
     * Server → us: a track rated 8–10 (4–5 stars) is a favorite, others aren't — except tracks
     * whose rating we changed and couldn't send yet (our change wins until it's sent).
     */
    private suspend fun reconcileFavorites() {
        val pending = db.mstreamOutbox().ratings().mapTo(HashSet()) { it.path }
        val liked = db.favorites().all().filter { it.kind == FavoriteKind.TRACK && MStreamPaths.isRemote(it.path) }.associateBy { it.path }
        val tracks = db.remoteTracks().all()
        db.withTransaction {
            for (rt in tracks) {
                if (rt.path in pending) continue
                val serverFavorite = (rt.rating ?: 0) >= FAVORITE_RATING
                val favorite = liked[rt.path]
                if (serverFavorite && favorite == null) {
                    val t = db.tracks().get(rt.path)
                    db.favorites().insert(FavoriteEntity(rt.path, FavoriteKind.TRACK, t?.title ?: MStreamPaths.fileName(rt.path), t?.artist))
                } else if (!serverFavorite && favorite != null) {
                    db.favorites().delete(rt.path)
                }
            }
        }
    }

    /**
     * Us → server, on every change of favorites: liking a server track rates it 10 (5 stars),
     * unliking one rated 8–10 clears its rating. Sent at once when possible, else on the next sync.
     */
    suspend fun onFavoritesChanged() {
        if (settings.settings.first().mstream == null) return
        val changed = favorites.withLock {
            val liked = db.favorites().all().filter { it.kind == FavoriteKind.TRACK && MStreamPaths.isRemote(it.path) }.mapTo(HashSet()) { it.path }
            var any = false
            for (rt in db.remoteTracks().all()) {
                val serverFavorite = (rt.rating ?: 0) >= FAVORITE_RATING
                val want = when {
                    rt.path in liked && !serverFavorite -> LIKED_RATING
                    rt.path !in liked && serverFavorite -> null
                    else -> continue
                }
                db.withTransaction {
                    db.mstreamOutbox().putRating(PendingRatingEntity(rt.path, want))
                    db.remoteTracks().setRating(rt.path, want)
                }
                any = true
            }
            any
        }
        if (changed) pushSoon()
    }

    /** A counted play of a server track (see PlaybackService): reported now or on the next sync. */
    suspend fun onPlayed(path: String, playedMs: Long, durationMs: Long) {
        if (!MStreamPaths.isRemote(path)) return
        val now = System.currentTimeMillis()
        db.mstreamOutbox().putPlay(PlayOutboxEntity(UUID.randomUUID().toString(), path, now - playedMs, playedMs, durationMs))
        pushSoon()
    }

    /** Sends waiting ratings and plays; failures leave them for the next try. */
    suspend fun pushSoon() {
        val account = settings.settings.first().mstream ?: return
        runCatching { pushOutbox(account) }
    }

    private suspend fun pushOutbox(account: MStreamAccount) = pushing.withLock {
        for (r in db.mstreamOutbox().ratings()) {
            client.rate(account, MStreamPaths.serverPath(r.path), r.rating)
            db.mstreamOutbox().dropRating(r.path)
        }
        while (true) {
            val plays = db.mstreamOutbox().plays()
            if (plays.isEmpty()) break
            client.reportPlays(account, plays.map { MStreamClient.Play(it.id, MStreamPaths.serverPath(it.path), it.startedAt, it.playedMs, it.durationMs) })
            db.mstreamOutbox().dropPlays(plays.map { it.id })
        }
    }

    /** A step an older server may not have: its 404 is logged and skipped, other failures still fail the sync. */
    private suspend fun optional(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: MStreamException) {
            if (e.code != 404) throw e
            android.util.Log.w("Zenelo", "mStream sync: the server has no $what (${e.message}), skipped")
        }
    }

    /** Plays of server tracks elsewhere (web player, other devices) join Recently played. */
    private suspend fun pullRecent(account: MStreamAccount) {
        for ((serverPath, at) in client.recentlyPlayed(account, RECENT_LIMIT)) {
            val path = MStreamPaths.of(serverPath)
            // Our own plays come back too (reported above): skip what we already have.
            if (db.plays().countNear(path, at - SAME_PLAY_MS, at + SAME_PLAY_MS) == 0) {
                db.plays().insert(PlayEntity(path = path, playedAt = at))
            }
        }
    }

    /** Logged out: the server's tracks and playlists leave the library (favorites / our playlists keep their paths). */
    suspend fun forget() = mutex.withLock {
        db.withTransaction {
            db.tracks().deleteRemote()
            db.remoteTracks().deleteAll()
            db.playlists().deleteRemote()
        }
        _state.value = State.Idle
    }

    private suspend fun store(entries: List<ManifestEntry>) {
        if (entries.isEmpty()) return
        db.withTransaction {
            db.tracks().upsert(entries.map(::toTrack))
            db.remoteTracks().upsert(
                entries.map { RemoteTrackEntity(MStreamPaths.of(it.filepath), it.artFile, it.rating, it.hasLyrics || it.hasSyncedLyrics, it.hash) },
            )
        }
    }

    companion object {
        /** 8–10 of 0–10: 4–5 stars. */
        const val FAVORITE_RATING = 8
        const val LIKED_RATING = 10
        private const val RECENT_LIMIT = 100
        /** A server play this close to one of ours is the same play. */
        private const val SAME_PLAY_MS = 10 * 60 * 1000L
        private val LOSSLESS = setOf("flac", "wav", "alac", "aiff", "aif", "ape", "wv", "dsf", "dff")

        /** Our index row for a server track. Cover art isn't embedded as far as we know (see Thumbnails). */
        fun toTrack(e: ManifestEntry): TrackEntity {
            val path = MStreamPaths.of(e.filepath)
            val format = (e.format ?: path.substringAfterLast('.', "")).lowercase()
            return TrackEntity(
                path = path,
                dir = MStreamPaths.dir(path),
                modified = e.modified,
                size = e.size,
                title = e.title,
                artist = e.artistDisplay ?: e.artist,
                album = e.album,
                albumArtist = null,
                trackNumber = e.track,
                durationMs = e.durationMs,
                sampleRate = 0,
                bitsPerSample = 0,
                bitrateKbps = if (e.durationMs > 0) (e.size * 8 / e.durationMs).toInt() else 0,
                lossless = format in LOSSLESS,
                hasArtwork = false,
                trackGainDb = e.replayGainDb,
                albumGainDb = null,
                albumKey = TagReader.albumKey(e.artist, e.album),
            )
        }
    }
}

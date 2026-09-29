package app.zenelo.mstream

import androidx.room.withTransaction
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

    /** Returns the number of server tracks after the sync, or null if not logged in / unchanged. */
    suspend fun sync(force: Boolean = false): Int? = mutex.withLock {
        val account = settings.settings.first().mstream ?: return null
        _state.value = State.Running(0)
        try {
            val revision = if (force) null else settings.mstreamRevision()
            var page = client.manifest(account, cursor = null, revision = revision) ?: run {
                _state.value = State.Idle
                return null
            }
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
            _state.value = State.Idle
            return seen.size
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Logged out: the server's tracks leave the library (favorites / playlists keep their paths). */
    suspend fun forget() = mutex.withLock {
        db.withTransaction {
            db.tracks().deleteRemote()
            db.remoteTracks().deleteAll()
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

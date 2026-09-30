package app.zenelo.mstream

import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Sonic Path (`discovery/local/path`): pick a start and an end track, and the server fills the
 * way between them with tracks whose sound morphs from one to the other (its audio embeddings).
 * The ends are set from any track menu or Now Playing ("Sonic path from / to here") and the
 * screen shows the path to play, queue or save. Local tracks stand for their server twin.
 */
class MStreamSonicPath(
    private val db: ZeneloDatabase,
    private val settings: SettingsRepository,
    private val client: MStreamClient,
    private val links: ServerLinks,
    online: kotlinx.coroutines.flow.Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    /** One end: our path (what the user picked) and the server's path of it. */
    data class End(val path: String, val serverPath: String, val title: String, val artist: String?)

    data class State(
        val start: End? = null,
        val end: End? = null,
        val loading: Boolean = false,
        /** The built path (our paths: local copies where there are, else server tracks), start and end included. */
        val result: List<String>? = null,
        /** Why there's no path (not analyzed yet, discovery off, network). */
        val problem: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Logged in to a server and online: the menus offer Sonic Path / Play similar. */
    val available: StateFlow<Boolean> = combine(settings.settings.map { it.mstream != null }, online) { logged, online -> logged && online }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _open = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    /** The screen should open; the value is a message to show instead when the track can't be used. */
    val open: SharedFlow<String?> = _open.asSharedFlow()

    private var job: Job? = null

    /** Sets the start ([start]) or end of the path to [path] and opens the screen. */
    fun set(path: String, start: Boolean) {
        scope.launch {
            val all = db.tracks().observeAll().first()
            val end = endFor(path, all) ?: return@launch run { _open.tryEmit("Not on the mStream server") }
            _state.update { if (start) it.copy(start = end, result = null, problem = null) else it.copy(end = end, result = null, problem = null) }
            _open.tryEmit(null)
        }
    }

    /** Opens the screen as it is. */
    fun show() {
        _open.tryEmit(null)
    }

    fun clear(start: Boolean) = _state.update { if (start) it.copy(start = null, result = null) else it.copy(end = null, result = null) }

    fun reset() {
        job?.cancel()
        _state.value = State()
    }

    /** Asks the server for the path of [length] tracks between the two ends. */
    fun build(length: Int) {
        val s = _state.value
        val start = s.start ?: return
        val end = s.end ?: return
        job?.cancel()
        _state.update { it.copy(loading = true, result = null, problem = null) }
        job = scope.launch {
            settings.setSonicPathLength(length)
            val next = try {
                val account = settings.settings.first().mstream ?: throw MStreamException("Not connected to an mStream server")
                val path = client.sonicPath(account, start.serverPath, end.serverPath, length)
                val twins = links.current.localCopies
                when {
                    path.startNotAnalyzed -> State(start, end, problem = "The server hasn't analyzed the start track yet — wait for its scan or pick another")
                    path.endNotAnalyzed -> State(start, end, problem = "The server hasn't analyzed the end track yet — wait for its scan or pick another")
                    path.songs.isEmpty() -> State(start, end, problem = "No path found — not enough analyzed tracks between these two")
                    else -> State(start, end, result = path.songs.map { MStreamPaths.of(it.filepath).let { p -> twins[p] ?: p } })
                }
            } catch (e: MStreamException) {
                State(start, end, problem = if (e.code == 403) "Sonic Path is off on this server (no audio analysis)" else e.message)
            } catch (e: IOException) {
                State(start, end, problem = "Couldn't reach the server")
            }
            _state.value = next
        }
    }

    /**
     * "Play similar": [path] then the server's closest-sounding tracks (local copies where there
     * are). Returns them, or throws with a message to show.
     */
    suspend fun similar(path: String, limit: Int = 30): List<String> {
        val account = settings.settings.first().mstream ?: throw MStreamException("Not connected to an mStream server")
        val server = links.current.serverOf(path) ?: throw MStreamException("Not on the mStream server")
        val songs = try {
            client.similarTracks(account, MStreamPaths.serverPath(server), limit)
        } catch (e: MStreamException) {
            throw MStreamException(if (e.code == 403) "The server doesn't analyze tracks (discovery is off)" else e.message ?: "Couldn't ask the server", e.code)
        }
        if (songs.isEmpty()) throw MStreamException("Nothing sounds close enough on the server")
        val twins = links.current.localCopies
        return listOf(path) + songs.map { MStreamPaths.of(it.filepath).let { p -> twins[p] ?: p } }.filter { it != path }
    }

    /** [similar], played (replacing the queue); a problem shows as a message. */
    fun playSimilar(path: String, play: (List<app.zenelo.library.AudioFile>) -> Unit) {
        scope.launch {
            try {
                val paths = similar(path)
                android.util.Log.i("Zenelo", "play similar to ${path.substringAfterLast('/')}: ${paths.size} tracks")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { play(paths.map(app.zenelo.library.AudioFile::forPath)) }
            } catch (e: IOException) {
                android.util.Log.w("Zenelo", "play similar failed", e)
                _open.tryEmit(e.message ?: "Couldn't reach the server")
            }
        }
    }

    private fun endFor(path: String, all: List<TrackEntity>): End? {
        val serverPath = links.current.serverOf(path) ?: return null
        val track = all.firstOrNull { it.path == path }
        return End(path, MStreamPaths.serverPath(serverPath), track?.title ?: path.substringAfterLast('/'), track?.artist ?: track?.albumArtist)
    }
}

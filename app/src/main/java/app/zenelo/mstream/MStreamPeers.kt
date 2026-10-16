package app.zenelo.mstream

import android.util.Log
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.AudioFile
import app.zenelo.library.DirListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The servers ours is federated with, and Discover's jobs. A peer is browsed in the folder browser
 * (`/mstream/@peer/<id>/…`, listed live through our server — never indexed) and its tracks play
 * through our server's stream proxy ([MStreamPaths.isPeer] paths). Their tags live here only, in
 * memory ([info]), filled from the listings and Discover's leads.
 *
 * "Add to my library" is the server's `federation-copy` plug-in: the song, its album, its artist or
 * a whole folder copied into one of our libraries as a job; [jobs] follows them while any runs, and
 * a finished copy syncs the library so the songs show up here.
 */
class MStreamPeers(
    private val client: MStreamClient,
    private val settings: SettingsRepository,
    private val online: Flow<Boolean>,
    private val scope: CoroutineScope,
    /** A copy landed in our library: sync it. */
    private val onCopied: () -> Unit,
) {
    private val _features = MutableStateFlow(ServerFeatures())
    val features: StateFlow<ServerFeatures> = _features.asStateFlow()

    private val _peers = MutableStateFlow<List<FederatedPeer>>(emptyList())

    /** The paired servers to browse (logged in, online, and the server federates). */
    val peers: StateFlow<List<FederatedPeer>> = _peers.asStateFlow()

    private val _plugins = MutableStateFlow<List<DiscoveryPlugin>>(emptyList())

    /** Plug-ins whose jobs this account may start ("Get it", copies). */
    val plugins: StateFlow<List<DiscoveryPlugin>> = _plugins.asStateFlow()

    private val _jobs = MutableStateFlow<List<DiscoverJob>>(emptyList())
    val jobs: StateFlow<List<DiscoverJob>> = _jobs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val catalog = ConcurrentHashMap<String, TrackEntity>()
    private val artFiles = ConcurrentHashMap<String, String>()
    private var pollJob: Job? = null

    fun start() {
        scope.launch {
            combine(settings.settings.map { it.mstream }.distinctUntilChanged(), online) { a, on -> a.takeIf { on } }
                .distinctUntilChanged()
                .collect { account ->
                    if (account == null) {
                        _peers.value = emptyList()
                    } else {
                        refresh()
                    }
                }
        }
    }

    /** Asks the server again what it can do, its peers and plug-ins (Discover calls it when shown). */
    suspend fun refresh() {
        val account = settings.settings.first().mstream ?: return
        val f = try {
            client.features(account)
        } catch (e: IOException) {
            Log.i(TAG, "peers: features: ${e.message}")
            return
        }
        _features.value = f
        _peers.value = if (f.browsePeers) runCatching { client.peers(account) }.getOrDefault(_peers.value) else emptyList()
        if (f.plugins) {
            runCatching { client.discoveryPlugins(account) }.getOrNull()?.let { (list, allowed) ->
                _plugins.value = if (allowed) list.filter { it.acquires } else emptyList()
            }
            refreshJobs()
        } else {
            _plugins.value = emptyList()
        }
    }

    fun peerName(id: Long?): String? = _peers.value.firstOrNull { it.id == id }?.name

    // ── Tracks ─────────────────────────────────────────────────────────

    /** The tags of a peer track the app has seen (browsed, or a Discover lead). */
    fun info(path: String): TrackEntity? = catalog[path]

    /** A peer track's cover from its server, null when it has none. */
    suspend fun art(path: String): ByteArray? {
        val id = MStreamPaths.peerId(path) ?: return null
        val file = artFiles[path] ?: return null
        val account = settings.settings.first().mstream ?: return null
        return client.peerArt(account, id, file)
    }

    fun streamUrl(account: MStreamAccount, path: String) =
        client.peerStreamUrl(account, MStreamPaths.peerId(path) ?: throw IOException("Not a peer's track"), MStreamPaths.peerPath(path))

    /** Remembers a lead's tags; returns its path, null when it isn't a peer's (a network lead). */
    fun remember(lead: Lead): String? {
        val id = lead.peerId ?: return null
        val peerPath = lead.filepath ?: return null
        val path = MStreamPaths.peer(id, peerPath)
        catalog.putIfAbsent(path, entity(path, lead.title, lead.artist, lead.album, null, ((lead.durationSec ?: 0.0) * 1000).toLong(), 0L, null))
        return path
    }

    // ── Browsing ───────────────────────────────────────────────────────

    /** One folder of a peer, for the browser (its root lists the peer's libraries). Throws when it can't be reached. */
    suspend fun list(dir: File): DirListing {
        val index = RemoteFolders.indexDir(dir)
        val id = MStreamPaths.peerId(index) ?: throw IOException("Not a peer's folder")
        val account = settings.settings.first().mstream ?: throw IOException("Not connected to an mStream server")
        val peerDir = MStreamPaths.peerPath(index)
        val (folders, files) = client.peerFolder(account, id, peerDir)
        val audio = files.map { (name, row) ->
            val path = MStreamPaths.peer(id, if (peerDir.isEmpty()) name else "$peerDir/$name")
            row?.let { remember(path, it) }
            AudioFile(path, name, name.substringAfterLast('.', "").lowercase(), row?.optJSONObject("metadata")?.optLong("file-size") ?: 0L, 0L)
        }
        return DirListing(dir = dir, stamp = 0, folders = folders.map { File(dir, it) }, files = audio)
    }

    /** Every track under a peer's folder, with their tags. */
    suspend fun listRecursive(dir: File): List<AudioFile> {
        val index = RemoteFolders.indexDir(dir)
        val id = MStreamPaths.peerId(index) ?: return emptyList()
        val account = settings.settings.first().mstream ?: throw IOException("Not connected to an mStream server")
        val peerDir = MStreamPaths.peerPath(index)
        val paths = if (peerDir.isEmpty()) {
            client.peerFolder(account, id, "").first.flatMap { client.peerFolderFiles(account, id, it) }
        } else {
            client.peerFolderFiles(account, id, peerDir)
        }
        paths.filter { !catalog.containsKey(MStreamPaths.peer(id, it)) }.chunked(200).forEach { chunk ->
            runCatching { client.peerMetadata(account, id, chunk) }.getOrNull()?.forEach { (p, row) -> remember(MStreamPaths.peer(id, p), row) }
        }
        return paths.map { p ->
            val path = MStreamPaths.peer(id, p)
            val name = p.substringAfterLast('/')
            AudioFile(path, name, name.substringAfterLast('.', "").lowercase(), catalog[path]?.size ?: 0L)
        }
    }

    private fun remember(path: String, row: JSONObject) {
        val m = row.optJSONObject("metadata") ?: return
        fun str(key: String) = if (m.isNull(key)) null else m.optString(key).takeIf { it.isNotBlank() }
        val art = str("album-art")
        if (art != null) artFiles[path] = art
        catalog[path] = entity(
            path,
            title = str("title"),
            artist = str("artist") ?: str("artist-display"),
            album = str("album"),
            track = if (m.isNull("track") || !m.has("track")) null else m.optInt("track"),
            durationMs = (m.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0).times(1000).toLong(),
            size = m.optLong("file-size", 0L),
            art = art,
            sampleRate = m.optInt("sample-rate", 0),
            bitrate = m.optInt("bitrate", 0) / 1000,
            genre = m.optJSONArray("genres")?.let { a -> List(a.length()) { a.optString(it) }.filter(String::isNotBlank).joinToString("; ") }?.ifBlank { null },
        )
    }

    private fun entity(
        path: String,
        title: String?,
        artist: String?,
        album: String?,
        track: Int?,
        durationMs: Long,
        size: Long,
        art: String?,
        sampleRate: Int = 0,
        bitrate: Int = 0,
        genre: String? = null,
    ) = TrackEntity(
        path = path,
        dir = MStreamPaths.dir(path),
        modified = 0L,
        size = size,
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = track,
        durationMs = durationMs,
        sampleRate = sampleRate,
        bitsPerSample = 0,
        bitrateKbps = bitrate,
        lossless = false,
        hasArtwork = false,
        trackGainDb = null,
        albumGainDb = null,
        // The peer's art file: one cover per album there, cached under it.
        albumKey = art?.let { "peer:${MStreamPaths.peerId(path)}:$it" },
        genre = genre,
    )

    // ── Into our library (plug-in jobs) ────────────────────────────────

    /** The server copies peers' songs into our library ("federation-copy" offered to this account). */
    val canCopy: Boolean get() = _plugins.value.any { it.name == COPY }

    fun copyScopes(): List<String> = _plugins.value.firstOrNull { it.name == COPY }?.scopes.orEmpty()

    /**
     * Copies a peer's track ([scope] null = the song, "album", "artist") or folder ([folder]) into
     * our library. The outcome comes as a message when the job ends. [tags]: what the player knows
     * of a track the app no longer has the tags of (a queue restored after a restart).
     */
    fun copy(path: String, scope: String? = null, folder: Boolean = false, tags: Triple<String?, String?, String?>? = null) {
        val id = MStreamPaths.peerId(path) ?: return
        val t = catalog[path] ?: tags?.let { (title, artist, album) -> entity(path, title, artist, album, null, 0L, 0L, null) }
        val rec = JSONObject()
            .put("source", "federation")
            .put("filepath", MStreamPaths.peerPath(path))
            .put("peer", JSONObject().put("id", id).put("name", peerName(id) ?: JSONObject.NULL))
        if (!folder && t != null) {
            t.title?.let { rec.put("title", it) }
            t.artist?.let { rec.put("artist", it) }
            t.album?.let { rec.put("album", it) }
            if (t.durationMs > 0) rec.put("duration", t.durationMs / 1000.0)
        }
        start(COPY, rec, if (folder) "folder" else scope)
    }

    /** A plug-in's job for a Discover lead (a copy of a peer's song, a network lead's download). */
    fun get(plugin: String, lead: Lead, scope: String? = null) = start(plugin, lead.raw, scope)

    private fun start(plugin: String, recommendation: JSONObject, jobScope: String?) {
        scope.launch {
            val account = settings.settings.first().mstream ?: return@launch
            try {
                val job = client.startJob(account, plugin, recommendation, jobScope)
                _jobs.value = listOf(job) + _jobs.value.filter { it.id != job.id }
                _messages.tryEmit(if (job.live) "Getting ${job.title}…" else "${job.title}: already done")
                poll()
            } catch (e: IOException) {
                _messages.tryEmit(e.message ?: "Couldn't reach the server")
            }
        }
    }

    fun cancel(job: DiscoverJob) {
        scope.launch {
            val account = settings.settings.first().mstream ?: return@launch
            runCatching { client.cancelJob(account, job.id) }
            refreshJobs()
        }
    }

    /** Drops the finished jobs from the list (the server's "Clear finished"). */
    fun clearFinished() {
        scope.launch {
            val account = settings.settings.first().mstream ?: return@launch
            runCatching { client.clearJobs(account) }
            refreshJobs()
        }
    }

    private suspend fun refreshJobs() {
        val account = settings.settings.first().mstream ?: return
        val fresh = runCatching { client.jobs(account) }.getOrNull() ?: return
        val before = _jobs.value.associateBy { it.id }
        _jobs.value = fresh
        var copied = false
        for (job in fresh) {
            val was = before[job.id] ?: continue
            if (!was.live || job.live) continue
            when (job.state) {
                "done" -> {
                    copied = true
                    _messages.tryEmit("In your library · ${job.title}")
                }
                "failed" -> _messages.tryEmit("Couldn't get ${job.title}" + (job.error?.let { ": $it" } ?: ""))
                "cancelled" -> _messages.tryEmit("Stopped · ${job.title}")
            }
        }
        if (copied) onCopied()
        if (fresh.any { it.live }) poll()
    }

    /** Follows the jobs while any runs. */
    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            delay(POLL_MS)
            pollJob = null
            refreshJobs()
        }
    }

    companion object {
        const val COPY = "federation-copy"
        private const val POLL_MS = 2000L
        private const val TAG = "Zenelo"
    }
}

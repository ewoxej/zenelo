package app.zenelo.mstream

import androidx.room.withTransaction
import app.zenelo.data.db.ServerLinkEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.library.LibraryMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Which local files are also on the mStream server (`server_links`): downloads, and the user's own
 * files that match a server track (`LibraryMerge.twins`). Such a file counts as downloaded: it
 * carries the download mark, and everything the server does for its track — favorites ↔ ratings,
 * plays, now playing, Sonic Path, Play similar, server playlists — works on it; the other way, a
 * server track with a local copy plays that copy. A link stays once found — fixing a tag doesn't
 * undo it — until either side leaves the index or the file stops being the same song
 * (`LibraryMerge.stillSame`); downloads stay until the file is gone.
 */
class ServerLinks(private val db: ZeneloDatabase, private val scope: CoroutineScope) {
    class Links(rows: List<ServerLinkEntity>) {
        private val toServer: Map<String, String> = rows.associate { it.localPath to it.serverPath }
        private val toLocal: Map<String, String> = rows.associate { it.serverPath to it.localPath }

        /** The server's track for [path]: itself for a server track, its link for a local file. */
        fun serverOf(path: String?): String? = when {
            path == null -> null
            MStreamPaths.isRemote(path) -> path
            else -> toServer[path]
        }

        /** The local copy of a server track, if any. */
        fun localOf(serverPath: String?): String? = serverPath?.let(toLocal::get)

        /** The path to show / play / keep for [path]: the local copy of a server track where there is one. */
        fun canonical(path: String): String = toLocal[path] ?: path

        /** A local file with a server copy, or a server track with a local one: shows the download mark. */
        fun isLinked(path: String?): Boolean = path != null && (path in toServer || path in toLocal)

        /** The same track under its other path: the server track of a local copy, or the local copy of a server track. */
        fun copiesOf(path: String?): List<String> = listOfNotNull(path?.let(toServer::get), path?.let(toLocal::get))

        /** Server track → local copy. */
        val localCopies: Map<String, String> get() = toLocal

        val isEmpty: Boolean get() = toServer.isEmpty()
    }

    private val _links = MutableStateFlow(Links(emptyList()))
    val links: StateFlow<Links> = _links.asStateFlow()

    val current: Links get() = _links.value

    /** Where "Download" saves (pushed from settings): files in it with a server path are downloads. */
    @Volatile
    var downloadDir: File = MStreamFiles.defaultDownloadDir()
        set(value) {
            if (field == value) return
            field = value
            scope.launch { refresh() }
        }

    private val mutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { db.serverLinks().observeAll().collect { _links.value = Links(it) } }
        // A cheap trigger, not the rows: reading 7000 tracks on every indexing batch would compete with the UI.
        scope.launch { db.tracks().observeChanges().debounce(2_000).conflate().collect { refresh() } }
    }

    /** Finds new links and drops those whose file or server track is gone. */
    suspend fun refresh(all: List<TrackEntity>? = null) = mutex.withLock {
        val tracks = all ?: db.tracks().all()
        val byPath = tracks.associateBy { it.path }
        val paths = byPath.keys
        val existing = db.serverLinks().all()
        // A download may not be indexed yet: its file is enough. A matched file must still be the
        // same song (a wrong match, or a file retagged as another song, is dropped and matched again).
        val gone = existing.filter { link ->
            val server = byPath[link.serverPath]
            val local = byPath[link.localPath]
            when {
                server == null -> true
                link.download -> local == null && !File(link.localPath).isFile
                else -> local == null || !LibraryMerge.stillSame(local, server)
            }
        }
        val linkedLocal = HashSet<String>()
        val linkedServer = HashSet<String>()
        existing.filterNot { it in gone }.forEach {
            linkedLocal.add(it.localPath)
            linkedServer.add(it.serverPath)
        }
        val added = ArrayList<ServerLinkEntity>()
        fun add(local: String, server: String, download: Boolean) {
            if (local in linkedLocal || server in linkedServer) return
            linkedLocal.add(local)
            linkedServer.add(server)
            added.add(ServerLinkEntity(local, server, download))
        }
        // Files in the download folder at the server's path (downloaded before links were kept, or copied in by hand).
        val dir = downloadDir.path + "/"
        for (t in tracks) {
            if (MStreamPaths.isRemote(t.path) || !t.path.startsWith(dir)) continue
            val server = MStreamPaths.of(t.path.removePrefix(dir))
            if (server in paths) add(t.path, server, download = true)
        }
        for ((server, local) in LibraryMerge.twins(tracks)) add(local, server, download = false)
        if (gone.isEmpty() && added.isEmpty()) return@withLock
        db.withTransaction {
            if (gone.isNotEmpty()) db.serverLinks().delete(gone.map { it.localPath })
            if (added.isNotEmpty()) db.serverLinks().upsert(added)
        }
    }

    /** "Download" saved [serverPath] as [file]: linked at once (before it's indexed). */
    suspend fun onDownloaded(file: File, serverPath: String) = mutex.withLock {
        db.withTransaction {
            db.serverLinks().deleteServer(serverPath)
            db.serverLinks().upsert(listOf(ServerLinkEntity(file.path, serverPath, download = true)))
        }
    }

    /** Local files were deleted: their server tracks lose the mark at once. */
    fun forgetDeleted(localPaths: List<String>) {
        if (localPaths.isEmpty()) return
        scope.launch { mutex.withLock { db.serverLinks().delete(localPaths) } }
    }

    /** Logged out. */
    suspend fun clear() = mutex.withLock { db.serverLinks().deleteAll() }
}

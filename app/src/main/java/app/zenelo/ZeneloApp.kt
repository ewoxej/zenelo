package app.zenelo

import android.app.Application
import android.content.Context
import app.zenelo.data.Backup
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.FileSystemBrowser
import app.zenelo.library.PlaylistFiles
import app.zenelo.mstream.MStreamAutoDj
import app.zenelo.mstream.MStreamClient
import app.zenelo.mstream.MStreamSonicPath
import app.zenelo.mstream.MStreamDownloads
import app.zenelo.mstream.MStreamFiles
import app.zenelo.data.settings.effectiveScanScope
import app.zenelo.mstream.MStreamSync
import app.zenelo.mstream.ServerLinks
import app.zenelo.library.Library
import app.zenelo.library.LibraryIndexer
import app.zenelo.library.LoudnessRepository
import app.zenelo.library.MetadataFetcher
import app.zenelo.library.PendingWrites
import app.zenelo.library.TagWriter
import app.zenelo.library.Thumbnails
import app.zenelo.online.CoverSources
import app.zenelo.online.Http
import app.zenelo.online.LrcLib
import app.zenelo.player.PlayQueue
import app.zenelo.player.PlaylistPicker
import app.zenelo.player.PlayerController
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class ZeneloApp : Application() {
    /** The database predates this start (checked before anything opens it): not a fresh install. */
    private var upgradedInstall = false
    val container by lazy { AppContainer(this, upgradedInstall) }

    override fun onCreate() {
        super.onCreate()
        upgradedInstall = getDatabasePath(ZeneloDatabase.NAME).exists()
        LibraryWork.schedule(this)
        // Writes that waited for a track to end when the app was killed: nothing plays yet, apply them.
        container.appScope.launch { container.pendingWrites.flush() }
    }
}

/** Manual DI: app-wide singletons. */
class AppContainer(context: Context, upgradedInstall: Boolean) {
    val db = ZeneloDatabase.create(context)
    val settings = SettingsRepository(context, upgradedInstall)
    val fileBrowser = FileSystemBrowser(context, db.tracks())
    /** Path of the track the service is playing; files in use are never rewritten. */
    val nowPlaying = MutableStateFlow<String?>(null)

    /** The player's audio session while the playback service runs (0: none), for the visualizer. */
    val audioSession = MutableStateFlow(0)

    /** The visualizer is open (full screen over the whole app, drawn by the root). */
    val visualizerOpen = MutableStateFlow(false)

    /**
     * Cover found for the playing track while it plays (download or manual pick): shown by the UI
     * right away, without touching the player's items, which would interrupt playback.
     */
    val coverOverride = MutableStateFlow<Pair<String, String>?>(null)

    /** Messages from playback for the UI ("Can't play … — skipped"). */
    val playbackMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Work that must outlive screens and the service (e.g. writing files once a track ends). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + app.zenelo.player.logUncaught("app"))

    val http = Http(context)
    val mstream = MStreamClient(http.client)
    /** Sign-in through the server's OIDC provider (Authelia, …): the browser, then zenelo://oidc. */
    val oidc = app.zenelo.mstream.MStreamOidc(context, mstream, settings, playbackMessages, appScope)
    val indexer = LibraryIndexer(db)

    /** Tag / cover writes; the playing file's wait for its track to end but show up everywhere at once. */
    val pendingWrites = PendingWrites(db, indexer, nowPlaying) { path, cover -> showChange(path, cover) }

    val metadata = MetadataFetcher(
        context = context,
        db = db,
        http = http,
        coverSources = CoverSources(http) { settings.settings.first().lastFmApiKey },
        lrcLib = LrcLib(http),
        settings = settings,
        pendingWrites = pendingWrites,
        mstream = mstream,
    )
    val thumbnails = Thumbnails(context, db.tracks(), metadata)
    val loudness = LoudnessRepository(db)
    val network = app.zenelo.online.Network(context)
    val hidden = app.zenelo.library.HiddenTracks(db, appScope)

    /** Every favorite's path (tracks, folders, albums, artists): which rows can still be liked. */
    val favoritePaths: kotlinx.coroutines.flow.StateFlow<Set<String>> = db.favorites().observeAll()
        .map { list -> list.mapTo(HashSet()) { it.path } }
        .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    val library = Library(
        db, settings.settings.map { it.artistSplitter }, settings.settings.map { it.librarySource }, appScope,
        online = network.online, isCached = { mstreamFiles.isCached(it) },
        scanScope = settings.settings.map { it.effectiveScanScope() },
        hidden = hidden.current,
    )
    val queue = PlayQueue(db.tracks(), db.plays(), settings.settings, metadata, File(context.filesDir, "queue.txt"))
    val tagWriter = TagWriter(pendingWrites)
    val playlistPicker = PlaylistPicker()
    val playlistFiles = PlaylistFiles(context, db)
    val backup = Backup(context, db, settings)
    val serverLinks = ServerLinks(db, appScope).also { it.start() }
    val mstreamFiles = MStreamFiles(context, http.client, serverLinks)
    val mstreamSync = MStreamSync(db, settings, mstream, serverLinks)
    val mstreamDownloads = MStreamDownloads(context, db, settings, mstream, mstreamFiles, indexer, queue, serverLinks, appScope).also { it.start() }
    val autoDj = MStreamAutoDj(db, settings, mstream, queue, serverLinks, network.online, appScope).also { it.start() }
    val sonicPath = MStreamSonicPath(db, settings, mstream, serverLinks, network.online, appScope)
    /** Federated peers (browsed, played through our server) and Discover's plug-in jobs. */
    val peers = app.zenelo.mstream.MStreamPeers(mstream, settings, network.online, appScope) {
        app.zenelo.work.LibraryWork.syncServer(context, force = true)
    }.also { it.start() }

    init {
        fileBrowser.hidden = { hidden.current.value }
        fileBrowser.copies = { serverLinks.current.copiesOf(it) }
        fileBrowser.peers = peers
        // Peers' tracks aren't indexed: their tags and covers come from what was browsed.
        queue.peerInfo = peers::info
        thumbnails.peerInfo = peers::info
        metadata.peerArt = peers::art
        autoDj.hidden = { hidden.current.value }
        // Likes of server tracks become ratings on the server (and unlikes clear them).
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        appScope.launch { db.favorites().observeAll().debounce(300).collect { mstreamSync.onFavoritesChanged() } }
        // A new download / matched file: favorites move to the local copy, its likes reach the server.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        appScope.launch { serverLinks.links.drop(1).debounce(500).collect { mstreamSync.onLinksChanged() } }
        queue.resolvePath = { serverLinks.current.canonical(it) }
        // Edited server playlists go to the server (or wait for the next sync when it's out of reach).
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        appScope.launch { db.playlists().observePendingRemote().debounce(1000).collect { if (it > 0) mstreamSync.onPlaylistsChanged() } }
        appScope.launch {
            settings.settings.map { it.downloadDir }.distinctUntilChanged().collect { dir ->
                mstreamFiles.setDownloadDir(dir?.let(::File) ?: MStreamFiles.defaultDownloadDir())
            }
        }
    }
    val player = PlayerController(context, queue, coverOverride)
    /** This phone as a playback device of the mStream account (started by PlaybackService). */
    val devices = app.zenelo.mstream.MStreamDevices(context, http.client, db, settings.settings, queue, serverLinks, network.online, appScope).also { d ->
        player.attachDevices(d)
        d.setAutoDj = { on -> autoDj.setEnabled(on) { player.playFiles(it) } }
    }

    /** Refreshes the queue / Now Playing (tags) and the thumbnails / Now Playing cover after a write. */
    private fun showChange(path: String, cover: File?) {
        queue.invalidate(path)
        if (cover != null) {
            thumbnails.override(path, cover)
            if (path == nowPlaying.value) coverOverride.value = path to cover.absolutePath
        }
    }
}

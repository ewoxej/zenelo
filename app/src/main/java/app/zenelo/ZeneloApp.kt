package app.zenelo

import android.app.Application
import android.content.Context
import app.zenelo.data.Backup
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.FileSystemBrowser
import app.zenelo.library.PlaylistFiles
import app.zenelo.mstream.MStreamClient
import app.zenelo.mstream.MStreamSync
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val fileBrowser = FileSystemBrowser(context)
    /** Path of the track the service is playing; files in use are never rewritten. */
    val nowPlaying = MutableStateFlow<String?>(null)

    /**
     * Cover found for the playing track while it plays (download or manual pick): shown by the UI
     * right away, without touching the player's items, which would interrupt playback.
     */
    val coverOverride = MutableStateFlow<Pair<String, String>?>(null)

    /** Work that must outlive screens and the service (e.g. writing files once a track ends). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val http = Http(context)
    val mstream = MStreamClient(http.client)
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
    val library = Library(db, settings.settings.map { it.artistSplitter }, settings.settings.map { it.librarySource }, appScope)
    val queue = PlayQueue(db.tracks(), db.plays(), settings.settings, metadata, File(context.filesDir, "queue.txt"))
    val tagWriter = TagWriter(pendingWrites)
    val playlistPicker = PlaylistPicker()
    val playlistFiles = PlaylistFiles(context, db)
    val backup = Backup(context, db, settings)
    val mstreamSync = MStreamSync(db, settings, mstream)
    val player = PlayerController(context, queue, coverOverride)

    /** Refreshes the queue / Now Playing (tags) and the thumbnails / Now Playing cover after a write. */
    private fun showChange(path: String, cover: File?) {
        queue.invalidate(path)
        if (cover != null) {
            thumbnails.override(path, cover)
            if (path == nowPlaying.value) coverOverride.value = path to cover.absolutePath
        }
    }
}

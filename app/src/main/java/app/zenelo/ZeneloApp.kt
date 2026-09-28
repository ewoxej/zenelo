package app.zenelo

import android.app.Application
import android.content.Context
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.FileSystemBrowser
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
import app.zenelo.player.PlayerController
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ZeneloApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        LibraryWork.schedule(this)
        // Writes that waited for a track to end when the app was killed: nothing plays yet, apply them.
        container.appScope.launch { container.pendingWrites.flush() }
    }
}

/** Manual DI: app-wide singletons. */
class AppContainer(context: Context) {
    val db = ZeneloDatabase.create(context)
    val settings = SettingsRepository(context)
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
    )
    val thumbnails = Thumbnails(context, db.tracks(), metadata)
    val loudness = LoudnessRepository(db)
    val queue = PlayQueue(db.tracks(), metadata, File(context.filesDir, "queue.txt"))
    val tagWriter = TagWriter(pendingWrites)
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

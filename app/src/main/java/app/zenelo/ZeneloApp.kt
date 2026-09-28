package app.zenelo

import android.app.Application
import android.content.Context
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.FileSystemBrowser
import app.zenelo.library.LibraryIndexer
import app.zenelo.library.MetadataFetcher
import app.zenelo.online.CoverSources
import app.zenelo.online.Http
import app.zenelo.online.LrcLib
import app.zenelo.player.PlayQueue
import app.zenelo.player.PlayerController
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class ZeneloApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        LibraryWork.schedule(this)
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

    val http = Http(context)
    val indexer = LibraryIndexer(db)
    val metadata = MetadataFetcher(
        context = context,
        db = db,
        http = http,
        coverSources = CoverSources(http) { settings.settings.first().lastFmApiKey },
        lrcLib = LrcLib(http),
        settings = settings,
        indexer = indexer,
        nowPlaying = nowPlaying,
    )
    val queue = PlayQueue(db.tracks(), metadata)
    val player = PlayerController(context, queue, coverOverride)
}

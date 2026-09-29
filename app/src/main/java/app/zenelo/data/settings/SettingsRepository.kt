package app.zenelo.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.zenelo.library.ArtistSplitter
import app.zenelo.mstream.MStreamAccount
import app.zenelo.player.ShuffleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class SwipeAction(val label: String) {
    NONE("Nothing"),
    ADD_TO_QUEUE("Add to queue"),
    PLAY_NEXT("Play next"),
    FAVORITE("Add to favorites"),
    ADD_TO_PLAYLIST("Add to playlist"),
    REMOVE_FROM_LIST("Remove from list"),
    HIDE("Hide"),
    DELETE_FILE("Delete file"),
}

enum class SwipeSlot(val label: String, val default: SwipeAction) {
    RIGHT_SHORT("SWIPE RIGHT · SHORT", SwipeAction.ADD_TO_QUEUE),
    RIGHT_LONG("SWIPE RIGHT · LONG", SwipeAction.PLAY_NEXT),
    LEFT_SHORT("SWIPE LEFT · SHORT", SwipeAction.FAVORITE),
    LEFT_LONG("SWIPE LEFT · LONG", SwipeAction.REMOVE_FROM_LIST),
}

/** Left swipes on queue rows (right swipes belong to the lyrics / cover / queue pager). */
enum class QueueSwipeAction(val label: String) {
    NONE("Nothing"),
    REMOVE("Remove from queue"),
    PLAY_NEXT("Play next"),
    MOVE_TO_END("Move to end"),
    FAVORITE("Add to favorites"),
}

enum class QueueSwipeSlot(val label: String, val slot: SwipeSlot, val default: QueueSwipeAction) {
    LEFT_SHORT("SWIPE LEFT · SHORT", SwipeSlot.LEFT_SHORT, QueueSwipeAction.REMOVE),
    LEFT_LONG("SWIPE LEFT · LONG", SwipeSlot.LEFT_LONG, QueueSwipeAction.PLAY_NEXT),
}

enum class NormalizationMode { OFF, TRACK, ALBUM }

/** Where a downward swipe collapses Now Playing: a share of the screen height from the top. */
enum class PullDownArea(val label: String, val fraction: Float) {
    TOP_BAR("Top bar only", 0f),
    TOP_THIRD("Top third", 1f / 3),
    TOP_HALF("Top half", 0.5f),
    WHOLE_SCREEN("Whole screen", 1f),
}

/** Which side of a row the selection check mark sits on (file list and queue). */
enum class SelectionMarkerSide(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
}

/** Which tracks the library pages show (the page title's menu). */
enum class LibrarySource(val label: String) {
    ALL("All"),
    LOCAL("Local"),
    MSTREAM("mStream"),
}

/** Default file name patterns for covers / lyrics lookups when tags are missing or wrong. */
val DEFAULT_FILENAME_PATTERNS = listOf(
    "[%number%[.] ][- ]%artist% - %title%",
    "%artist%/%album%/[%number%[.] ][- ]%title%",
)

data class ZeneloSettings(
    val homeFolder: String? = null,
    val swipes: Map<SwipeSlot, SwipeAction> = SwipeSlot.entries.associateWith { it.default },
    /** Ask before destructive swipe actions instead of showing an undo snackbar. */
    val confirmRemove: Boolean = false,
    val normalization: NormalizationMode = NormalizationMode.TRACK,
    /** Added to the normalization gain (dB), −6…+6; still bounded by the peak. */
    val preampDb: Float = 0f,
    val crossfadeMs: Int = 0,
    /** Covers and lyrics from the internet; always Wi-Fi only. */
    val onlineFetch: Boolean = true,
    /** Write downloaded covers into files that have none (and on manual "Download cover"). */
    val embedCovers: Boolean = true,
    /** Optional Last.fm API key: adds Last.fm as a cover source. Key-less sources are used either way. */
    val lastFmApiKey: String? = null,
    /** Show already played tracks above the current one in the queue. */
    val queueHistory: Boolean = true,
    val queueSwipes: Map<QueueSwipeSlot, QueueSwipeAction> = QueueSwipeSlot.entries.associateWith { it.default },
    /** Tried in order when tags don't find covers / lyrics. See [app.zenelo.library.FilenamePattern]. */
    val filenamePatterns: List<String> = DEFAULT_FILENAME_PATTERNS,
    val sorts: Map<SortPage, SortOrder> = SortPage.entries.associateWith { it.default },
    /** Grid / list mode of the library pages that have one. */
    val albumsView: LibraryView = LibraryView.GRID2,
    val artistsView: LibraryView = LibraryView.LIST,
    /** Bottom bar tabs, in order (at most [Navigation.MAX_TABS]; empty = no bar, Home only). */
    val tabs: List<Section> = Navigation.DEFAULT_TABS,
    /** Home sections in order, every section present (see [Navigation.completeHome]). */
    val home: List<HomeItem> = Navigation.DEFAULT_HOME,
    /** The tab open when the app was last used. */
    val lastTab: Section? = null,
    val pullDownArea: PullDownArea = PullDownArea.TOP_THIRD,
    val selectionMarker: SelectionMarkerSide = SelectionMarkerSide.RIGHT,
    val shuffleMode: ShuffleMode = ShuffleMode.TRACKS,
    /** Split multi-artist tags ("A; B") on these; empty = never split. See [ArtistSplitter]. */
    val artistSeparators: List<String> = ArtistSplitter.DEFAULT_SEPARATORS,
    /** Artist names never split although they contain a separator ("AC/DC"). */
    val artistExceptions: List<String> = ArtistSplitter.DEFAULT_EXCEPTIONS,
    /** The mStream server we're logged in to, if any. */
    val mstream: MStreamAccount? = null,
    val librarySource: LibrarySource = LibrarySource.ALL,
) {
    val artistSplitter: ArtistSplitter get() = ArtistSplitter(artistSeparators, artistExceptions)
}

/**
 * Settings (DataStore "settings"). [upgradedInstall]: the app was installed before this version
 * (its database already existed at startup), see [FirstNavigation].
 */
class SettingsRepository(context: Context, upgradedInstall: Boolean) {
    private val store = PreferenceDataStoreFactory.create(migrations = listOf(FirstNavigation(upgradedInstall))) {
        context.applicationContext.preferencesDataStoreFile("settings")
    }

    val settings: Flow<ZeneloSettings> = store.data.map { it.toSettings() }

    /** Every stored value by key, for backups; the server login (token) stays out of them. */
    suspend fun snapshot(): Map<String, Any> =
        store.data.first().asMap().mapKeys { it.key.name }.filterKeys { it !in PRIVATE_KEYS }

    /** Replaces all settings with a [snapshot]'s values (the server login is kept). */
    suspend fun restoreSnapshot(values: Map<String, Any>) = store.edit { prefs ->
        val kept = prefs.asMap().filterKeys { it.name in PRIVATE_KEYS }
        prefs.clear()
        @Suppress("UNCHECKED_CAST")
        kept.forEach { (key, value) -> prefs[key as Preferences.Key<Any>] = value }
        values.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is String -> prefs[stringPreferencesKey(key)] = value
                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                is Int -> prefs[intPreferencesKey(key)] = value
                is Long -> prefs[longPreferencesKey(key)] = value
                is Float -> prefs[floatPreferencesKey(key)] = value
                is Double -> prefs[doublePreferencesKey(key)] = value
                is Set<*> -> prefs[stringSetPreferencesKey(key)] = value as Set<String>
            }
        }
    }

    suspend fun setHomeFolder(path: String) = store.edit { it[HOME_FOLDER] = path }

    suspend fun setMStream(account: MStreamAccount?) = store.edit {
        if (account == null) {
            it.remove(MSTREAM_TOKEN)
            it.remove(MSTREAM_REVISION)
            it[LIBRARY_SOURCE] = LibrarySource.ALL.name
        } else {
            it[MSTREAM_URL] = account.url
            it[MSTREAM_USER] = account.username
            it[MSTREAM_TOKEN] = account.token
            it.remove(MSTREAM_REVISION)
        }
    }

    /** The server library revision we last mirrored (`sync/manifest`). */
    suspend fun mstreamRevision(): String? = store.data.first()[MSTREAM_REVISION]

    suspend fun setMStreamRevision(revision: String?) = store.edit {
        if (revision == null) it.remove(MSTREAM_REVISION) else it[MSTREAM_REVISION] = revision
    }

    suspend fun setLibrarySource(source: LibrarySource) = store.edit { it[LIBRARY_SOURCE] = source.name }

    suspend fun setSwipe(slot: SwipeSlot, action: SwipeAction) =
        store.edit { it[slotKey(slot)] = action.name }

    suspend fun setConfirmRemove(value: Boolean) = store.edit { it[CONFIRM_REMOVE] = value }

    suspend fun setNormalization(mode: NormalizationMode) = store.edit { it[NORMALIZATION] = mode.name }

    suspend fun setPreampDb(value: Float) = store.edit { it[PREAMP_DB] = value }

    suspend fun setShuffleMode(mode: ShuffleMode) = store.edit { it[SHUFFLE_MODE] = mode.name }

    suspend fun setCrossfadeMs(value: Int) = store.edit { it[CROSSFADE_MS] = value }

    suspend fun setOnlineFetch(value: Boolean) = store.edit { it[ONLINE_FETCH] = value }

    suspend fun setEmbedCovers(value: Boolean) = store.edit { it[EMBED_COVERS] = value }

    suspend fun setSort(page: SortPage, order: SortOrder) = store.edit { it[sortKey(page)] = order.encode() }

    suspend fun setView(page: SortPage, view: LibraryView) = store.edit {
        when (page) {
            SortPage.ALBUMS -> it[ALBUMS_VIEW] = view.name
            SortPage.ARTISTS -> it[ARTISTS_VIEW] = view.name
            else -> Unit
        }
    }

    /** Refused (false) when it would leave Settings unreachable, see [Navigation.settingsReachable]. */
    suspend fun setNavigation(tabs: List<Section>, home: List<HomeItem>): Boolean {
        val bar = tabs.distinct().take(Navigation.MAX_TABS)
        val sections = Navigation.completeHome(home)
        if (!Navigation.settingsReachable(bar, sections)) return false
        store.edit {
            it[TABS] = bar.joinToString(",") { s -> s.name }
            it[HOME] = sections.joinToString(",") { h -> "${h.section.name}:${h.mode.name}" }
        }
        return true
    }

    suspend fun setLastTab(section: Section) = store.edit { it[LAST_TAB] = section.name }

    suspend fun setPullDownArea(area: PullDownArea) = store.edit { it[PULL_DOWN_AREA] = area.name }

    suspend fun setSelectionMarker(side: SelectionMarkerSide) = store.edit { it[SELECTION_MARKER] = side.name }

    suspend fun setQueueHistory(value: Boolean) = store.edit { it[QUEUE_HISTORY] = value }

    suspend fun setQueueSwipe(slot: QueueSwipeSlot, action: QueueSwipeAction) =
        store.edit { it[queueSlotKey(slot)] = action.name }

    suspend fun setFilenamePatterns(patterns: List<String>) =
        store.edit { it[FILENAME_PATTERNS] = patterns.map(String::trim).filter(String::isNotEmpty).joinToString("\n") }

    /** Stored even when empty (splitting off), unlike a missing value (the defaults). */
    suspend fun setArtistSplitting(separators: List<String>, exceptions: List<String>) = store.edit {
        it[ARTIST_SEPARATORS] = separators.map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n")
        it[ARTIST_EXCEPTIONS] = exceptions.map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n")
    }

    suspend fun setLastFmApiKey(value: String?) = store.edit {
        if (value.isNullOrBlank()) it.remove(LASTFM_KEY) else it[LASTFM_KEY] = value.trim()
    }

    private fun Preferences.toSettings() = ZeneloSettings(
        homeFolder = this[HOME_FOLDER],
        swipes = SwipeSlot.entries.associateWith { slot ->
            this[slotKey(slot)]?.let { enumOrNull<SwipeAction>(it) } ?: slot.default
        },
        confirmRemove = this[CONFIRM_REMOVE] ?: false,
        normalization = this[NORMALIZATION]?.let { enumOrNull<NormalizationMode>(it) } ?: NormalizationMode.TRACK,
        crossfadeMs = this[CROSSFADE_MS] ?: 0,
        shuffleMode = this[SHUFFLE_MODE]?.let { enumOrNull<ShuffleMode>(it) } ?: ShuffleMode.TRACKS,
        preampDb = this[PREAMP_DB] ?: 0f,
        onlineFetch = this[ONLINE_FETCH] ?: true,
        embedCovers = this[EMBED_COVERS] ?: true,
        lastFmApiKey = this[LASTFM_KEY],
        queueHistory = this[QUEUE_HISTORY] ?: true,
        queueSwipes = QueueSwipeSlot.entries.associateWith { slot ->
            this[queueSlotKey(slot)]?.let { enumOrNull<QueueSwipeAction>(it) } ?: slot.default
        },
        sorts = SortPage.entries.associateWith { page ->
            SortOrder.decode(this[sortKey(page)]) ?: (if (page == SortPage.BROWSER) legacyBrowserSort() else null) ?: page.default
        },
        albumsView = this[ALBUMS_VIEW]?.let { enumOrNull<LibraryView>(it) } ?: LibraryView.GRID2,
        artistsView = this[ARTISTS_VIEW]?.let { enumOrNull<LibraryView>(it) } ?: LibraryView.LIST,
        tabs = this[TABS]?.split(',')?.filter(String::isNotEmpty)?.mapNotNull { enumOrNull<Section>(it) } ?: Navigation.DEFAULT_TABS,
        home = this[HOME]?.split(',')?.mapNotNull { entry ->
            val (section, mode) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            HomeItem(enumOrNull<Section>(section) ?: return@mapNotNull null, enumOrNull<HomeMode>(mode) ?: return@mapNotNull null)
        }?.let(Navigation::completeHome) ?: Navigation.DEFAULT_HOME,
        lastTab = this[LAST_TAB]?.let { enumOrNull<Section>(it) },
        pullDownArea = this[PULL_DOWN_AREA]?.let { enumOrNull<PullDownArea>(it) } ?: PullDownArea.TOP_THIRD,
        selectionMarker = this[SELECTION_MARKER]?.let { enumOrNull<SelectionMarkerSide>(it) } ?: SelectionMarkerSide.RIGHT,
        filenamePatterns = this[FILENAME_PATTERNS]?.split('\n')?.filter(String::isNotBlank) ?: DEFAULT_FILENAME_PATTERNS,
        artistSeparators = this[ARTIST_SEPARATORS]?.split('\n')?.filter(String::isNotBlank) ?: ArtistSplitter.DEFAULT_SEPARATORS,
        mstream = this[MSTREAM_TOKEN]?.let { token ->
            MStreamAccount(this[MSTREAM_URL] ?: return@let null, this[MSTREAM_USER].orEmpty(), token)
        },
        librarySource = this[LIBRARY_SOURCE]?.let { enumOrNull<LibrarySource>(it) } ?: LibrarySource.ALL,
        artistExceptions = this[ARTIST_EXCEPTIONS]?.split('\n')?.filter(String::isNotBlank) ?: ArtistSplitter.DEFAULT_EXCEPTIONS,
    )

    /** The browser's sort before per-page sorts existed ("NAME_DESC", "DATE_NEWEST", ...). */
    private fun Preferences.legacyBrowserSort(): SortOrder? = when (this[BROWSER_SORT]) {
        "NAME_DESC" -> SortOrder(SortField.NAME, descending = true)
        "DATE_NEWEST" -> SortOrder(SortField.DATE_ADDED, descending = true)
        "DATE_OLDEST" -> SortOrder(SortField.DATE_ADDED)
        else -> null
    }

    /**
     * Runs once, while no bottom bar is stored: an upgrade from the fixed-tabs version keeps its
     * four tabs ([Navigation.LEGACY_TABS]); a new install gets the defaults. Written either way, so
     * the choice is made on the first start only.
     */
    private class FirstNavigation(private val upgradedInstall: Boolean) : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = currentData[TABS] == null

        override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
            val tabs = if (upgradedInstall) Navigation.LEGACY_TABS else Navigation.DEFAULT_TABS
            this[TABS] = tabs.joinToString(",") { it.name }
        }

        override suspend fun cleanUp() = Unit
    }

    private companion object {
        val HOME_FOLDER = stringPreferencesKey("home_folder")
        val CONFIRM_REMOVE = booleanPreferencesKey("confirm_remove")
        val NORMALIZATION = stringPreferencesKey("normalization")
        val CROSSFADE_MS = intPreferencesKey("crossfade_ms")
        val SHUFFLE_MODE = stringPreferencesKey("shuffle_mode")
        val PREAMP_DB = floatPreferencesKey("preamp_db")
        val ONLINE_FETCH = booleanPreferencesKey("online_fetch")
        val EMBED_COVERS = booleanPreferencesKey("embed_covers")
        val LASTFM_KEY = stringPreferencesKey("lastfm_api_key")
        val QUEUE_HISTORY = booleanPreferencesKey("queue_history")
        val FILENAME_PATTERNS = stringPreferencesKey("filename_patterns")
        val BROWSER_SORT = stringPreferencesKey("browser_sort")
        val PULL_DOWN_AREA = stringPreferencesKey("pull_down_area")
        val SELECTION_MARKER = stringPreferencesKey("selection_marker")
        val ALBUMS_VIEW = stringPreferencesKey("albums_view")
        val ARTISTS_VIEW = stringPreferencesKey("artists_view")
        val TABS = stringPreferencesKey("tabs")
        val HOME = stringPreferencesKey("home_sections")
        val LAST_TAB = stringPreferencesKey("last_tab")
        val MSTREAM_URL = stringPreferencesKey("mstream_url")
        val MSTREAM_USER = stringPreferencesKey("mstream_user")
        val MSTREAM_TOKEN = stringPreferencesKey("mstream_token")
        val MSTREAM_REVISION = stringPreferencesKey("mstream_revision")
        val LIBRARY_SOURCE = stringPreferencesKey("library_source")
        /** Not in backups: a login token, and sync state that means nothing elsewhere. */
        val PRIVATE_KEYS = setOf("mstream_token", "mstream_revision")
        val ARTIST_SEPARATORS = stringPreferencesKey("artist_separators")
        val ARTIST_EXCEPTIONS = stringPreferencesKey("artist_exceptions")

        fun sortKey(page: SortPage) = stringPreferencesKey("sort_${page.name.lowercase()}")

        fun queueSlotKey(slot: QueueSwipeSlot) = stringPreferencesKey("queue_swipe_${slot.name.lowercase()}")

        fun slotKey(slot: SwipeSlot) = stringPreferencesKey("swipe_${slot.name.lowercase()}")

        inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}

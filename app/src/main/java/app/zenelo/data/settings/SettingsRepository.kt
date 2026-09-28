package app.zenelo.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

enum class NormalizationMode { OFF, TRACK, ALBUM }

data class ZeneloSettings(
    val homeFolder: String? = null,
    val swipes: Map<SwipeSlot, SwipeAction> = SwipeSlot.entries.associateWith { it.default },
    /** Ask before destructive swipe actions instead of showing an undo snackbar. */
    val confirmRemove: Boolean = false,
    val normalization: NormalizationMode = NormalizationMode.TRACK,
    val crossfadeMs: Int = 0,
    /** Covers and lyrics from the internet; always Wi-Fi only. */
    val onlineFetch: Boolean = true,
    /** Write downloaded covers into files that have none (and on manual "Download cover"). */
    val embedCovers: Boolean = true,
    /** Optional Last.fm API key: adds Last.fm as a cover source. Key-less sources are used either way. */
    val lastFmApiKey: String? = null,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<ZeneloSettings> = store.data.map { it.toSettings() }

    suspend fun setHomeFolder(path: String) = store.edit { it[HOME_FOLDER] = path }

    suspend fun setSwipe(slot: SwipeSlot, action: SwipeAction) =
        store.edit { it[slotKey(slot)] = action.name }

    suspend fun setConfirmRemove(value: Boolean) = store.edit { it[CONFIRM_REMOVE] = value }

    suspend fun setNormalization(mode: NormalizationMode) = store.edit { it[NORMALIZATION] = mode.name }

    suspend fun setCrossfadeMs(value: Int) = store.edit { it[CROSSFADE_MS] = value }

    suspend fun setOnlineFetch(value: Boolean) = store.edit { it[ONLINE_FETCH] = value }

    suspend fun setEmbedCovers(value: Boolean) = store.edit { it[EMBED_COVERS] = value }

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
        onlineFetch = this[ONLINE_FETCH] ?: true,
        embedCovers = this[EMBED_COVERS] ?: true,
        lastFmApiKey = this[LASTFM_KEY],
    )

    private companion object {
        val HOME_FOLDER = stringPreferencesKey("home_folder")
        val CONFIRM_REMOVE = booleanPreferencesKey("confirm_remove")
        val NORMALIZATION = stringPreferencesKey("normalization")
        val CROSSFADE_MS = intPreferencesKey("crossfade_ms")
        val ONLINE_FETCH = booleanPreferencesKey("online_fetch")
        val EMBED_COVERS = booleanPreferencesKey("embed_covers")
        val LASTFM_KEY = stringPreferencesKey("lastfm_api_key")

        fun slotKey(slot: SwipeSlot) = stringPreferencesKey("swipe_${slot.name.lowercase()}")

        inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}

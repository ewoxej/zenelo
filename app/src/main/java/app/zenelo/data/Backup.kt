package app.zenelo.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.PlayEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Everything a backup holds. Settings are DataStore's raw values by key. */
data class BackupData(
    val createdAt: Long,
    val appVersion: String,
    val settings: Map<String, Any>,
    val favorites: List<FavoriteEntity>,
    val playlists: List<BackupPlaylist>,
    val plays: List<PlayEntity>,
)

data class BackupPlaylist(val name: String, val createdAt: Long, val paths: List<String>)

/**
 * The backup file: one JSON object, `"format": "zenelo-backup"`. Plays are `[path, playedAt]`
 * pairs (the bulk of the file). Unknown keys are ignored, so later versions can add sections.
 */
object BackupFormat {
    private const val FORMAT = "zenelo-backup"
    private const val VERSION = 1

    fun toJson(data: BackupData): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdAt", data.createdAt)
        put("appVersion", data.appVersion)
        put("settings", JSONObject().apply { data.settings.forEach { (key, value) -> put(key, typed(value)) } })
        put("favorites", JSONArray().apply {
            data.favorites.forEach { f ->
                put(JSONObject().put("path", f.path).put("kind", f.kind.name).put("title", f.title).put("subtitle", f.subtitle ?: JSONObject.NULL).put("addedAt", f.addedAt))
            }
        })
        put("playlists", JSONArray().apply {
            data.playlists.forEach { p -> put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("tracks", JSONArray(p.paths))) }
        })
        put("plays", JSONArray().apply { data.plays.forEach { put(JSONArray().put(it.path).put(it.playedAt)) } })
    }.toString()

    /** Throws [IllegalArgumentException] for anything that isn't a backup this version can read. */
    fun fromJson(text: String): BackupData {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a Zenelo backup") }
        require(root.optString("format") == FORMAT) { "Not a Zenelo backup" }
        require(root.optInt("version") in 1..VERSION) { "Backup from a newer version of Zenelo" }
        val settings = root.optJSONObject("settings")?.let { s -> s.keys().asSequence().mapNotNull { k -> untyped(s.getJSONObject(k))?.let { k to it } }.toMap() }.orEmpty()
        val favorites = root.optJSONArray("favorites").objects().mapNotNull { f ->
            val kind = FavoriteKind.entries.firstOrNull { it.name == f.optString("kind") } ?: return@mapNotNull null
            FavoriteEntity(f.getString("path"), kind, f.optString("title"), f.optStringOrNull("subtitle"), f.optLong("addedAt"))
        }
        val playlists = root.optJSONArray("playlists").objects().map { p ->
            val tracks = p.optJSONArray("tracks") ?: JSONArray()
            BackupPlaylist(p.getString("name"), p.optLong("createdAt"), List(tracks.length()) { tracks.getString(it) })
        }
        val playsJson = root.optJSONArray("plays") ?: JSONArray()
        val plays = List(playsJson.length()) { i -> playsJson.getJSONArray(i).let { PlayEntity(path = it.getString(0), playedAt = it.getLong(1)) } }
        return BackupData(root.optLong("createdAt"), root.optString("appVersion"), settings, favorites, playlists, plays)
    }

    private fun typed(value: Any): JSONObject = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Double -> JSONObject().put("t", "d").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> JSONObject().put("t", "s").put("v", value.toString())
    }

    private fun untyped(o: JSONObject): Any? = when (o.optString("t")) {
        "s" -> o.getString("v")
        "b" -> o.getBoolean("v")
        "i" -> o.getInt("v")
        "l" -> o.getLong("v")
        "f" -> o.getDouble("v").toFloat()
        "d" -> o.getDouble("v")
        "ss" -> o.getJSONArray("v").let { a -> List(a.length()) { a.getString(it) }.toSet() }
        else -> null
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key)) null else optString(key)
}

/** Backs up / restores settings, favorites, playlists and play history as one file. */
class Backup(private val context: Context, private val db: ZeneloDatabase, private val settings: SettingsRepository) {

    suspend fun write(uri: Uri): BackupData = withContext(Dispatchers.IO) {
        val entries = db.playlists().allEntries().groupBy({ it.playlistId }, { it.path })
        val data = BackupData(
            createdAt = System.currentTimeMillis(),
            appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
            settings = settings.snapshot(),
            favorites = db.favorites().all(),
            playlists = db.playlists().all().map { BackupPlaylist(it.name, it.createdAt, entries[it.id].orEmpty()) },
            plays = db.plays().all(),
        )
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(BackupFormat.toJson(data).toByteArray()) } ?: error("Can't write $uri")
        data
    }

    /** Throws [IllegalArgumentException] if [uri] isn't a readable backup. */
    suspend fun read(uri: Uri): BackupData = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("Can't read the file")
        BackupFormat.fromJson(text)
    }

    /** Replaces settings, favorites, playlists and play history with [data]'s. */
    suspend fun restore(data: BackupData) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.favorites().deleteAll()
            db.favorites().insertAll(data.favorites)
            db.playlists().deleteAllEntries()
            db.playlists().deleteAllPlaylists()
            data.playlists.forEach { db.playlists().create(it.name, it.paths, it.createdAt) }
            db.plays().deleteAll()
            db.plays().insertAll(data.plays.map { it.copy(id = 0) })
        }
        settings.restoreSnapshot(data.settings)
    }
}

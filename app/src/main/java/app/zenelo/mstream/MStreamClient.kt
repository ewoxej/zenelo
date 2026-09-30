package app.zenelo.mstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the server is and who we are there. The token is mStream's JWT (5-year lifetime). */
data class MStreamAccount(val url: String, val username: String, val token: String)

/** One track of the server's library manifest. */
data class ManifestEntry(
    /** `<vpath>/<rel>`. */
    val filepath: String,
    val title: String?,
    val artist: String?,
    val artistDisplay: String?,
    val album: String?,
    val track: Int?,
    val durationMs: Long,
    val artFile: String?,
    val rating: Int?,
    val replayGainDb: Float?,
    val hasLyrics: Boolean,
    val hasSyncedLyrics: Boolean,
    val size: Long,
    val modified: Long,
    val hash: String?,
    val format: String?,
    val genres: List<String> = emptyList(),
)

data class ManifestPage(val revision: String?, val scanning: Boolean, val next: Int?, val entries: List<ManifestEntry>)

class MStreamException(message: String, val code: Int = 0) : IOException(message)

/** A track the server picked (Auto DJ, Sonic Path): its path and the tags the pick logic needs. */
data class ServerSong(
    /** `<vpath>/<rel>`. */
    val filepath: String,
    val title: String?,
    val artist: String?,
    val bpm: Double?,
    val musicalKey: String?,
    val album: String? = null,
)

/** One `db/random-songs` answer: the picks and the server's round-trip cursor of served ids. */
data class RandomSongs(val songs: List<ServerSong>, val ignoreList: List<Int>)

/** A Sonic Path: the songs from start to end (both included), or why there are none. */
data class SonicPath(val songs: List<ServerSong>, val startNotAnalyzed: Boolean, val endNotAnalyzed: Boolean)

/**
 * The mStream HTTP API (see its `docs/openapi.yaml`). Every call is blocking I/O on
 * [Dispatchers.IO]; failures throw [IOException] ([MStreamException] with the server's message).
 */
class MStreamClient(base: OkHttpClient) {
    private val client = base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Logs in; returns the account with its token. [url] may lack the scheme ("192.168.1.5:3000").
     * An empty [username]: a server without users (public mode) — only checked to answer.
     */
    suspend fun login(url: String, username: String, password: String): MStreamAccount = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url) ?: throw MStreamException("Not a server address")
        if (username.isEmpty()) {
            val ping = base.resolve("api/v1/ping") ?: throw MStreamException("Bad address")
            client.newCall(Request.Builder().url(ping).build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw MStreamException("This server needs a user name and password")
                if (!r.isSuccessful) throw MStreamException("Not an mStream server (${r.code})")
            }
            return@withContext MStreamAccount(base.toString().trimEnd('/'), "", "")
        }
        val body = JSONObject().put("username", username).put("password", password)
        val json = post(base, "api/v1/auth/login", token = null, body = body) as JSONObject
        val token = json.optString("token").takeIf { it.isNotEmpty() } ?: throw MStreamException("No token in the answer")
        MStreamAccount(base.toString().trimEnd('/'), username, token)
    }

    /**
     * One page of the library manifest. [revision] (first page only) returns null when nothing
     * changed since it.
     */
    suspend fun manifest(account: MStreamAccount, cursor: Int?, revision: String?): ManifestPage? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("limit", PAGE)
        if (cursor != null) body.put("cursor", cursor)
        val request = Request.Builder()
            .url(url(account, "api/v1/sync/manifest"))
            .header("x-access-token", account.token)
            .apply { if (cursor == null && revision != null) header("If-None-Match", "\"$revision\"") }
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) return@withContext null
            if (response.code == 404) throw MStreamException("This mStream server has no library sync API — update it", 404)
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw error(response.code, text)
            val json = JSONObject(text)
            val entries = json.optJSONArray("entries") ?: JSONArray()
            ManifestPage(
                revision = json.optString("revision").takeIf { it.isNotEmpty() },
                scanning = json.optBoolean("scanning"),
                next = if (json.isNull("next")) null else json.optInt("next"),
                entries = List(entries.length()) { parseEntry(entries.getJSONObject(it)) },
            )
        }
    }

    /** The track's lyrics stored on the server: synced (LRC) and plain; nulls when it has none. */
    suspend fun lyrics(account: MStreamAccount, serverPath: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val url = url(account, "api/v1/lyrics").newBuilder().addQueryParameter("path", serverPath).build()
        val request = Request.Builder().url(url).header("x-access-token", account.token).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null to null
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw error(response.code, text)
            val json = JSONObject(text)
            fun pick(key: String): String? {
                val box = json.optJSONObject(key) ?: return null
                val list = box.optJSONArray("lyrics") ?: return null
                if (list.length() == 0) return null
                val entry = list.optJSONObject(box.optInt("default", 0).coerceIn(0, list.length() - 1)) ?: return null
                return entry.optString("data").takeIf { it.isNotBlank() }
            }
            pick("syncedLyrics") to pick("lyrics")
        }
    }

    /** Album art from the server's cache; [small] asks for its small thumbnail. Needs the login (despite the docs). */
    suspend fun albumArt(account: MStreamAccount, file: String, small: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        val url = url(account, "album-art/$file").newBuilder()
            .apply { if (small) addQueryParameter("compress", "zl") }
            .build()
        runCatching {
            client.newCall(Request.Builder().url(url).header("x-access-token", account.token).build()).execute()
                .use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
    }

    /** Sets the user's rating (0–10) of a track, or clears it (null). */
    suspend fun rate(account: MStreamAccount, serverPath: String, rating: Int?) = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        post(base, "api/v1/db/rate-song", account.token, JSONObject().put("filepath", serverPath).put("rating", rating ?: JSONObject.NULL))
        Unit
    }

    /** A play of one of the server's tracks, reported after it happened (see [reportPlays]). */
    data class Play(val id: String, val serverPath: String, val startedAt: Long, val playedMs: Long, val durationMs: Long)

    /** Reports plays (Stats API v2). Idempotent by [Play.id]: resending one is harmless. */
    suspend fun reportPlays(account: MStreamAccount, plays: List<Play>) = withContext(Dispatchers.IO) {
        if (plays.isEmpty()) return@withContext
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        val body = JSONObject()
            .put("client", JSONObject().put("name", "Zenelo"))
            .put("plays", JSONArray().apply {
                plays.forEach { p ->
                    put(JSONObject().put("id", p.id).put("filePath", p.serverPath).put("startedAt", p.startedAt)
                        .put("playedMs", p.playedMs).put("durationMs", p.durationMs).put("outcome", "completed"))
                }
            })
        post(base, "api/v1/stats/plays", account.token, body)
        Unit
    }

    /**
     * "Now playing" for the server's own now-playing view (and Last.fm's, if linked): kept there
     * for 10 min per [sessionId], so a long track is announced again.
     */
    suspend fun nowPlaying(account: MStreamAccount, serverPath: String, sessionId: String) = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        post(base, "api/v1/stats/now-playing", account.token, JSONObject().put("filePath", serverPath).put("sessionId", sessionId))
        Unit
    }

    /** The server's genres with their track counts, as it names them (for Auto DJ's genre filter). */
    suspend fun genres(account: MStreamAccount): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(account, "api/v1/db/genres")).header("x-access-token", account.token).build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw error(r.code, text)
            val array = JSONObject(text).optJSONArray("genres") ?: JSONArray()
            List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
                o.optString("name").takeIf { it.isNotBlank() }?.let { it to o.optInt("track_count") }
            }
        }
    }

    /** The user's ratings (0–10) of rated tracks: server path → rating. */
    suspend fun rated(account: MStreamAccount): Map<String, Int> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(account, "api/v1/db/rated")).header("x-access-token", account.token).build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw error(r.code, text)
            val array = JSONArray(text)
            List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
                val rating = o.optJSONObject("metadata")?.takeIf { m -> !m.isNull("rating") }?.optInt("rating") ?: return@mapNotNull null
                o.optString("filepath").takeIf { it.isNotEmpty() }?.let { it to rating }
            }.toMap()
        }
    }

    /** The user's recently played tracks: server path → last played (epoch ms). */
    suspend fun recentlyPlayed(account: MStreamAccount, limit: Int): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        val array = post(base, "api/v1/db/stats/recently-played", account.token, JSONObject().put("limit", limit)) as? JSONArray ?: JSONArray()
        List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
            val at = parseTime(o.optJSONObject("metadata")?.opt("last-played")) ?: return@mapNotNull null
            o.optString("filepath").takeIf { it.isNotEmpty() }?.let { it to at }
        }
    }

    /** The user's playlists on the server, each with its tracks (server paths, in order). */
    suspend fun playlists(account: MStreamAccount): List<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        val request = Request.Builder().url(url(account, "api/v1/playlist/getall")).header("x-access-token", account.token).build()
        val names = client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw error(r.code, text)
            val array = JSONArray(text)
            List(array.length()) { array.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }
        }
        names.map { name ->
            val songs = post(base, "api/v1/playlist/load", account.token, JSONObject().put("playlistname", name)) as? JSONArray ?: JSONArray()
            name to List(songs.length()) { songs.getJSONObject(it).optString("filepath") }.filter { it.isNotEmpty() }
        }
    }

    /**
     * Auto DJ picks (`db/random-songs`): [body] is the request as the web app builds it (see
     * [MStreamAutoDj]). 400 = nothing matches.
     */
    suspend fun randomSongs(account: MStreamAccount, body: JSONObject): RandomSongs = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        val json = post(base, "api/v1/db/random-songs", account.token, body) as JSONObject
        val songs = json.optJSONArray("songs") ?: JSONArray()
        val ignore = json.optJSONArray("ignoreList") ?: JSONArray()
        RandomSongs(List(songs.length()) { parseSong(songs.getJSONObject(it)) }, List(ignore.length()) { ignore.optInt(it) })
    }

    /** The server's tags of one track (BPM / key for Auto DJ), null if it doesn't know it. */
    suspend fun song(account: MStreamAccount, serverPath: String): ServerSong? = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        runCatching { parseSong(post(base, "api/v1/db/metadata", account.token, JSONObject().put("filepath", serverPath)) as JSONObject) }.getOrNull()
    }

    /**
     * Library artists Last.fm calls similar to [artist] (the server asks Last.fm and keeps its own
     * spellings); empty when the server has no Last.fm key or nothing matches.
     */
    suspend fun similarArtists(account: MStreamAccount, artist: String): List<String> = withContext(Dispatchers.IO) {
        val url = url(account, "api/v1/lastfm/similar-artists").newBuilder().addQueryParameter("artist", artist).build()
        runCatching {
            client.newCall(Request.Builder().url(url).header("x-access-token", account.token).build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val array = JSONObject(r.body?.string().orEmpty()).optJSONArray("artists") ?: return@use emptyList()
                List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Tracks that sound like [serverPath] (the server's audio embeddings), closest first; 403 = discovery off. */
    suspend fun similarTracks(account: MStreamAccount, serverPath: String, limit: Int): List<ServerSong> = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        val json = post(base, "api/v1/discovery/local/similar/tracks", account.token, JSONObject().put("filePath", serverPath).put("limit", limit)) as JSONObject
        if (json.optBoolean("notAnalyzed")) throw MStreamException("The server hasn't analyzed this track yet", 409)
        val rows = json.optJSONArray("results") ?: JSONArray()
        List(rows.length()) { parseSong(rows.getJSONObject(it)) }
    }

    /** Library artists that sound like [artist] (the server's audio embeddings); empty if it can't tell. */
    suspend fun soundAlikeArtists(account: MStreamAccount, artist: String, limit: Int = 12): List<String> = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        runCatching {
            val json = post(base, "api/v1/discovery/local/similar/artists", account.token, JSONObject().put("artist", artist).put("limit", limit)) as JSONObject
            val rows = json.optJSONArray("results") ?: JSONArray()
            List(rows.length()) { rows.getJSONObject(it).optString("artist") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * Sonic Path (`discovery/local/path`): [length] songs (4–32, ends included) that morph from
     * [start] to [end] by the server's audio embeddings. 403 = discovery is off on the server.
     */
    suspend fun sonicPath(account: MStreamAccount, start: String, end: String, length: Int): SonicPath = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        val body = JSONObject().put("startFilePath", start).put("endFilePath", end).put("length", length.coerceIn(4, 32))
        val json = post(base, "api/v1/discovery/local/path", account.token, body) as JSONObject
        val rows = json.optJSONArray("results") ?: JSONArray()
        val notAnalyzed = json.optJSONObject("notAnalyzed")
        SonicPath(
            songs = List(rows.length()) { parseSong(rows.getJSONObject(it)) },
            startNotAnalyzed = notAnalyzed?.optBoolean("start") == true,
            endNotAnalyzed = notAnalyzed?.optBoolean("end") == true,
        )
    }

    /** Creates or overwrites the user's playlist [name] with [serverPaths]. */
    suspend fun savePlaylist(account: MStreamAccount, name: String, serverPaths: List<String>) = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        post(base, "api/v1/playlist/save", account.token, JSONObject().put("title", name).put("songs", JSONArray(serverPaths)))
        Unit
    }

    /** Renames a playlist; 400 when [newName] is taken. */
    suspend fun renamePlaylist(account: MStreamAccount, oldName: String, newName: String) = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        post(base, "api/v1/playlist/rename", account.token, JSONObject().put("oldName", oldName).put("newName", newName))
        Unit
    }

    suspend fun deletePlaylist(account: MStreamAccount, name: String) = withContext(Dispatchers.IO) {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        post(base, "api/v1/playlist/delete", account.token, JSONObject().put("playlistname", name))
        Unit
    }

    /** The server's transcoded stream of a track (`/transcode/<vpath>/<rel>`). */
    fun transcodeUrl(account: MStreamAccount, serverPath: String, codec: String, bitrate: String): HttpUrl =
        url(account, "transcode/" + serverPath.trimStart('/')).newBuilder()
            .addQueryParameter("codec", codec)
            .addQueryParameter("bitrate", bitrate)
            .build()

    /** The file itself (`/media/<vpath>/<rel>`), for the player. */
    fun mediaUrl(account: MStreamAccount, serverPath: String): HttpUrl =
        url(account, "media/" + serverPath.trimStart('/'))

    private fun post(base: HttpUrl, path: String, token: String?, body: JSONObject): Any {
        val request = Request.Builder()
            .url(base.resolve(path) ?: throw MStreamException("Bad address"))
            .apply { if (token != null) header("x-access-token", token) }
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw error(response.code, text)
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }

    private fun url(account: MStreamAccount, path: String): HttpUrl {
        val base = "${account.url}/".toHttpUrlOrNull() ?: throw MStreamException("Bad address")
        // Path segments one by one: file names carry spaces, '#', '?'.
        return base.newBuilder().apply { path.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment) }.build()
    }

    private fun error(code: Int, body: String): MStreamException {
        android.util.Log.w("Zenelo", "mStream answered $code: ${body.take(300)}")
        val message = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
        return MStreamException(
            when (code) {
                401, 403 -> message ?: "Wrong user name or password"
                else -> message ?: "Server error $code"
            },
            code,
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val PAGE = 2000

        /** The server's times: epoch ms, ISO 8601, or SQLite's "YYYY-MM-DD HH:MM:SS" (UTC). */
        internal fun parseTime(value: Any?): Long? = when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }
            is String -> runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching {
                    java.time.LocalDateTime.parse(value.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                }.getOrNull()
            else -> null
        }

        /** "192.168.1.5:3000" → "http://192.168.1.5:3000/"; keeps a path prefix (reverse proxies). */
        fun normalizeUrl(url: String): HttpUrl? {
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return "$withScheme/".toHttpUrlOrNull()
        }

        internal fun parseSong(o: JSONObject): ServerSong {
            val m = o.optJSONObject("metadata") ?: JSONObject()
            fun str(key: String) = if (m.isNull(key)) null else m.optString(key).takeIf { it.isNotBlank() }
            return ServerSong(
                filepath = o.getString("filepath").trimStart('/'),
                title = str("title"),
                artist = str("artist"),
                bpm = if (m.isNull("bpm") || !m.has("bpm")) null else m.optDouble("bpm").takeIf { it.isFinite() && it > 0 },
                musicalKey = str("musical-key"),
                album = str("album"),
            )
        }

        internal fun parseEntry(o: JSONObject): ManifestEntry {
            val m = o.optJSONObject("metadata") ?: JSONObject()
            fun str(json: JSONObject, key: String) = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
            fun int(json: JSONObject, key: String) = if (json.isNull(key) || !json.has(key)) null else json.optInt(key)
            return ManifestEntry(
                filepath = o.getString("filepath"),
                title = str(m, "title"),
                artist = str(m, "artist"),
                artistDisplay = str(m, "artist-display"),
                album = str(m, "album"),
                track = int(m, "track"),
                durationMs = (m.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0).times(1000).toLong(),
                artFile = str(m, "album-art"),
                rating = int(m, "rating"),
                replayGainDb = if (m.isNull("replaygain-track") || !m.has("replaygain-track")) null else m.optDouble("replaygain-track").toFloat(),
                hasLyrics = m.optBoolean("has-lyrics"),
                hasSyncedLyrics = m.optBoolean("has-synced-lyrics"),
                size = o.optLong("file-size", 0),
                modified = o.optLong("modified", 0),
                hash = str(o, "audio-hash") ?: str(o, "hash"),
                format = str(o, "format"),
                genres = m.optJSONArray("genres")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
            )
        }
    }
}

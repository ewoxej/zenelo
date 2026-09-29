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
)

data class ManifestPage(val revision: String?, val scanning: Boolean, val next: Int?, val entries: List<ManifestEntry>)

class MStreamException(message: String) : IOException(message)

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
        val message = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
        return MStreamException(
            when (code) {
                401, 403 -> message ?: "Wrong user name or password"
                else -> message ?: "Server error $code"
            },
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val PAGE = 2000

        /** "192.168.1.5:3000" → "http://192.168.1.5:3000/"; keeps a path prefix (reverse proxies). */
        fun normalizeUrl(url: String): HttpUrl? {
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return "$withScheme/".toHttpUrlOrNull()
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
            )
        }
    }
}

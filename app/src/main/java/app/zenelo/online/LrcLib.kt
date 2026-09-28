package app.zenelo.online

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import kotlin.math.abs

data class FoundLyrics(val synced: String?, val plain: String?, val instrumental: Boolean)

/** https://lrclib.net — free, key-less, with time-synced lyrics. */
class LrcLib(private val http: Http) {

    /**
     * Exact lookup by title / artist / album / duration first, then a search picking the closest
     * duration. Returns null on network failure (retry later) and an empty result when not found.
     */
    suspend fun find(title: String, artist: String, album: String?, durationSec: Int): FoundLyrics? {
        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        http.getObject(exact)?.let { return it.toLyrics() }

        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        // The exact endpoint answers 404 for misses; a failed search means the network is down.
        val results = http.getArray(search) ?: return null
        val best = results.objects()
            .filter { durationSec <= 0 || abs(it.optDouble("duration", 0.0) - durationSec) <= 3 }
            .sortedByDescending { !it.optString("syncedLyrics").isNullOrBlank() && it.optString("syncedLyrics") != "null" }
            .firstOrNull()
        return best?.toLyrics() ?: EMPTY
    }

    private fun JSONObject.toLyrics() = FoundLyrics(
        synced = optNullableString("syncedLyrics"),
        plain = optNullableString("plainLyrics"),
        instrumental = optBoolean("instrumental", false),
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        val EMPTY = FoundLyrics(null, null, false)
    }
}

package app.zenelo.online

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class FoundLyrics(val synced: String?, val plain: String?, val instrumental: Boolean)

/** https://lrclib.net — free, key-less, with time-synced lyrics. */
class LrcLib(private val http: Http) {

    /**
     * Lyrics for a track tagged [titles] / [artists] (as tagged first, then cleaned-up variants, see
     * [LyricsSearch]): the exact lookup, then searches by title and artist, then a free-text
     * search. Stops at a record of the same length; else takes the closest version's text (plain).
     * Returns null on network failure (retry later) and an empty result when not found.
     */
    suspend fun find(titles: List<String>, artists: List<String>, album: String?, durationSec: Int): FoundLyrics? {
        val title = titles.firstOrNull() ?: return EMPTY
        val artist = artists.firstOrNull() ?: return EMPTY
        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        http.getObject(exact)?.let { found ->
            val lyrics = found.toLyrics()
            if (lyrics.synced != null || lyrics.plain != null || lyrics.instrumental) return lyrics
        }

        val pool = mutableListOf<LyricsSearch.Candidate>()
        var online = false
        fun best() = LyricsSearch.pick(pool, titles, artists, durationSec)
        // Tagged and core title × the first two artist variants; the exact endpoint answers 404 for
        // misses, so only a search tells whether the network is up.
        val searchTitles = listOf(title, LyricsSearch.titleVariants(title).last()).distinct()
        for (a in artists.take(2)) {
            for (t in searchTitles) {
                val results = search { addQueryParameter("track_name", t).addQueryParameter("artist_name", a) } ?: continue
                online = true
                pool += results
                best()?.takeIf { it.exact }?.let { return it.lyrics }
            }
        }
        search { addQueryParameter("q", "${artists.minBy { it.length }} ${searchTitles.last()}") }?.let {
            online = true
            pool += it
        }
        best()?.let { return it.lyrics }
        return if (online) EMPTY else null
    }

    private suspend fun search(params: okhttp3.HttpUrl.Builder.() -> okhttp3.HttpUrl.Builder): List<LyricsSearch.Candidate>? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().params().build()
        return http.getArray(url)?.objects()?.map {
            LyricsSearch.Candidate(
                artist = it.optString("artistName"),
                title = it.optString("trackName"),
                durationSec = it.optDouble("duration", Double.NaN).takeIf { d -> !d.isNaN() },
                synced = it.optNullableString("syncedLyrics"),
                plain = it.optNullableString("plainLyrics"),
                instrumental = it.optBoolean("instrumental", false),
            )
        }
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

package app.zenelo.online

import app.zenelo.library.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl

data class CoverCandidate(
    val source: String,
    val artist: String,
    val album: String,
    val thumbUrl: String,
    /** Best-first list: some catalogs only have smaller sizes for older uploads. */
    val fullUrls: List<String>,
)

/**
 * Album cover search. Key-less catalogs by default: Deezer, iTunes, MusicBrainz + Cover Art Archive.
 * Last.fm joins when the user entered an API key ([lastFmKey]); in [auto] it is the last resort.
 * [auto] takes the first result whose artist and album both match; [candidates] returns everything
 * for the manual "Download cover" picker.
 */
class CoverSources(private val http: Http, private val lastFmKey: suspend () -> String?) {
    private val musicBrainzLimiter = RateLimiter(1100)

    suspend fun candidates(artist: String, album: String): List<CoverCandidate> = coroutineScope {
        val deezer = async { deezer(artist, album) }
        val itunes = async { itunes(artist, album) }
        val musicBrainz = async { musicBrainz(artist, album) }
        val lastFm = async { lastFm(artist, album) }
        deezer.await() + itunes.await() + musicBrainz.await() + lastFm.await()
    }

    /** Cover bytes for a confident match, or null. Sources are tried in order and stop at the first hit. */
    suspend fun auto(artist: String, album: String): Pair<CoverCandidate, ByteArray>? {
        for (search in listOf(::deezer, ::itunes, ::musicBrainz, ::lastFm)) {
            val match = search(artist, album).firstOrNull { Text.matches(it.artist, artist) && Text.matches(it.album, album) }
                ?: continue
            download(match)?.let { return match to it }
        }
        return null
    }

    suspend fun download(candidate: CoverCandidate): ByteArray? =
        candidate.fullUrls.firstNotNullOfOrNull { http.getBytes(it)?.takeIf { bytes -> bytes.size > 1000 } }

    private suspend fun deezer(artist: String, album: String): List<CoverCandidate> {
        val url = "https://api.deezer.com/search/album".toHttpUrl().newBuilder()
            .addQueryParameter("q", "artist:\"${artist.clean()}\" album:\"${album.clean()}\"")
            .addQueryParameter("limit", "6")
            .build()
        val data = http.getObject(url)?.optJSONArray("data") ?: return emptyList()
        return data.objects().mapNotNull { o ->
            val xl = o.optString("cover_xl").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CoverCandidate(
                source = "Deezer",
                artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                album = o.optString("title"),
                thumbUrl = o.optString("cover_medium", xl),
                fullUrls = listOf(xl),
            )
        }
    }

    private suspend fun itunes(artist: String, album: String): List<CoverCandidate> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $album")
            .addQueryParameter("entity", "album")
            .addQueryParameter("limit", "6")
            .build()
        val results = http.getObject(url)?.optJSONArray("results") ?: return emptyList()
        return results.objects().mapNotNull { o ->
            val art = o.optString("artworkUrl100").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CoverCandidate(
                source = "iTunes",
                artist = o.optString("artistName"),
                album = o.optString("collectionName"),
                thumbUrl = art.replace("100x100bb", "300x300bb"),
                fullUrls = listOf(art.replace("100x100bb", "1200x1200bb"), art.replace("100x100bb", "600x600bb")),
            )
        }
    }

    private suspend fun musicBrainz(artist: String, album: String): List<CoverCandidate> {
        val url = "https://musicbrainz.org/ws/2/release-group/".toHttpUrl().newBuilder()
            .addQueryParameter("query", "releasegroup:\"${album.clean()}\" AND artist:\"${artist.clean()}\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "5")
            .build()
        val groups = musicBrainzLimiter.run { http.getObject(url) }?.optJSONArray("release-groups") ?: return emptyList()
        return groups.objects().map { o ->
            val id = o.getString("id")
            val base = "https://coverartarchive.org/release-group/$id"
            CoverCandidate(
                source = "MusicBrainz",
                artist = o.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name").orEmpty(),
                album = o.optString("title"),
                thumbUrl = "$base/front-250",
                fullUrls = listOf("$base/front-1200", "$base/front-500", "$base/front"),
            )
        }
    }

    /** Only with a user-provided API key. Image URLs without the size segment point at the original. */
    private suspend fun lastFm(artist: String, album: String): List<CoverCandidate> {
        val key = lastFmKey() ?: return emptyList()
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", "album.search")
            .addQueryParameter("album", "$artist $album")
            .addQueryParameter("api_key", key)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "6")
            .build()
        val albums = http.getObject(url)?.optJSONObject("results")?.optJSONObject("albummatches")?.optJSONArray("album")
            ?: return emptyList()
        return albums.objects().mapNotNull { o ->
            val images = o.optJSONArray("image")?.objects().orEmpty()
            val best = images.lastOrNull { it.optString("#text").isNotEmpty() }?.optString("#text") ?: return@mapNotNull null
            CoverCandidate(
                source = "Last.fm",
                artist = o.optString("artist"),
                album = o.optString("name"),
                thumbUrl = best,
                fullUrls = listOf(best.replace(Regex("/i/u/[^/]+/"), "/i/u/"), best),
            )
        }
    }

    /** Quotes would break the Lucene-style queries. */
    private fun String.clean() = replace("\"", " ").trim()
}

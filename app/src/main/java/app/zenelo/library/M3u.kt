package app.zenelo.library

import java.net.URLDecoder
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * M3U / M3U8 playlists: parsing (paths absolute, relative to the playlist, `file://` URIs,
 * Windows separators) and writing (`#EXTM3U` + `#EXTINF`, paths relative to the playlist's folder
 * when both are on the same storage volume, so the folder tree can be copied to a PC as is).
 */
object M3u {
    data class Entry(val location: String, val title: String?, val durationSec: Int?)

    data class Track(val path: String, val artist: String?, val title: String?, val durationMs: Long?)

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var title: String? = null
        var duration: Int? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substring(8)
                    duration = info.substringBefore(',').trim().substringBefore(' ').toIntOrNull()?.takeIf { it > 0 }
                    title = info.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += Entry(line, title, duration)
                    title = null
                    duration = null
                }
            }
        }
        return entries
    }

    /**
     * The absolute, normalized path [location] points to, read from a playlist in [baseDir] (null
     * when unknown: relative entries then can't be resolved here). Null for URLs.
     */
    fun resolve(location: String, baseDir: String?): String? {
        var loc = location.replace('\\', '/')
        if (loc.startsWith("file:", ignoreCase = true)) {
            loc = runCatching { URLDecoder.decode(loc.substring(5).replace("+", "%2B"), "UTF-8") }.getOrDefault(loc.substring(5))
            loc = "/" + loc.trimStart('/')
        } else if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(loc)) {
            return null
        }
        // "C:/Music/..." from a Windows player: only the tail can match, see [bestMatch].
        if (Regex("^[a-zA-Z]:/").containsMatchIn(loc)) return null
        val absolute = when {
            loc.startsWith("/") -> loc
            baseDir != null -> "${baseDir.trimEnd('/')}/$loc"
            else -> return null
        }
        return normalize(absolute)
    }

    /**
     * For an entry that isn't at [resolve]'s path: the library file whose path shares the longest
     * tail with [location] (file name at least), e.g. "..\Music\Air\Moon Safari\01 La femme.flac"
     * → "/storage/emulated/0/Music/Air/Moon Safari/01 La femme.flac". Ties → null (ambiguous).
     */
    fun bestMatch(location: String, candidates: List<String>): String? {
        val want = segments(location)
        if (want.isEmpty()) return null
        val scored = candidates.map { it to commonTail(want, segments(it)) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /** The name the tail lookup searches the index by. */
    fun fileName(location: String): String = segments(location).lastOrNull().orEmpty()

    fun write(tracks: List<Track>, baseDir: String?): String = buildString {
        append("#EXTM3U\n")
        for (t in tracks) {
            val seconds = t.durationMs?.takeIf { it > 0 }?.let { (it + 500) / 1000 } ?: -1
            val name = listOfNotNull(t.artist, t.title).joinToString(" - ").ifEmpty { t.path.substringAfterLast('/').substringBeforeLast('.') }
            append("#EXTINF:").append(seconds).append(',').append(name).append('\n')
            append(relativize(t.path, baseDir)).append('\n')
        }
    }

    /** [path] relative to [baseDir] when both are on one volume (/storage/<volume>/...), else as is. */
    fun relativize(path: String, baseDir: String?): String {
        if (baseDir == null || volume(path) == null || volume(path) != volume(baseDir)) return path
        return try {
            Paths.get(baseDir).relativize(Paths.get(path)).toString().replace('\\', '/')
        } catch (e: IllegalArgumentException) {
            path
        } catch (e: InvalidPathException) {
            path
        }
    }

    private fun volume(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> "/storage/emulated/${parts[2]}"
            parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"
            else -> parts.firstOrNull()?.let { "/$it" }
        }
    }

    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    private fun segments(path: String) =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }

    private fun commonTail(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n].equals(b[b.size - 1 - n], ignoreCase = true)) n++
        return n
    }
}

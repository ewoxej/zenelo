package app.zenelo.library

/**
 * Splits an artist tag naming several artists ("A; B", "A / B", "A, B feat. C") into names.
 *
 * Separators match ignoring case. One that starts or ends with a letter or digit ("feat.", "x")
 * only matches as a whole word, so "feat." doesn't cut "defeat." and "x" doesn't cut "Xzibit".
 * [exceptions] are names never cut, even though they contain a separator ("AC/DC").
 */
class ArtistSplitter(separators: List<String>, exceptions: List<String>) {
    private val separators = separators.map(String::trim).filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
    private val exceptions = exceptions.map(String::trim).filter(String::isNotEmpty)

    fun split(value: String?): List<String> {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (separators.isEmpty()) return listOf(text)
        val keep = BooleanArray(text.length)
        for (name in exceptions) {
            var at = text.indexOf(name, ignoreCase = true)
            while (at >= 0) {
                for (i in at until at + name.length) keep[i] = true
                at = text.indexOf(name, at + name.length, ignoreCase = true)
            }
        }
        val parts = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val sep = if (keep[i]) null else separators.firstOrNull { matchesAt(text, i, it) && !keep.anyIn(i, it.length) }
            if (sep == null) {
                i++
                continue
            }
            parts += text.substring(start, i)
            i += sep.length
            start = i
        }
        parts += text.substring(start)
        return parts.map(String::trim).filter(String::isNotEmpty).distinctBy(::artistKey)
    }

    private fun matchesAt(text: String, at: Int, sep: String): Boolean {
        if (!text.regionMatches(at, sep, 0, sep.length, ignoreCase = true)) return false
        if (sep.first().isLetterOrDigit() && at > 0 && text[at - 1].isLetterOrDigit()) return false
        val end = at + sep.length
        if (sep.last().isLetterOrDigit() && end < text.length && text[end].isLetterOrDigit()) return false
        return true
    }

    private fun BooleanArray.anyIn(from: Int, length: Int) = (from until minOf(size, from + length)).any { this[it] }

    override fun equals(other: Any?) =
        other is ArtistSplitter && other.separators == separators && other.exceptions == exceptions

    override fun hashCode() = separators.hashCode() * 31 + exceptions.hashCode()

    companion object {
        val DEFAULT_SEPARATORS = listOf(";", "/", ",")
        val DEFAULT_EXCEPTIONS = listOf("AC/DC", "Earth, Wind & Fire", "Tyler, the Creator")
    }
}

/** An artist's library key (route, favorites): the name, trimmed and lowercased. */
fun artistKey(name: String): String = name.trim().lowercase()

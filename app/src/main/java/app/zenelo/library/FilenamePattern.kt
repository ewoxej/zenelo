package app.zenelo.library

/** Artist / album / title / track number guessed from a file path. */
data class PathGuess(val artist: String?, val album: String?, val title: String?, val number: Int? = null)

/**
 * File name patterns like `%artist% - %title%` or `%artist%/%album%/%number% %title%`.
 *
 * Tokens: `%artist%`, `%album%`, `%title%` (alias `%track%`), `%number%` (digits), `%any%` (skipped).
 * `[...]` marks an optional part: `[%number%. ]%artist% - %title%` matches "03. A - B" and "A - B".
 * `/` separates path segments; a pattern matches the last segments of the path, the file name
 * without its extension last. Matching ignores case and extra spaces around separators.
 */
object FilenamePattern {
    private val token = Regex("%(artist|album|title|track|number|any)%", RegexOption.IGNORE_CASE)

    fun parse(pattern: String, path: String): PathGuess? {
        val patternSegments = pattern.trim().split('/').filter { it.isNotBlank() }
        if (patternSegments.isEmpty()) return null
        val pathSegments = path.split('/').filter { it.isNotEmpty() }.toMutableList()
        if (pathSegments.size < patternSegments.size) return null
        pathSegments[pathSegments.lastIndex] = pathSegments.last().substringBeforeLast('.')
        val tail = pathSegments.takeLast(patternSegments.size)

        val values = HashMap<String, String>()
        for ((segmentPattern, segment) in patternSegments.zip(tail)) {
            val (regex, names) = compile(segmentPattern) ?: return null
            val match = regex.matchEntire(segment.trim()) ?: return null
            names.forEachIndexed { i, name ->
                // A skipped optional part leaves its group empty; don't let it erase a value found elsewhere.
                val value = match.groupValues[i + 1].trim()
                if (value.isNotEmpty() || name !in values) values[name] = value
            }
        }
        val guess = PathGuess(
            artist = values["artist"]?.takeIf { it.isNotEmpty() },
            album = values["album"]?.takeIf { it.isNotEmpty() },
            title = (values["title"]?.takeIf { it.isNotEmpty() } ?: values["track"])?.takeIf { it.isNotEmpty() },
            number = values["number"]?.toIntOrNull(),
        )
        return guess.takeIf { it.artist != null && (it.title != null || it.album != null) }
    }

    /** First matching pattern wins. */
    fun guess(patterns: List<String>, path: String): PathGuess? = patterns.firstNotNullOfOrNull { parse(it, path) }

    /** Regex for one path segment and the token names of its groups, in order; null if brackets don't balance. */
    private fun compile(segment: String): Pair<Regex, List<String>>? {
        val names = ArrayList<String>()
        val sb = StringBuilder()
        val text = StringBuilder()
        var depth = 0
        fun flush() {
            sb.append(literal(text.toString()))
            text.clear()
        }
        var i = 0
        while (i < segment.length) {
            val m = token.matchAt(segment, i)
            when {
                m != null -> {
                    flush()
                    val name = m.groupValues[1].lowercase()
                    names += name
                    sb.append(if (name == "number") "(\\d+)" else "(.+?)")
                    i = m.range.last + 1
                    continue
                }
                segment[i] == '[' -> {
                    flush()
                    sb.append("(?:")
                    depth++
                }
                segment[i] == ']' -> {
                    if (depth == 0) return null
                    flush()
                    sb.append(")?")
                    depth--
                }
                else -> text.append(segment[i])
            }
            i++
        }
        flush()
        if (depth != 0) return null
        return Regex(sb.toString(), RegexOption.IGNORE_CASE) to names
    }

    /** Literal text with flexible whitespace: " - " also matches "-" and "  -  ". */
    private fun literal(text: String): String =
        text.split(Regex("\\s+")).joinToString("\\s*") { Regex.escape(it).takeIf { e -> it.isNotEmpty() } ?: "" }
}

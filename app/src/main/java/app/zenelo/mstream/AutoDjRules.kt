package app.zenelo.mstream

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pure parts of Auto DJ, as in mStream's web app (`webapp/alpha/auto-dj.js`): the Camelot
 * wheel for harmonic mixing, BPM windows (normal / half / double tempo) and the check of a pick
 * against the session's anchors.
 */
object AutoDjRules {
    private val CAMELOT = mapOf(
        "Ab minor" to "1A", "G# minor" to "1A", "B major" to "1B",
        "Eb minor" to "2A", "D# minor" to "2A", "F# major" to "2B", "Gb major" to "2B",
        "Bb minor" to "3A", "A# minor" to "3A", "Db major" to "3B", "C# major" to "3B",
        "F minor" to "4A", "Ab major" to "4B", "G# major" to "4B",
        "C minor" to "5A", "Eb major" to "5B", "D# major" to "5B",
        "G minor" to "6A", "Bb major" to "6B", "A# major" to "6B",
        "D minor" to "7A", "F major" to "7B",
        "A minor" to "8A", "C major" to "8B",
        "E minor" to "9A", "G major" to "9B",
        "B minor" to "10A", "D major" to "10B",
        "F# minor" to "11A", "A major" to "11B",
        "C# minor" to "12A", "E major" to "12B",
    )
    private val CODE = Regex("^([1-9]|1[0-2])[AB]$")
    private val SHORT = Regex("^([A-G][#b]?)\\s*(m|min|minor|maj|major)?$")

    /**
     * The Camelot code of a key tag: "A minor" / "Am" / "Amin" → 8A, "C" / "C major" → 8B, a code
     * as it is; null if it isn't a key. (The server takes codes and expands them to every spelling.)
     */
    fun toCamelot(key: String?): String? {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return null
        CAMELOT[k]?.let { return it }
        if (CODE.matches(k.uppercase())) return k.uppercase()
        val m = SHORT.matchEntire(k) ?: return null
        val minor = m.groupValues[2].let { it == "m" || it.startsWith("min") }
        return CAMELOT["${m.groupValues[1]} ${if (minor) "minor" else "major"}"]
    }

    /** The code itself, its relative major / minor and both neighbours in each: six codes. */
    fun neighbours(code: String): Set<String> {
        val num = code.dropLast(1).toInt()
        val letter = code.last()
        val other = if (letter == 'A') 'B' else 'A'
        val prev = (num + 10) % 12 + 1
        val next = num % 12 + 1
        return setOf("$num$letter", "$num$other", "$prev$letter", "$prev$other", "$next$letter", "$next$other")
    }

    /** BPM windows around [bpm] ± [tolerance], at half and double tempo too, within 20–300. */
    fun bpmRanges(bpm: Double, tolerance: Int): List<IntRange> {
        if (bpm < 20 || bpm > 300) return emptyList()
        val t = tolerance.toDouble()
        return listOf(bpm - t to bpm + t, bpm / 2 - t / 2 to bpm / 2 + t / 2, bpm * 2 - t * 2 to bpm * 2 + t * 2)
            .filter { (lo, hi) -> hi >= 20 && lo <= 300 }
            .map { (lo, hi) -> maxOf(20, lo.roundToInt())..minOf(300, hi.roundToInt()) }
            .filter { !it.isEmpty() }
    }

    /** The session's tempo: the rounded average of the last picks' BPM. */
    fun average(bpms: Collection<Double>): Double? = if (bpms.isEmpty()) null else bpms.average().roundToInt().toDouble()

    /**
     * A skip word in the pick's title, artist, album or path, as the web app matches them:
     * lowercase with repeated letters collapsed ("acapella" = "acappella", "trax" = "traxxx").
     */
    fun hasSkipWord(song: ServerSong, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val text = fold(listOfNotNull(song.title, song.artist, song.album, song.filepath).joinToString(" "))
        return words.map(::fold).any { it.isNotBlank() && it in text }
    }

    private val REPEATS = Regex("(.)\\1+")

    private fun fold(s: String) = s.lowercase().replace(REPEATS, "$1")

    /**
     * Whether a pick breaks the session's tempo / key (the server may relax them when nothing
     * fits). Untagged picks pass: the server already ran out of tagged ones.
     */
    fun blocked(song: ServerSong, refBpm: Double?, tolerance: Int, keys: Set<String>?): Boolean {
        val bpm = song.bpm
        if (refBpm != null && bpm != null) {
            val t = tolerance.toDouble()
            val fits = abs(bpm - refBpm) <= t || abs(bpm - refBpm / 2) <= t / 2 || abs(bpm - refBpm * 2) <= t * 2
            if (!fits) return true
        }
        if (keys != null) {
            val code = toCamelot(song.musicalKey)
            if (code != null && code !in keys) return true
        }
        return false
    }
}

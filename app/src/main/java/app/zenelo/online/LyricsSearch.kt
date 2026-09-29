package app.zenelo.online

import app.zenelo.library.ArtistSplitter
import kotlin.math.abs

/**
 * What to ask LRCLIB for and which answer to take. Tags often carry more than the lyrics site
 * lists: "Song - Unplugged в подъезде", "Song (feat. X)", "Song [muzhub.net]", "A feat. B",
 * "A; B". So queries go from the tags as they are to cleaned-up variants, and an answer counts
 * only when its title and artist match ours after the same clean-up.
 */
object LyricsSearch {
    /** One LRCLIB record. */
    data class Candidate(
        val artist: String,
        val title: String,
        val durationSec: Double?,
        val synced: String?,
        val plain: String?,
        val instrumental: Boolean,
    )

    /** [exact]: same length as our file (± [DURATION_SLACK_SEC]), so its timing fits. */
    data class Match(val lyrics: FoundLyrics, val exact: Boolean)

    const val DURATION_SLACK_SEC = 3.0

    private val brackets = Regex("\\s*\\[[^]]*]")
    private val featGroup = Regex("\\s*[(\\[](?:feat\\.?|ft\\.?|featuring|with|при уч\\.?|при участии)\\s[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val featTail = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val parens = Regex("\\s*\\([^)]*\\)")
    private val dashTail = Regex("\\s+[-–—]\\s+.*$")

    /** From the title as tagged to its core ("Синячу - Unplugged в подъезде" → "Синячу"). */
    fun titleVariants(title: String): List<String> {
        val original = title.trim()
        val noBrackets = original.replace(brackets, "").trim()
        val noFeat = noBrackets.replace(featGroup, "").replace(featTail, "").trim()
        val noParens = noFeat.replace(parens, "").trim()
        val core = noParens.replace(dashTail, "").trim()
        return listOf(original, noBrackets, noFeat, noParens, core).filter(String::isNotBlank).distinctBy(::normalize)
    }

    /** Track artist, it without "feat. …", the first of a multi-artist tag, then the album artist. */
    fun artistVariants(artist: String?, albumArtist: String?, splitter: ArtistSplitter): List<String> {
        val out = mutableListOf<String>()
        artist?.trim()?.takeIf(String::isNotEmpty)?.let { a ->
            out += a.replace(brackets, "").trim()
            out += a.replace(featTail, "").trim()
            splitter.split(a.replace(featTail, "")).firstOrNull()?.let(out::add)
        }
        albumArtist?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Various Artists", ignoreCase = true) }?.let(out::add)
        return out.filter(String::isNotBlank).distinctBy(::normalize)
    }

    /** Lowercase letters and digits only, ё as е, single spaces. */
    fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * The best record for our track among [results], or null. Title and artist must match one of
     * [titles] / [artists] after clean-up. Same length ⇒ exact (synced first). Otherwise the
     * closest length, as plain text only: another version's timing wouldn't fit.
     */
    fun pick(results: List<Candidate>, titles: List<String>, artists: List<String>, durationSec: Int): Match? {
        val wantTitles = titles.flatMap { titleVariants(it) }.map(::normalize).toSet()
        val wantArtists = artists.map(::normalize).filter(String::isNotEmpty)
        val matching = results.filter { c ->
            (c.synced != null || c.plain != null || c.instrumental) &&
                titleVariants(c.title).any { normalize(it) in wantTitles } &&
                artistMatches(normalize(c.artist), wantArtists)
        }
        if (matching.isEmpty()) return null
        if (durationSec <= 0) return Match(matching.sortedByDescending { it.synced != null }.first().toLyrics(), exact = true)
        val sameLength = matching.filter { c -> c.durationSec?.let { abs(it - durationSec) <= DURATION_SLACK_SEC } == true }
        if (sameLength.isNotEmpty()) return Match(sameLength.sortedByDescending { it.synced != null }.first().toLyrics(), exact = true)
        val closest = matching.filter { !it.instrumental && (it.plain != null || it.synced != null) }
            .minByOrNull { c -> c.durationSec?.let { abs(it - durationSec) } ?: Double.MAX_VALUE } ?: return null
        return Match(FoundLyrics(synced = null, plain = closest.plain ?: stripTimestamps(closest.synced!!), instrumental = false), exact = false)
    }

    /** "Альбина Сексова" matches "Альбина Сексова"; "Ежемесячные" matches "СД feat. Ежемесячные" too. */
    private fun artistMatches(found: String, wanted: List<String>): Boolean =
        wanted.any { w -> found == w || (" $found ").contains(" $w ") || (" $w ").contains(" $found ") }

    /** LRC to plain text: timestamps and [ar:…]-style tags dropped. */
    fun stripTimestamps(lrc: String): String =
        lrc.lineSequence()
            .map { it.replace(Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]"), "").trim() }
            .filterNot { Regex("^\\[[a-z]+:.*]$", RegexOption.IGNORE_CASE).matches(it) }
            .joinToString("\n")
            .trim()

    private fun Candidate.toLyrics() = FoundLyrics(synced, plain, instrumental)
}

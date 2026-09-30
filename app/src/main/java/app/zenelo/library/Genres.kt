package app.zenelo.library

import org.jaudiotagger.tag.reference.GenreTypes

/**
 * Genre tags into genres: several in one tag ("Rock; Indie", "Rock/Pop", "Jazz, Soul") count for
 * each; ID3v1 numbers ("(17)", "17") become their names ("Rock"). Keyed like artists (lowercase),
 * shown in one of the spellings.
 */
object Genres {
    private val SEPARATORS = Regex("\\s*[;/,|\\\\]\\s*")
    private val ID3_NUMBER = Regex("^\\((\\d{1,3})\\)(.*)$|^(\\d{1,3})$")

    fun split(tag: String?): List<String> {
        if (tag.isNullOrBlank()) return emptyList()
        return tag.split(SEPARATORS).mapNotNull { part -> name(part.trim()) }.distinctBy(::key)
    }

    fun key(name: String): String = name.trim().lowercase()

    private fun name(part: String): String? {
        if (part.isEmpty()) return null
        val m = ID3_NUMBER.matchEntire(part) ?: return part
        // "(17)Rock" keeps the text after the number; a bare number looks up the ID3v1 table.
        m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { return it }
        val id = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt()
        return runCatching { GenreTypes.getInstanceOf().getValueForId(id) }.getOrNull()
    }
}

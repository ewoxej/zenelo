package app.zenelo.mstream

/**
 * The tracks offered when an end of the Sonic Path is picked on its screen (before anything is
 * typed): the playing track, what's up next in the queue, recent plays, then favorites — tracks
 * the user has in mind lately. Only tracks the path can use (on the server, local ones through
 * their link) and in the library; each once, the first group wins; never the other end.
 */
object SonicPathSuggestions {
    data class Group(val title: String, val paths: List<String>)

    fun of(
        playing: String?,
        upNext: List<String>,
        recent: List<String>,
        favorites: List<String>,
        /** On the server (a server track, or a local one linked to it) and shown in the library. */
        usable: (String) -> Boolean,
        /** The path already at the other end. */
        exclude: String?,
        perGroup: Int = 15,
    ): List<Group> {
        val seen = HashSet<String>()
        exclude?.let(seen::add)
        fun group(title: String, paths: List<String>, limit: Int): Group? {
            val picked = paths.asSequence().filter { usable(it) && seen.add(it) }.take(limit).toList()
            return picked.takeIf { it.isNotEmpty() }?.let { Group(title, it) }
        }
        return listOfNotNull(
            group("Playing", listOfNotNull(playing), 1),
            group("Up next", upNext, perGroup),
            group("Recently played", recent, perGroup),
            group("Favorites", favorites, perGroup),
        )
    }
}

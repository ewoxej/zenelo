package app.zenelo.data.settings

/** A top-level page: a bottom bar tab, a Home section, or both. */
enum class Section(val label: String) {
    HOME("Home"),
    FOLDERS("Folders"),
    FAVORITES("Favorites"),
    PLAYLISTS("Playlists"),
    SETTINGS("Settings"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    TRACKS("All tracks"),
    RECENT("Recently played"),
    ;

    /** Settings has no content to show in a card, only a shortcut. */
    val hasCard: Boolean get() = this != SETTINGS && this != HOME
}

/** How a section shows on Home: not at all, as a shortcut icon, or as a card. */
enum class HomeMode(val label: String) { OFF("Off"), ICON("Icon"), GRID("Grid"), LIST("List") }

data class HomeItem(val section: Section, val mode: HomeMode)

/** Library pages cycle through these with the round button bottom left. */
enum class LibraryView {
    GRID2, GRID3, LIST;

    fun next(): LibraryView = entries[(ordinal + 1) % entries.size]
}

enum class SortField(val label: String, val ascending: String, val descending: String) {
    NAME("Name", "A → Z", "Z → A"),
    ARTIST("Artist", "A → Z", "Z → A"),
    ALBUM("Album", "A → Z", "Z → A"),
    DATE_ADDED("Date added", "Oldest first", "Newest first"),
    TRACKS("Number of tracks", "Fewest first", "Most first"),
    ALBUMS("Number of albums", "Fewest first", "Most first"),
    DURATION("Duration", "Shortest first", "Longest first"),
}

data class SortOrder(val sortBy: SortField, val descending: Boolean = false) {
    /** "BY DATE ADDED · NEWEST FIRST" */
    val label: String get() = "By ${sortBy.label} · ${if (descending) sortBy.descending else sortBy.ascending}".uppercase()

    fun encode() = "${sortBy.name}:${if (descending) "desc" else "asc"}"

    companion object {
        fun decode(raw: String?): SortOrder? {
            val (field, dir) = raw?.split(':')?.takeIf { it.size == 2 } ?: return null
            val f = SortField.entries.firstOrNull { it.name == field } ?: return null
            return SortOrder(f, dir == "desc")
        }
    }
}

/** Pages with a sort menu, the fields that make sense there, and where they start. */
enum class SortPage(val fields: List<SortField>, val default: SortOrder) {
    BROWSER(
        listOf(SortField.NAME, SortField.ARTIST, SortField.ALBUM, SortField.DATE_ADDED, SortField.DURATION, SortField.TRACKS),
        SortOrder(SortField.NAME),
    ),
    ALBUMS(
        listOf(SortField.NAME, SortField.ARTIST, SortField.DATE_ADDED, SortField.TRACKS),
        SortOrder(SortField.DATE_ADDED, descending = true),
    ),
    ARTISTS(
        listOf(SortField.NAME, SortField.DATE_ADDED, SortField.TRACKS, SortField.ALBUMS),
        SortOrder(SortField.NAME),
    ),
    TRACKS(
        listOf(SortField.NAME, SortField.ARTIST, SortField.ALBUM, SortField.DATE_ADDED, SortField.DURATION),
        SortOrder(SortField.NAME),
    ),
}

object Navigation {
    const val MAX_TABS = 5

    val DEFAULT_TABS = listOf(Section.HOME, Section.FOLDERS, Section.FAVORITES, Section.PLAYLISTS, Section.SETTINGS)

    /** The fixed bar before tabs were configurable; kept for installs upgrading from it. */
    val LEGACY_TABS = listOf(Section.FOLDERS, Section.FAVORITES, Section.PLAYLISTS, Section.SETTINGS)

    val DEFAULT_HOME = listOf(
        HomeItem(Section.RECENT, HomeMode.GRID),
        HomeItem(Section.ALBUMS, HomeMode.GRID),
        HomeItem(Section.ARTISTS, HomeMode.LIST),
        HomeItem(Section.TRACKS, HomeMode.LIST),
        HomeItem(Section.FOLDERS, HomeMode.ICON),
        HomeItem(Section.FAVORITES, HomeMode.ICON),
        HomeItem(Section.PLAYLISTS, HomeMode.ICON),
        HomeItem(Section.SETTINGS, HomeMode.ICON),
    )

    /** Every Home section except Home itself, in [home]'s order, missing ones appended (off). */
    fun completeHome(home: List<HomeItem>): List<HomeItem> {
        val known = home.filter { it.section != Section.HOME }.distinctBy { it.section }
        val missing = Section.entries.filter { s -> s != Section.HOME && known.none { it.section == s } }
        return known + missing.map { HomeItem(it, HomeMode.OFF) }
    }

    /** The pages the app shows: the tabs, or Home alone when the bar is empty. */
    fun pages(tabs: List<Section>): List<Section> = tabs.ifEmpty { listOf(Section.HOME) }

    /**
     * Settings must stay reachable: as a tab, or as a Home shortcut while Home is a page
     * (a tab, or the only page when the bar is empty).
     */
    fun settingsReachable(tabs: List<Section>, home: List<HomeItem>): Boolean {
        if (Section.SETTINGS in tabs) return true
        val inHome = home.any { it.section == Section.SETTINGS && it.mode != HomeMode.OFF }
        return inHome && Section.HOME in pages(tabs)
    }
}

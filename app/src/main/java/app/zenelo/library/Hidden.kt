package app.zenelo.library

import app.zenelo.data.db.HiddenEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.library.ScanScope.Companion.under
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What "Hide" left out: tracks by path and folders with everything in them. A track is hidden
 * under either of its paths ([hides]' copies: a server track's local copy and the other way round),
 * so hiding it in one view hides its twin in another.
 */
class Hidden(val paths: Set<String> = emptySet(), val folders: List<String> = emptyList()) {
    val isEmpty: Boolean get() = paths.isEmpty() && folders.isEmpty()

    fun hides(path: String, copies: List<String> = emptyList()): Boolean {
        if (isEmpty) return false
        if (path in paths || copies.any { it in paths }) return true
        return folders.any { path.under(it) } || copies.any { c -> folders.any { c.under(it) } }
    }

    /** A folder in the browser (its index path) is hidden itself or lies in a hidden one. */
    fun hidesFolder(indexPath: String): Boolean = folders.any { indexPath.under(it) }

    companion object {
        fun of(rows: List<HiddenEntity>) = Hidden(
            rows.filterNot { it.folder }.mapTo(HashSet()) { it.path },
            rows.filter { it.folder }.map { it.path },
        )
    }
}

/** The hidden tracks and folders (`hidden` table): hide, show again, and the current [Hidden] set. */
class HiddenTracks(private val db: ZeneloDatabase, scope: CoroutineScope) {
    val rows = db.hidden().observeAll()

    val current: StateFlow<Hidden> = rows.map(Hidden::of).stateIn(scope, SharingStarted.Eagerly, Hidden())

    suspend fun hide(items: List<HiddenEntity>) {
        if (items.isNotEmpty()) db.hidden().insertAll(items)
    }

    /** Hides tracks, named from the index (the file name when not indexed). Returns what to undo. */
    suspend fun hideTracks(paths: List<String>): List<String> {
        val distinct = paths.distinct()
        val known = db.tracks().getMany(distinct).associateBy { it.path }
        hide(
            distinct.map { p ->
                val t = known[p]
                HiddenEntity(p, t?.let(LibrarySort::trackName) ?: p.substringAfterLast('/').substringBeforeLast('.'), t?.artistTag)
            },
        )
        return distinct
    }

    /** Hides a folder (its index path: `mstream://…` for the server's) with everything in it. */
    suspend fun hideFolder(indexPath: String, name: String): List<String> {
        hide(listOf(HiddenEntity(indexPath, name, indexPath.substringBeforeLast('/'), folder = true)))
        return listOf(indexPath)
    }

    suspend fun show(paths: List<String>) {
        if (paths.isNotEmpty()) db.hidden().delete(paths)
    }

    suspend fun showAll() = db.hidden().deleteAll()
}

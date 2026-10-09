package app.zenelo.library

import java.io.File

/** Which folders the library scan covers (setting). */
enum class ScanMode(val label: String) {
    ALL("All storage"),
    ONLY("Only these folders"),
    EXCEPT("All but these folders"),
}

/**
 * The library scan's reach: the whole storage, only [include] (whitelist) or all but [exclude]
 * (blacklist), and — with [nomedia] — never a folder holding a `.nomedia` file, nor anything inside
 * it (Android's way of hiding a folder from media apps). Both lists are kept, so switching the
 * mode loses neither.
 */
data class ScanScope(
    val mode: ScanMode = ScanMode.ALL,
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val nomedia: Boolean = true,
    /** Always scanned whatever the lists say: the mStream download folder (not a setting). */
    val always: Set<String> = emptySet(),
) {
    /**
     * The mode in effect: an empty whitelist scans everything until a folder is added — switching
     * to it mustn't empty the library (and make it read every tag again once a folder is in).
     */
    val effectiveMode: ScanMode get() = if (mode == ScanMode.ONLY && include.isEmpty()) ScanMode.ALL else mode

    /** Folders the scan walks: the storage [volumes], or the whitelist's folders on them (outermost only). */
    fun roots(volumes: List<File>): List<File> = when (effectiveMode) {
        ScanMode.ONLY -> outermost(include + always).map(::File).filter { dir -> volumes.any { dir.path.under(it.path) } }
        else -> volumes
    }

    /** A folder the scan skips with everything in it, judged by its path ([nomedia] is checked by [skips]). */
    fun excluded(path: String): Boolean =
        mode == ScanMode.EXCEPT && exclude.any { path.under(it) } && always.none { path.under(it) }

    /**
     * Whether a local track at [path] shows in the library, by the lists alone (no disk access:
     * `.nomedia` folders are left out by the scan). Files indexed outside the scope meanwhile —
     * played or edited from the browser — stay out until the next scan drops them.
     */
    fun inLibrary(path: String): Boolean = when (effectiveMode) {
        ScanMode.ALL -> true
        ScanMode.ONLY -> (include + always).any { path.under(it) }
        ScanMode.EXCEPT -> !excluded(path)
    }

    /** A folder (or file) the scan reaches by its path alone: inside the roots, not blacklisted. */
    fun covers(path: String, volumes: List<File>): Boolean =
        roots(volumes).any { path.under(it.path) } && !excluded(path)

    /** The walk leaves [dir] out: blacklisted, or it holds `.nomedia` (when that counts). */
    fun skips(dir: File): Boolean = excluded(dir.path) || (nomedia && File(dir, NOMEDIA).exists())

    /**
     * Whether files of [dir] belong in the library (the browser indexes the folder on screen only
     * then): covered, and no `.nomedia` in it or any folder above it up to its storage volume.
     */
    fun allows(dir: File, volumes: List<File>): Boolean {
        if (!covers(dir.path, volumes)) return false
        if (!nomedia) return true
        val volume = volumes.firstOrNull { dir.path.under(it.path) } ?: return false
        var d: File? = dir
        while (d != null && d.path.under(volume.path)) {
            if (File(d, NOMEDIA).exists()) return false
            if (d.path == volume.path) break
            d = d.parentFile
        }
        return true
    }

    companion object {
        const val NOMEDIA = ".nomedia"

        /** [path] is [dir] or inside it (path-wise: "/a/bc" isn't inside "/a/b"). */
        fun String.under(dir: String): Boolean {
            val d = dir.trimEnd('/')
            return this == d || startsWith("$d/")
        }

        /** The folders not inside another one of the set. */
        fun outermost(folders: Set<String>): List<String> {
            val trimmed = folders.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
            return trimmed.filter { f -> trimmed.none { other -> other != f && f.under(other) } }.sorted()
        }
    }
}

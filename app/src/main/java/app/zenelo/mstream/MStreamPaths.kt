package app.zenelo.mstream

/**
 * Tracks on the mStream server live in our index under `mstream://<vpath>/<rel>`: the server's
 * virtual path, so one string is both our key (mediaId, favorites, playlists, plays) and what the
 * server's API takes.
 */
object MStreamPaths {
    const val SCHEME = "mstream://"

    fun isRemote(path: String?): Boolean = path != null && path.startsWith(SCHEME)

    /** Our path for a server file path (`music/Artist/Album/01.flac`). */
    fun of(serverPath: String): String = SCHEME + serverPath.trimStart('/')

    /** The server's path (`<vpath>/<rel>`) for one of our `mstream://` paths. */
    fun serverPath(path: String): String = path.removePrefix(SCHEME)

    /** "01.flac" of `mstream://music/Artist/Album/01.flac`. */
    fun fileName(path: String): String = path.substringAfterLast('/')

    /** Parent folder, in our form: `mstream://music/Artist/Album`. */
    fun dir(path: String): String = path.substringBeforeLast('/')
}

/**
 * The server's folders in the folder browser: a virtual root `/mstream`, with `/mstream/<vpath>/…`
 * standing for the index's `mstream://<vpath>/…` dirs. Only a path holder: never touched on disk.
 */
object RemoteFolders {
    val ROOT = java.io.File("/mstream")

    fun isRemote(dir: java.io.File?): Boolean = dir != null && (dir == ROOT || dir.path.startsWith(ROOT.path + "/"))

    /** `/mstream/music/A` → `mstream://music/A`; the root → `mstream:/` (a prefix only). */
    fun indexDir(dir: java.io.File): String =
        if (dir == ROOT) MStreamPaths.SCHEME.dropLast(1) else MStreamPaths.SCHEME + dir.path.removePrefix(ROOT.path + "/")

    /** `mstream://music/A` → `/mstream/music/A`. */
    fun folder(indexDir: String): java.io.File =
        if (indexDir.length <= MStreamPaths.SCHEME.length) ROOT else java.io.File(ROOT, indexDir.removePrefix(MStreamPaths.SCHEME))

    /** The index dir for any folder: remote ones mapped, local ones as they are. */
    fun indexPath(dir: java.io.File): String = if (isRemote(dir)) indexDir(dir) else dir.absolutePath

    /** A folder path for an index dir (inverse of [indexPath]). */
    fun folderPath(indexDir: String): String = if (MStreamPaths.isRemote(indexDir)) folder(indexDir).path else indexDir
}

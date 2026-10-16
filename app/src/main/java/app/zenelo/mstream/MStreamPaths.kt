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

    /**
     * A federated peer's track, browsed and played through our server (never in the index):
     * `mstream://@peer/<peer id>/<the peer's vpath>/<rel>`. Library names are letters, digits and
     * dashes, so `@peer` never names one of ours. Such a path is remote (no file here) but not our
     * server's: [isServer] is false and [ServerLinks] gives it no server track.
     */
    private const val PEER = "@peer/"

    fun isPeer(path: String?): Boolean = path != null && path.startsWith(SCHEME + PEER)

    /** One of our server's tracks (not a peer's). */
    fun isServer(path: String?): Boolean = isRemote(path) && !isPeer(path)

    fun peer(peerId: Long, peerPath: String): String = SCHEME + PEER + peerId + "/" + peerPath.trimStart('/')

    /** The peer of a peer path (or folder index dir), null for anything else. */
    fun peerId(path: String): Long? = if (isPeer(path)) path.removePrefix(SCHEME + PEER).substringBefore('/').toLongOrNull() else null

    /** The peer's own path (`<vpath>/<rel>`) of a peer path; "" for the peer's root. */
    fun peerPath(path: String): String = path.removePrefix(SCHEME + PEER).substringAfter('/', "")
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

    /** A peer's root folder in the browser (`/mstream/@peer/<id>`): its libraries below. */
    fun peerRoot(peerId: Long): java.io.File = java.io.File(ROOT, "@peer/$peerId")

    fun isPeer(dir: java.io.File?): Boolean = dir != null && isRemote(dir) && MStreamPaths.isPeer(indexDir(dir))

    /** The index dir for any folder: remote ones mapped, local ones as they are. */
    fun indexPath(dir: java.io.File): String = if (isRemote(dir)) indexDir(dir) else dir.absolutePath

    /** A folder path for an index dir (inverse of [indexPath]). */
    fun folderPath(indexDir: String): String = if (MStreamPaths.isRemote(indexDir)) folder(indexDir).path else indexDir
}

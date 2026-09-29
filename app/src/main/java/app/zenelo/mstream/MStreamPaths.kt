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

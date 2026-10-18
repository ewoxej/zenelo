package app.zenelo.library

import app.zenelo.library.TagReader.EditableTags
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.MStreamTags

/**
 * Tag editor backend. Local writes go through [PendingWrites]: the playing file gets its tags when
 * the track ends, but every screen shows the edit immediately, so to the user it's always "saved".
 * The server's tracks are written into the server's files ([MStreamTags]).
 */
class TagWriter(private val pending: PendingWrites, private val server: MStreamTags) {
    /** The tags to edit; null when this file's can't be. Throws when the server can't be reached. */
    suspend fun read(path: String): EditableTags? =
        if (MStreamPaths.isServer(path)) server.read(path) else pending.readTags(path)

    /** Saves [tags]: null when saved, else why it couldn't. */
    suspend fun write(path: String, tags: EditableTags): String? =
        if (MStreamPaths.isServer(path)) {
            server.write(path, tags).exceptionOrNull()?.let { it.message ?: "Couldn't write the tags on the server" }
        } else if (pending.writeTags(path, tags) == PendingWrites.Result.FAILED) {
            "Couldn't write tags to this file"
        } else {
            null
        }
}

package app.zenelo.library

import app.zenelo.library.TagReader.EditableTags

/**
 * Tag editor backend. Writes go through [PendingWrites]: the playing file gets its tags when the
 * track ends, but every screen shows the edit immediately, so to the user it's always "saved".
 */
class TagWriter(private val pending: PendingWrites) {
    enum class Result { SAVED, FAILED }

    suspend fun read(path: String): EditableTags? = pending.readTags(path)

    suspend fun write(path: String, tags: EditableTags): Result =
        if (pending.writeTags(path, tags) == PendingWrites.Result.FAILED) Result.FAILED else Result.SAVED
}

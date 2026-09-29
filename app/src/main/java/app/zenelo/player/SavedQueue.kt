package app.zenelo.player

import app.zenelo.mstream.MStreamPaths
import java.io.File

/**
 * The queue as saved between launches: a small header, then one path per line (a 7000-track queue
 * is a few hundred KB). The position lives in its own tiny file, written far more often.
 */
internal data class SavedQueue(
    val paths: List<String>,
    val order: List<Int>,
    /** Index in [order] of the current track. */
    val current: Int,
    val shuffle: Boolean,
    val repeatMode: Int,
    val positionMs: Long = 0,
) {
    fun write(file: File) {
        val text = buildString {
            appendLine(MAGIC)
            appendLine(current)
            appendLine(if (shuffle) 1 else 0)
            appendLine(repeatMode)
            appendLine(order.joinToString(","))
            paths.forEach(::appendLine)
        }
        writeAtomically(file, text)
    }

    companion object {
        private const val MAGIC = "zenelo-queue 1"

        /** Null when nothing (valid) was saved or the current file is gone. */
        fun read(file: File, positionFile: File): SavedQueue? = runCatching {
            if (!file.exists()) return null
            val lines = file.readLines()
            if (lines.size < 5 || lines[0] != MAGIC) return null
            val paths = lines.drop(5)
            val order = lines[4].split(',').filter(String::isNotEmpty).map(String::toInt)
            val current = lines[1].toInt()
            if (order.isEmpty() || current !in order.indices || order.any { it !in paths.indices }) return null
            val currentPath = paths[order[current]]
            // A server track isn't a file here: whether it still exists is the server's business.
            if (!MStreamPaths.isRemote(currentPath) && !File(currentPath).exists()) return null
            // The position only counts if it was saved for this track.
            val position = positionFile.takeIf { it.exists() }?.readLines()
                ?.takeIf { it.size >= 2 && it[0] == currentPath }?.get(1)?.toLongOrNull() ?: 0L
            SavedQueue(paths, order, current, lines[2] == "1", lines[3].toInt(), position)
        }.getOrNull()

        fun writePosition(file: File, path: String, positionMs: Long) = writeAtomically(file, "$path\n$positionMs\n")

        /** Write-then-rename, so a kill mid-write leaves the previous file intact. */
        private fun writeAtomically(file: File, text: String) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

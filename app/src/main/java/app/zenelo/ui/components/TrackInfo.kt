package app.zenelo.ui.components

import app.zenelo.data.db.TrackEntity
import app.zenelo.library.AudioFile

/** "24/96", "16/44", "DSD64" or "320 kbps", as in the design. */
fun TrackEntity.qualityLabel(): String? = when {
    extension in setOf("dsf", "dff") && sampleRate > 0 -> "DSD${sampleRate / 44_100}"
    lossless && bitsPerSample > 0 && sampleRate > 0 -> "$bitsPerSample/${sampleRate / 1000}"
    bitrateKbps > 0 -> "$bitrateKbps kbps"
    else -> null
}

/** "FLAC · 24/96" for the Now Playing chip. */
fun formatChip(extension: String, info: TrackEntity?): String =
    listOfNotNull(extension.uppercase(), info?.qualityLabel()).joinToString(" · ")

/** File row subtitle: "4:31 · 24/96 · 78 MB"; falls back to the extension until tags are read. */
fun trackSubtitle(file: AudioFile, info: TrackEntity?): String = listOfNotNull(
    info?.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
    info?.qualityLabel() ?: file.extension.uppercase(),
    formatSize(file.sizeBytes),
).joinToString(" · ")

/** "48 min" / "1 h 12 min". */
fun formatTotal(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

package app.zenelo.library

data class LyricLine(val timeMs: Long, val text: String)

/** Minimal LRC parser: `[mm:ss.xx]text`, several timestamps per line allowed, metadata tags skipped. */
object Lrc {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun looksSynced(text: String) = stamp.containsMatchIn(text)

    fun parse(text: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    else -> frac.take(3).toInt()
                }
                lines += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs, body)
            }
        }
        return lines.sortedBy { it.timeMs }
    }
}

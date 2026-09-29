package app.zenelo.library

import java.io.File
import java.io.RandomAccessFile

/**
 * Exact duration of MP3 files whose headers can't tell it: variable bitrate without a Xing /
 * Info / VBRI frame (joined or cut mixes, some encoders). For those, jaudiotagger and ExoPlayer
 * estimate from the first frame's bitrate — a 30 min file showed 12:33, and seeks landed minutes
 * off. Here every frame header is walked and the samples counted.
 *
 * Returns null when the estimate is already right or the file isn't MPEG audio: a Xing / VBRI
 * frame (it carries the frame count), or constant bitrate over the first [CBR_PROBE] frames.
 */
object Mp3Scan {
    private const val CBR_PROBE = 64
    /** How far to look for the next frame after junk between frames. */
    private const val RESYNC_LIMIT = 64 * 1024

    fun durationMs(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf -> durationMs(FileBytes(raf)) }
    }.getOrNull()

    /** Random access to the file's bytes; returns how many bytes were read into [into]. */
    interface Bytes {
        val size: Long
        fun read(position: Long, into: ByteArray, count: Int): Int
    }

    internal class Frame(val length: Int, val samples: Int, val sampleRate: Int, val bitrate: Int, val mpeg1: Boolean, val mono: Boolean, val crc: Boolean)

    fun durationMs(bytes: Bytes): Long? {
        val head = ByteArray(10)
        var pos = 0L
        // ID3v2 tag(s) at the start.
        while (bytes.read(pos, head, 10) == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = (head[6].toInt() and 0x7f shl 21) or (head[7].toInt() and 0x7f shl 14) or (head[8].toInt() and 0x7f shl 7) or (head[9].toInt() and 0x7f)
            val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
            pos += 10 + size + footer
        }
        val end = audioEnd(bytes)
        pos = sync(bytes, pos, end) ?: return null
        val first = frameAt(bytes, pos) ?: return null
        if (hasVbrHeader(bytes, pos, first)) return null

        var samplesUs = 0.0
        var frames = 0
        val bitrates = HashSet<Int>()
        while (pos < end) {
            val frame = frameAt(bytes, pos)
            if (frame == null || pos + frame.length > end) {
                pos = sync(bytes, pos + 1, end) ?: break
                continue
            }
            samplesUs += frame.samples * 1_000_000.0 / frame.sampleRate
            frames++
            if (frames <= CBR_PROBE) {
                bitrates.add(frame.bitrate)
                if (frames == CBR_PROBE && bitrates.size == 1) return null
            }
            pos += frame.length
        }
        if (frames < 2 || (frames <= CBR_PROBE && bitrates.size == 1)) return null
        return (samplesUs / 1000).toLong()
    }

    /** Before an ID3v1 ("TAG", last 128 bytes) and an APEv2 footer ("APETAGEX"), if any. */
    private fun audioEnd(bytes: Bytes): Long {
        var end = bytes.size
        val b = ByteArray(32)
        if (end >= 128 && bytes.read(end - 128, b, 3) == 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "TAG") end -= 128
        if (end >= 32 && bytes.read(end - 32, b, 32) == 32 && String(b, 0, 8, Charsets.ISO_8859_1) == "APETAGEX") {
            val tagSize = (b[12].toLong() and 0xff) or (b[13].toLong() and 0xff shl 8) or (b[14].toLong() and 0xff shl 16) or (b[15].toLong() and 0xff shl 24)
            val hasHeader = (b[23].toInt() and 0x80) != 0
            end -= tagSize + if (hasHeader) 32 else 0
        }
        return end.coerceAtLeast(0)
    }

    /** The next position holding a frame followed by another frame (a lone 0xFFE pattern in junk isn't enough). */
    private fun sync(bytes: Bytes, from: Long, end: Long): Long? {
        var pos = from
        val limit = minOf(end, from + RESYNC_LIMIT)
        while (pos < limit) {
            val frame = frameAt(bytes, pos)
            if (frame != null) {
                val next = pos + frame.length
                if (next >= end || frameAt(bytes, next) != null) return pos
            }
            pos++
        }
        return null
    }

    private val header = ByteArray(4)

    @Synchronized
    internal fun frameAt(bytes: Bytes, pos: Long): Frame? {
        if (bytes.read(pos, header, 4) != 4) return null
        return parse(header[0].toInt() and 0xff, header[1].toInt() and 0xff, header[2].toInt() and 0xff, header[3].toInt() and 0xff)
    }

    internal fun parse(b0: Int, b1: Int, b2: Int, b3: Int): Frame? {
        if (b0 != 0xff || (b1 and 0xe0) != 0xe0) return null
        val version = (b1 shr 3) and 3 // 0: MPEG 2.5, 2: MPEG 2, 3: MPEG 1
        val layer = (b1 shr 1) and 3 // 1: III, 2: II, 3: I
        if (version == 1 || layer == 0) return null
        val bitrateIndex = b2 shr 4
        val rateIndex = (b2 shr 2) and 3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val padding = (b2 shr 1) and 1
        val mpeg1 = version == 3
        val sampleRate = intArrayOf(44100, 48000, 32000)[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val bitrate = 1000 * when {
            mpeg1 && layer == 3 -> BR_M1_L1
            mpeg1 && layer == 2 -> BR_M1_L2
            mpeg1 -> BR_M1_L3
            layer == 3 -> BR_M2_L1
            else -> BR_M2_L23
        }[bitrateIndex]
        val (length, samples) = when (layer) {
            3 -> (12 * bitrate / sampleRate + padding) * 4 to 384
            2 -> 144 * bitrate / sampleRate + padding to 1152
            else -> (if (mpeg1) 144 else 72) * bitrate / sampleRate + padding to (if (mpeg1) 1152 else 576)
        }
        if (length < 4) return null
        return Frame(length, samples, sampleRate, bitrate, mpeg1, mono = (b3 shr 6) == 3, crc = (b1 and 1) == 0)
    }

    /** "Xing" / "Info" after the side info, or "VBRI" 32 bytes in, of the first frame. */
    private fun hasVbrHeader(bytes: Bytes, pos: Long, frame: Frame): Boolean {
        val sideInfo = when {
            frame.mpeg1 -> if (frame.mono) 17 else 32
            else -> if (frame.mono) 9 else 17
        }
        val b = ByteArray(4)
        fun tag(offset: Int) = if (bytes.read(pos + offset, b, 4) == 4) String(b, Charsets.ISO_8859_1) else ""
        val xing = tag(4 + (if (frame.crc) 2 else 0) + sideInfo)
        return xing == "Xing" || xing == "Info" || tag(36) == "VBRI"
    }

    private val BR_M1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BR_M1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BR_M1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR_M2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BR_M2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Buffered reads: frames are walked front to back, a few hundred bytes apart. */
    private class FileBytes(private val raf: RandomAccessFile) : Bytes {
        override val size: Long = raf.length()
        private val buffer = ByteArray(256 * 1024)
        private var bufferStart = 0L
        private var bufferLength = 0

        override fun read(position: Long, into: ByteArray, count: Int): Int {
            if (position < 0 || position >= size) return 0
            if (position < bufferStart || position + count > bufferStart + bufferLength) {
                raf.seek(position)
                bufferStart = position
                bufferLength = maxOf(0, raf.read(buffer, 0, buffer.size))
            }
            val n = minOf(count.toLong(), bufferStart + bufferLength - position).toInt()
            if (n <= 0) return 0
            System.arraycopy(buffer, (position - bufferStart).toInt(), into, 0, n)
            return n
        }
    }
}

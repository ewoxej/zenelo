package app.zenelo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp3ScanTest {
    /** MPEG-1 Layer III, 44.1 kHz, joint stereo, no CRC. Bitrate index 9 = 128 kbps, 14 = 320 kbps. */
    private fun frame(bitrateIndex: Int, padding: Boolean = false, tag: String? = null): ByteArray {
        val bitrate = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex] * 1000
        val length = 144 * bitrate / 44100 + if (padding) 1 else 0
        val f = ByteArray(length)
        f[0] = 0xff.toByte()
        f[1] = 0xfb.toByte()
        f[2] = ((bitrateIndex shl 4) or (if (padding) 2 else 0)).toByte()
        f[3] = 0x44
        // Xing / Info sits after the 32 bytes of stereo side info.
        tag?.toByteArray()?.copyInto(f, 4 + 32)
        return f
    }

    private fun bytes(data: ByteArray) = object : Mp3Scan.Bytes {
        override val size = data.size.toLong()
        override fun read(position: Long, into: ByteArray, count: Int): Int {
            if (position < 0 || position >= data.size) return 0
            val n = minOf(count.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), into, 0, n)
            return n
        }
    }

    private fun build(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()

    /** 1152 samples per frame at 44.1 kHz. */
    private fun ms(frames: Int) = frames * 1152 * 1000L / 44100

    @Test
    fun vbrWithoutXingIsCounted() {
        val data = build { repeat(3000) { write(frame(if (it % 3 == 0) 14 else 9, padding = it % 2 == 0)) } }
        assertEquals(ms(3000).toDouble(), Mp3Scan.durationMs(bytes(data))!!.toDouble(), 1.0)
    }

    @Test
    fun skipsId3v2Id3v1AndJunk() {
        val id3 = ByteArray(10 + 300).also {
            "ID3".toByteArray().copyInto(it)
            it[3] = 4
            // Syncsafe size 300 = 0b10_0101100 → bytes 0, 0, 2, 44.
            it[8] = 2
            it[9] = 44
        }
        val data = build {
            write(id3)
            repeat(500) { write(frame(if (it % 2 == 0) 14 else 9)) }
            write(ByteArray(777) { 0x55 }) // junk between frames
            repeat(500) { write(frame(if (it % 2 == 0) 9 else 14)) }
            write("TAG".toByteArray() + ByteArray(125))
        }
        assertEquals(ms(1000).toDouble(), Mp3Scan.durationMs(bytes(data))!!.toDouble(), 1.0)
    }

    @Test
    fun xingOrInfoFrameLeavesItToTheHeaders() {
        val xing = build { write(frame(9, tag = "Xing")); repeat(200) { write(frame(if (it % 2 == 0) 14 else 9)) } }
        assertNull(Mp3Scan.durationMs(bytes(xing)))
        val info = build { write(frame(9, tag = "Info")); repeat(200) { write(frame(9)) } }
        assertNull(Mp3Scan.durationMs(bytes(info)))
    }

    @Test
    fun constantBitrateIsLeftToTheEstimate() {
        val cbr = build { repeat(1000) { write(frame(9, padding = it % 3 == 0)) } }
        assertNull(Mp3Scan.durationMs(bytes(cbr)))
    }

    @Test
    fun notMpegAudio() {
        assertNull(Mp3Scan.durationMs(bytes(ByteArray(10_000) { (it % 251).toByte() })))
        assertNull(Mp3Scan.durationMs(bytes(ByteArray(0))))
    }

    @Test
    fun parsesMpeg2LayerIII() {
        // MPEG-2 (version bits 10), Layer III, 64 kbps (index 8), 22.05 kHz: 72 * 64000 / 22050 = 208 bytes, 576 samples.
        val frame = Mp3Scan.parse(0xff, 0xf3, 0x80, 0x44)!!
        assertEquals(208, frame.length)
        assertEquals(576, frame.samples)
        assertEquals(22050, frame.sampleRate)
    }
}

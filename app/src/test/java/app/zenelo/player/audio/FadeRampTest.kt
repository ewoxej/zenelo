package app.zenelo.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crossfade ramps counted in the processor's own audio (from its next frame / flush). */
class FadeRampTest {
    private val rate = 1000

    private fun processor() = NormalizationAudioProcessor().apply {
        configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    /** [n] mono frames at full scale through [p]: what came out. */
    private fun run(p: NormalizationAudioProcessor, n: Int): ShortArray {
        val input = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        repeat(n) { input.putShort(10_000) }
        input.flip()
        p.queueInput(input)
        val out = p.output
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun fadeOutGoesToSilenceAndStays() {
        val p = processor()
        p.fadeOut(1_000)
        val a = run(p, 500)
        assertEquals(10_000, a.first().toInt())
        assertEquals(1_250.0, a.last().toDouble(), 50.0) // half way: (½)³
        val b = run(p, 600)
        assertEquals(0, b.last().toInt())
        assertTrue(run(p, 100).all { it.toInt() == 0 })
        p.clearFade()
        assertEquals(10_000, run(p, 10).last().toInt())
    }

    @Test
    fun fadeInRestartsAtTheFlushAndEndsByItself() {
        val p = processor()
        p.fadeIn(1_000)
        run(p, 300) // the old track's last buffers before the seek
        p.flush()
        val a = run(p, 1)
        assertEquals(0, a.first().toInt())
        run(p, 1_200)
        // Done: a later seek doesn't fade in again.
        p.flush()
        assertEquals(10_000, run(p, 1).first().toInt())
    }

    @Test
    fun fadeOutWaitsItsTurn() {
        val p = processor()
        p.fadeOut(1_000, afterMs = 500)
        assertTrue(run(p, 500).all { it.toInt() == 10_000 })
        val a = run(p, 500)
        assertEquals(1_250.0, a.last().toDouble(), 50.0)
    }

    @Test
    fun fadeOutKeepsCountingThroughAFlush() {
        val p = processor()
        p.fadeOut(1_000)
        run(p, 500)
        p.flush() // a speed change
        assertEquals(1_250.0, run(p, 1).first().toDouble(), 50.0)
    }
}

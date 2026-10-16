package app.zenelo.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class VizMathTest {
    private fun sine(hz: Double, amp: Double, rate: Int = 44100) =
        ByteArray(VizMath.N) { i -> (128 + (amp * 127 * sin(2 * PI * hz * i / rate)).roundToInt()).toByte() }

    @Test
    fun silenceIsQuiet() {
        val silence = ByteArray(VizMath.N) { 128.toByte() }
        assertEquals(0.0, VizMath.rms(silence), 1e-9)
        val mags = DoubleArray(VizMath.binsUpTo(VizMath.FLUX_TOP_HZ, 44100))
        VizMath.magnitudes(silence, mags)
        assertTrue(mags.all { it < 1e-9 })
    }

    @Test
    fun sineLoudnessAndItsBin() {
        // A full-scale sine's RMS is 1/√2.
        assertEquals(0.707, VizMath.rms(sine(1000.0, 1.0)), 0.01)
        val bins = VizMath.binsUpTo(VizMath.FLUX_TOP_HZ, 44100)
        assertEquals(127, bins)
        val mags = DoubleArray(bins)
        VizMath.magnitudes(sine(1000.0, 1.0), mags)
        // 1 kHz at 44.1 kHz in 1024 points: bin 23.
        assertEquals(23, mags.indices.maxBy { mags[it] })
    }

    @Test
    fun fluxCountsRisesOnly() {
        val bins = VizMath.binsUpTo(VizMath.FLUX_TOP_HZ, 44100)
        val quiet = DoubleArray(bins).also { VizMath.magnitudes(sine(200.0, 0.1), it) }
        val loud = DoubleArray(bins).also { VizMath.magnitudes(sine(200.0, 0.9), it) }
        val up = VizMath.flux(loud, quiet)
        assertTrue(up > 0.05)
        // Going quieter: only the 8-bit quantization's noise rises somewhere.
        assertTrue(VizMath.flux(quiet, loud) < up / 20)
    }

    @Test
    fun shortCapturesStretch() {
        val from = ByteArray(512) { (it % 256).toByte() }
        val to = ByteArray(VizMath.N)
        VizMath.stretch(from, to)
        assertEquals(from[0], to[0])
        assertEquals(from[0], to[1])
        assertEquals(from[511], to[1023])
    }
}

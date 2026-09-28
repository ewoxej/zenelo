package app.zenelo.library

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** BS.1770 reference: a 1 kHz sine at amplitude A in both channels reads 20·log10(A) LUFS. */
class LoudnessMeterTest {

    private fun measureSine(amplitude: Double, rate: Int, seconds: Int = 10, channels: Int = 2): Loudness {
        val meter = Meter(rate, channels, isFloat = true)
        for (n in 0 until rate * seconds) {
            val v = amplitude * sin(2 * PI * 1000.0 * n / rate)
            repeat(channels) { meter.add(v) }
        }
        return meter.result()!!
    }

    @Test
    fun minus20Lufs48k() = assertEquals(-20.0, measureSine(0.1, 48_000).integratedLufs, 0.1)

    @Test
    fun minus20Lufs44k() = assertEquals(-20.0, measureSine(0.1, 44_100).integratedLufs, 0.1)

    @Test
    fun fullScale96k() = assertEquals(0.0, measureSine(1.0, 96_000).integratedLufs, 0.1)

    @Test
    fun monoIsThreeDbQuieter() = assertEquals(-23.01, measureSine(0.1, 48_000, channels = 1).integratedLufs, 0.1)

    @Test
    fun peak() = assertEquals(-20.0, measureSine(0.1, 48_000).peakDbfs, 0.05)
}

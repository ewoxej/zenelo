package app.zenelo.player

import android.media.audiofx.Visualizer
import android.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the player sounds like right now, for the visualizer: Android's [Visualizer] on the player's
 * audio session — taken at the output, so it matches what is heard (a tap inside the player would run
 * ahead by its buffer). Android gives it only with the microphone permission; nothing is recorded.
 * Throws when the session can't be listened to (no permission, no such session).
 */
class SoundTap(sessionId: Int) : AutoCloseable {
    private val visualizer = Visualizer(sessionId).apply {
        enabled = false
        captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(VizMath.N)
        scalingMode = Visualizer.SCALING_MODE_NORMALIZED
        enabled = true
    }
    private val sampleRate = visualizer.samplingRate / 1000
    private val captured = ByteArray(visualizer.captureSize)
    private val wave = ByteArray(VizMath.N)
    private val mags = DoubleArray(VizMath.binsUpTo(VizMath.FLUX_TOP_HZ, sampleRate))
    private val prev = DoubleArray(mags.size)

    /** `"<base64 of N unsigned 8-bit samples>,<rms>,<flux>"` (the page's format), "" when it can't hear. */
    @Synchronized
    fun frame(): String {
        if (visualizer.getWaveForm(captured) != Visualizer.SUCCESS) return ""
        VizMath.stretch(captured, wave)
        val rms = VizMath.rms(wave)
        VizMath.magnitudes(wave, mags)
        val flux = VizMath.flux(mags, prev)
        mags.copyInto(prev)
        return Base64.encodeToString(wave, Base64.NO_WRAP) + "," + rms + "," + flux
    }

    @Synchronized
    override fun close() {
        runCatching { visualizer.enabled = false }
        visualizer.release()
    }
}

/**
 * The visualizer's sums (unit-tested): the drive (`viz-drive.js`) wants a frame's loudness and its
 * spectral flux on the scale the web player's analyser gives (Blackman window, magnitudes / N, up to
 * ~5.5 kHz). Samples are unsigned 8-bit, 128 = silence, as Android and butterchurn both have them.
 */
object VizMath {
    const val N = 1024
    const val FLUX_TOP_HZ = 5500

    private val window = DoubleArray(N) { i -> 0.42 - 0.5 * cos(2 * PI * i / N) + 0.08 * cos(4 * PI * i / N) }

    /** FFT bins (of [N] samples at [sampleRate]) below [hz]. */
    fun binsUpTo(hz: Int, sampleRate: Int): Int = (hz.toLong() * N / sampleRate.coerceAtLeast(1)).toInt().coerceIn(1, N / 2)

    /** [from] (any power-of-two length) into [to] ([N] long): repeated samples when shorter. */
    fun stretch(from: ByteArray, to: ByteArray) {
        if (from.size == to.size) { from.copyInto(to); return }
        for (i in to.indices) to[i] = from[i * from.size / to.size]
    }

    private fun sample(b: Byte): Double = ((b.toInt() and 0xFF) - 128) / 128.0

    fun rms(wave: ByteArray): Double {
        var sum = 0.0
        for (b in wave) { val s = sample(b); sum += s * s }
        return sqrt(sum / wave.size)
    }

    /** Magnitudes of the first `out.size` bins of [wave] ([N] samples). */
    fun magnitudes(wave: ByteArray, out: DoubleArray) {
        val re = DoubleArray(N) { sample(wave[it]) * window[it] }
        val im = DoubleArray(N)
        fft(re, im)
        for (k in out.indices) out[k] = hypot(re[k], im[k]) / N
    }

    /** The sum of the rises from [prev] to [mags]. */
    fun flux(mags: DoubleArray, prev: DoubleArray): Double {
        var s = 0.0
        for (i in mags.indices) { val d = mags[i] - prev[i]; if (d > 0) s += d }
        return s
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cos(angle * k)
                    val s = sin(angle * k)
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }
}

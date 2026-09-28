package app.zenelo.library

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** Integrated loudness (EBU R128 / ITU-R BS.1770) and sample peak of one file. */
data class Loudness(val integratedLufs: Double, val peakDbfs: Double)

/**
 * Measures loudness for files without ReplayGain tags: decodes with the platform decoders
 * (FLAC, MP3, AAC/M4A, WAV, Ogg, Opus) and applies BS.1770 K-weighting with 400ms blocks,
 * 75% overlap, an absolute gate at -70 LUFS and a relative gate 10 LU below.
 * Returns null for formats the platform can't decode (e.g. DSD without the FFmpeg extension).
 */
object LoudnessAnalyzer {

    suspend fun measure(path: String): Loudness? = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var meter: Meter? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (meter == null) meter = Meter.from(decoder.outputFormat)
                    val out = decoder.getOutputBuffer(outIndex)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    meter?.feed(out.slice().order(ByteOrder.nativeOrder()))
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            meter?.result()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

}

/** Streaming BS.1770 meter over interleaved PCM (16-bit or float). */
internal class Meter(private val sampleRate: Int, private val channels: Int, private val isFloat: Boolean) {
    private val filters = Array(channels) { KWeighting(sampleRate.toDouble()) }
    /** Sum of squares per 100ms step (all channels, weight 1: stereo / mono). */
    private val steps = ArrayList<Double>()
    private val stepLength = sampleRate / 10
    private var inStep = 0
    private var stepSum = 0.0
    private var peak = 0.0
    private var channel = 0

    fun feed(buffer: java.nio.ByteBuffer) {
        if (isFloat) {
            val floats = buffer.asFloatBuffer()
            while (floats.hasRemaining()) add(floats.get().toDouble())
        } else {
            val shorts = buffer.asShortBuffer()
            while (shorts.hasRemaining()) add(shorts.get() / 32768.0)
        }
    }

    fun add(sample: Double) {
        if (abs(sample) > peak) peak = abs(sample)
        val y = filters[channel].process(sample)
        stepSum += y * y
        channel++
        if (channel == channels) {
            channel = 0
            inStep++
            if (inStep == stepLength) {
                steps += stepSum
                stepSum = 0.0
                inStep = 0
            }
        }
    }

    fun result(): Loudness? {
        if (steps.size < 4) return null
        // 400ms blocks = 4 consecutive 100ms steps (75% overlap).
        val blockSamples = 4.0 * stepLength
        val blocks = DoubleArray(steps.size - 3) { i -> (steps[i] + steps[i + 1] + steps[i + 2] + steps[i + 3]) / blockSamples }
        fun lufs(meanSquare: Double) = -0.691 + 10 * log10(meanSquare)
        val aboveAbsolute = blocks.filter { it > 0 && lufs(it) > -70.0 }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = lufs(aboveAbsolute.average()) - 10.0
        val gated = aboveAbsolute.filter { lufs(it) > relativeGate }
        if (gated.isEmpty()) return null
        return Loudness(lufs(gated.average()), 20 * log10(peak.coerceAtLeast(1e-9)))
    }

    companion object {
        fun from(format: MediaFormat): Meter {
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
            return Meter(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1),
                isFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT,
            )
        }
    }
}

/** BS.1770 pre-filter (high shelf) + RLB high-pass, coefficients for any sample rate (as in libebur128). */
private class KWeighting(fs: Double) {
    private val b = DoubleArray(5)
    private val a = DoubleArray(5)
    private val x = DoubleArray(5)
    private val y = DoubleArray(5)

    init {
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        val pb = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
        val pa = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / fs)
        a0 = 1.0 + k / q + k * k
        val rb = doubleArrayOf(1.0, -2.0, 1.0)
        val ra = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        // Cascade both biquads into one 4th-order filter.
        b[0] = pb[0] * rb[0]
        b[1] = pb[0] * rb[1] + pb[1] * rb[0]
        b[2] = pb[0] * rb[2] + pb[1] * rb[1] + pb[2] * rb[0]
        b[3] = pb[1] * rb[2] + pb[2] * rb[1]
        b[4] = pb[2] * rb[2]
        a[0] = pa[0] * ra[0]
        a[1] = pa[0] * ra[1] + pa[1] * ra[0]
        a[2] = pa[0] * ra[2] + pa[1] * ra[1] + pa[2] * ra[0]
        a[3] = pa[1] * ra[2] + pa[2] * ra[1]
        a[4] = pa[2] * ra[2]
    }

    fun process(input: Double): Double {
        for (i in 4 downTo 1) {
            x[i] = x[i - 1]
            y[i] = y[i - 1]
        }
        x[0] = input
        y[0] = b[0] * x[0] + b[1] * x[1] + b[2] * x[2] + b[3] * x[3] + b[4] * x[4] -
            a[1] * y[1] - a[2] * y[2] - a[3] * y[3] - a[4] * y[4]
        return y[0]
    }
}

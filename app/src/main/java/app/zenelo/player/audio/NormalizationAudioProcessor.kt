package app.zenelo.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/**
 * Applies a per-track gain (ReplayGain / measured R128) to the PCM stream.
 *
 * Positive gain is clamped to avoid clipping; a proper look-ahead limiter is a TODO.
 * Any non-zero gain means the output is no longer bit-perfect.
 */
@UnstableApi
class NormalizationAudioProcessor : BaseAudioProcessor() {

    /**
     * Set from the playback thread when a track starts.
     * TODO: the sink buffers ahead, so the new gain lands a few hundred ms early at gapless
     *  boundaries. Fix by tagging gain changes with a presentation timestamp.
     */
    @Volatile
    var gainDb: Float = 0f

    /**
     * A crossfade's ramp, counted in the audio this processor handles (not in wall time: a busy main
     * thread used to hold the old track at full volume for seconds). It starts at the stream's next
     * frame. A fade-in starts again at a flush (main's seek to the next track); a fade-out doesn't —
     * the tail is lined up by speed, and every speed change flushes the processors (the count went
     * back to 0 each time and the old track never faded). A finished fade-in removes itself; a
     * faded-out stream stays silent until [clearFade].
     */
    private val ramp = AtomicReference<Ramp?>(null)
    private var counted: Ramp? = null
    private var rampFrames = 0L

    private class Ramp(val fadeIn: Boolean, val ms: Long, val afterMs: Long)

    fun fadeIn(ms: Long) = ramp.set(Ramp(true, ms, 0))

    /** Full volume for [afterMs] of the stream (from its next frame / flush), then a fade-out over [ms]. */
    fun fadeOut(ms: Long, afterMs: Long = 0) = ramp.set(Ramp(false, ms, afterMs.coerceAtLeast(0)))

    fun clearFade() = ramp.set(null)

    override fun onFlush() {
        if (ramp.get()?.fadeIn == true) rampFrames = 0
    }

    /**
     * The ramp's volume [frames] into it. Ears hear in decibels: the old track goes as (1 − p)³ —
     * −7.5 dB a quarter in, −18 dB half way — and the new one comes up as its mirror. (cos², linear in
     * amplitude, was still −3 dB at mid-fade: the old track seemed not to fade at all, then stopped.)
     */
    private fun rampGain(r: Ramp, frames: Long, total: Long, after: Long): Float {
        if (frames < after) return if (r.fadeIn) 0f else 1f
        val left = 1f - (frames - after).coerceAtMost(total).toFloat() / total
        val out = left * left * left
        return if (r.fadeIn) 1f - out else out
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val gain = 10f.pow(gainDb / 20f)
        val r = ramp.get()
        if (r !== counted) {
            counted = r
            rampFrames = 0
        }
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val total = ((r?.ms ?: 0L) * inputAudioFormat.sampleRate / 1000).coerceAtLeast(1)
        val after = (r?.afterMs ?: 0L) * inputAudioFormat.sampleRate / 1000
        var frame = 0
        var g = gain

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> while (inputBuffer.hasRemaining()) {
                if (r != null && frame % channels == 0) g = gain * rampGain(r, rampFrames + frame / channels, total, after)
                val sample = inputBuffer.short * g
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort())
                frame++
            }
            C.ENCODING_PCM_FLOAT -> while (inputBuffer.hasRemaining()) {
                if (r != null && frame % channels == 0) g = gain * rampGain(r, rampFrames + frame / channels, total, after)
                output.putFloat((inputBuffer.float * g).coerceIn(-1f, 1f))
                frame++
            }
        }
        output.flip()
        if (r != null) {
            rampFrames += frame / channels
            if (r.fadeIn && rampFrames >= after + total) ramp.compareAndSet(r, null)
        }
    }
}

package app.zenelo.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
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

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> while (inputBuffer.hasRemaining()) {
                val sample = inputBuffer.short * gain
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort())
            }
            C.ENCODING_PCM_FLOAT -> while (inputBuffer.hasRemaining()) {
                output.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
            }
        }
        output.flip()
    }
}

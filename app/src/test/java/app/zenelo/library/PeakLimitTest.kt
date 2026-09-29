package app.zenelo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PeakLimitTest {
    private fun limit(gain: Float, peakDb: Float?) = LoudnessRepository.limitToPeak(gain, peakDb)

    @Test
    fun unknownPeakKeepsGain() {
        assertEquals(8f, limit(8f, null), 0f)
    }

    @Test
    fun boostStopsAtFullScale() {
        // Peak −3 dBFS: at most +3 dB.
        assertEquals(3f, limit(8f, -3f), 0f)
        assertEquals(2f, limit(2f, -3f), 0f)
    }

    @Test
    fun cutsAreNeverRaised() {
        assertEquals(-4f, limit(-4f, -1f), 0f)
        // Already over full scale: a small cut stays as asked, not pulled up to 0.
        assertEquals(-0.5f, limit(-0.5f, 1f), 0f)
    }

    @Test
    fun fileOverFullScaleGetsNoBoost() {
        assertEquals(0f, limit(5f, 1f), 0f)
    }
}

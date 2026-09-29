package app.zenelo.library

import app.zenelo.data.db.LoudnessEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/** Loudness measurements for tracks without ReplayGain tags, and the gain to apply. */
class LoudnessRepository(db: ZeneloDatabase) {
    private val loudness = db.loudness()
    private val tracks = db.tracks()
    /** One decode at a time: the background pass and the "measure what's next" path share the CPU. */
    private val mutex = Mutex()

    /** Measures [track] if it has no ReplayGain tag and no up-to-date measurement. */
    suspend fun ensureMeasured(track: TrackEntity) {
        if (track.trackGainDb != null) return
        if (loudness.get(track.path)?.fileModified == track.modified) return
        mutex.withLock {
            if (loudness.get(track.path)?.fileModified == track.modified) return
            val result = LoudnessAnalyzer.measure(track.path)
            loudness.upsert(
                LoudnessEntity(
                    path = track.path,
                    fileModified = File(track.path).lastModified(),
                    integratedLufs = result?.integratedLufs?.toFloat() ?: FAILED,
                    truePeakDbtp = result?.peakDbfs?.toFloat(),
                ),
            )
        }
    }

    suspend fun ensureMeasured(path: String) {
        tracks.get(path)?.let { ensureMeasured(it) }
    }

    /**
     * Gain for [track] towards [targetLufs] plus [preampDb]: ReplayGain tags first (album gain in
     * album mode), then our measurement (album: energy average of the album's measured tracks in
     * the folder). Bounded by the peak (tag, else measured) so the loudest sample stays under full
     * scale. Null when nothing is known yet.
     */
    suspend fun gainFor(track: TrackEntity, album: Boolean, targetLufs: Float, preampDb: Float = 0f): Float? {
        val useAlbum = album && track.albumGainDb != null
        val tagged = if (useAlbum) track.albumGainDb else track.trackGainDb
        if (tagged != null) {
            // ReplayGain 2 tags are relative to −18 LUFS.
            val peak = (if (useAlbum) track.albumPeak ?: track.trackPeak else track.trackPeak)?.takeIf { it > 0f }
            return limitToPeak(tagged + (targetLufs - REPLAYGAIN_REFERENCE_LUFS) + preampDb, peak?.let { 20 * log10(it) })
        }
        val measured = if (album) {
            loudness.forAlbum(track.dir, track.albumKey).takeIf { it.isNotEmpty() }?.let { rows ->
                10 * log10(rows.map { 10.0.pow(it.integratedLufs / 10.0) }.average()).toFloat()
            }
        } else {
            null
        }
        val own = loudness.get(track.path)?.takeIf { it.fileModified == File(track.path).lastModified() && it.integratedLufs > FAILED + 1 }
        val lufs = measured ?: own?.integratedLufs ?: return null
        return limitToPeak(targetLufs - lufs + preampDb, own?.truePeakDbtp)
    }

    companion object {
        private const val FAILED = -999f
        private const val REPLAYGAIN_REFERENCE_LUFS = -18f

        /**
         * Don't push the peak ([peakDb], dBFS) over full scale: the processor would hard-clip.
         * Never cuts below the gain asked for or below 0 dB just for that (a file already peaking
         * over full scale isn't made quieter than it is).
         */
        fun limitToPeak(gain: Float, peakDb: Float?): Float {
            val headroom = peakDb?.let { -it } ?: return gain
            return if (gain > headroom) maxOf(headroom, minOf(gain, 0f)) else gain
        }
    }
}

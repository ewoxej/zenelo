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
     * Gain for [track] towards [targetLufs]: ReplayGain tags first (album gain in album mode),
     * then our measurement (album: energy average of the album's measured tracks in the folder).
     * Null when nothing is known yet.
     */
    suspend fun gainFor(track: TrackEntity, album: Boolean, targetLufs: Float): Float? {
        val tagged = if (album) track.albumGainDb ?: track.trackGainDb else track.trackGainDb
        if (tagged != null) return tagged
        val measured = if (album) {
            loudness.forAlbum(track.dir, track.albumKey).takeIf { it.isNotEmpty() }?.let { rows ->
                10 * log10(rows.map { 10.0.pow(it.integratedLufs / 10.0) }.average()).toFloat()
            }
        } else {
            null
        }
        val own = loudness.get(track.path)?.takeIf { it.fileModified == File(track.path).lastModified() && it.integratedLufs > FAILED + 1 }
        val lufs = measured ?: own?.integratedLufs ?: return null
        val gain = targetLufs - lufs
        // Don't push the track's peak over full scale (the processor would hard-clip).
        val headroom = own?.truePeakDbtp?.let { -it }
        return if (headroom != null && gain > headroom) maxOf(headroom, 0f) else gain
    }

    private companion object {
        const val FAILED = -999f
    }
}

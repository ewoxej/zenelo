package app.zenelo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Debug builds only: records what goes out of the mixer (a Visualizer on the output mix) for a
 * while, to look at transitions (crossfades) as waveforms / spectrograms on a computer:
 *
 *     adb shell am broadcast -n app.zenelo/app.zenelo.debug.MixCapture --ei secs 30
 *     adb exec-out run-as app.zenelo cat files/mix.bin > mix.bin
 *
 * The file: sampling rate (mHz) and capture size (ints), then per new capture its
 * elapsedRealtime (ns, long) and that many unsigned 8-bit samples — the latest samples when
 * polled; polled faster than a capture lasts, so they overlap.
 */
class MixCapture : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val secs = intent.getIntExtra("secs", 30)
        val file = File(context.filesDir, "mix.bin")
        thread(name = "mix-capture") {
            val v = Visualizer(0)
            try {
                v.enabled = false
                v.captureSize = Visualizer.getCaptureSizeRange()[1]
                v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                v.enabled = true
                val buf = ByteArray(v.captureSize)
                var last = ByteArray(0)
                var n = 0
                DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                    out.writeInt(v.samplingRate)
                    out.writeInt(v.captureSize)
                    val end = SystemClock.elapsedRealtime() + secs * 1000L
                    Log.i(TAG, "mix capture: $secs s at ${v.samplingRate / 1000} Hz, ${v.captureSize} samples")
                    while (SystemClock.elapsedRealtime() < end) {
                        if (v.getWaveForm(buf) == Visualizer.SUCCESS && !buf.contentEquals(last)) {
                            out.writeLong(SystemClock.elapsedRealtimeNanos())
                            out.write(buf)
                            last = buf.copyOf()
                            n++
                        }
                        Thread.sleep(2)
                    }
                }
                Log.i(TAG, "mix capture done: $n captures in ${file.path}")
            } catch (e: Exception) {
                Log.w(TAG, "mix capture failed", e)
            } finally {
                v.release()
            }
        }
    }

    private companion object {
        const val TAG = "Zenelo"
    }
}

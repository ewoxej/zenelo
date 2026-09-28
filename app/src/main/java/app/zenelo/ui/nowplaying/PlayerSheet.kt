package app.zenelo.ui.nowplaying

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Now Playing as a sheet over the tabs, following the finger both ways. Collapsed, its top is the
 * mini player: swipe up pulls that up and it turns into Now Playing; pull-down turns it back.
 * [fraction]: 0 = open, 1 = collapsed to the mini player.
 */
@Stable
class PlayerSheet(private val scope: CoroutineScope) {
    var fraction by mutableFloatStateOf(1f)
        private set

    /** How far the sheet travels, in px: from the mini player's place (above the tab bar) to the top. */
    var heightPx = 1f

    /** A finger is on it (a drag in progress). */
    var held by mutableStateOf(false)
        private set

    private var startFraction = 1f
    private var animation: Job? = null

    fun dragStart() {
        animation?.cancel()
        startFraction = fraction
        held = true
    }

    fun dragBy(dy: Float) {
        fraction = (fraction + dy / heightPx).coerceIn(0f, 1f)
    }

    /** Settles open or closed: a fling decides, else whether the drag went past [thresholdPx]. */
    fun dragEnd(velocityY: Float, thresholdPx: Float) {
        held = false
        val moved = (fraction - startFraction) * heightPx
        val open = when {
            velocityY < -FLING -> true
            velocityY > FLING -> false
            startFraction > 0.5f -> moved < -thresholdPx
            else -> moved <= thresholdPx
        }
        animateTo(if (open) 0f else 1f)
    }

    fun open() = animateTo(0f)

    fun close() = animateTo(1f)

    private fun animateTo(target: Float) {
        animation?.cancel()
        animation = scope.launch {
            animate(fraction, target, animationSpec = tween(200)) { value, _ -> fraction = value }
        }
    }

    private companion object {
        const val FLING = 1000f
    }
}

package app.zenelo.ui.library

import kotlin.math.roundToInt

/**
 * The A–Z rail's slots when its letters don't all fit (iOS-style): some letters, evenly picked
 * (always the first and the last), with a dot standing for each run of hidden ones between. The
 * slots split the rail's height equally; a finger on a dot picks among its hidden letters by
 * where it is inside the dot's slot, so dragging still passes every letter.
 */
object RailSlots {
    /** Letter indices [first]..[last] of the rail's letters: one letter, or a dot for several. */
    data class Slot(val first: Int, val last: Int) {
        val isDot: Boolean get() = last > first
    }

    /** Slots for [count] letters in at most [maxSlots] places. */
    fun layout(count: Int, maxSlots: Int): List<Slot> {
        if (count <= 0) return emptyList()
        if (count <= maxSlots) return (0 until count).map { Slot(it, it) }
        // Letters and dots alternate: n letters take 2n - 1 places.
        val shown = ((maxSlots.coerceAtLeast(3) + 1) / 2).coerceAtLeast(2)
        val picks = (0 until shown).map { (it * (count - 1).toDouble() / (shown - 1)).roundToInt() }.distinct()
        return buildList {
            picks.forEachIndexed { i, p ->
                add(Slot(p, p))
                val next = picks.getOrNull(i + 1) ?: return@forEachIndexed
                if (next - p > 1) add(Slot(p + 1, next - 1))
            }
        }
    }

    /** The letter under a finger at [fraction] (0..1) of [slot]'s height. */
    fun letterAt(slot: Slot, fraction: Float): Int =
        (slot.first + (fraction * (slot.last - slot.first + 1)).toInt()).coerceIn(slot.first, slot.last)
}

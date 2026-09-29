package app.zenelo.player

import kotlin.random.Random

/** How shuffle orders the queue (a setting). */
enum class ShuffleMode(val label: String, val description: String) {
    TRACKS("Tracks", "Every track in random order"),
    ALBUMS("Albums", "Albums in random order, each played through"),
    SMART("Smart", "Artists spread out, recently played last"),
}

/** What shuffling needs to know about one queue entry. */
data class ShuffleItem(
    /** Album key, else the folder: tracks sharing it are one album. */
    val album: String,
    /** Artist keys (a multi-artist tag counts for each); empty when unknown. */
    val artists: Set<String>,
    val trackNumber: Int?,
    /** Played in the last [Shuffle.RECENT_MS]: goes towards the end in smart mode. */
    val recent: Boolean,
)

/**
 * Shuffled orders of a queue: permutations of its indices, [start] first.
 * - [ShuffleMode.TRACKS]: uniform.
 * - [ShuffleMode.ALBUMS]: albums shuffled, tracks by number inside each. The start track's album
 *   plays from the start track on; that album's earlier tracks come last.
 * - [ShuffleMode.SMART]: each artist's tracks are spread evenly over the order (with random
 *   offsets), then neighbours sharing an artist are swapped apart where possible; recently played
 *   tracks form the tail.
 */
object Shuffle {
    const val RECENT_MS = 48L * 60 * 60 * 1000
    private const val SWAP_LOOKAHEAD = 500

    fun order(items: List<ShuffleItem>, start: Int, mode: ShuffleMode, random: Random = Random): List<Int> {
        if (items.isEmpty()) return emptyList()
        val first = start.coerceIn(items.indices)
        return when (mode) {
            ShuffleMode.TRACKS -> listOf(first) + (items.indices - first).shuffled(random)
            ShuffleMode.ALBUMS -> albums(items, first, random)
            ShuffleMode.SMART -> smart(items, first, random)
        }
    }

    private fun albums(items: List<ShuffleItem>, start: Int, random: Random): List<Int> {
        val byNumber = compareBy<Int>({ items[it].trackNumber == null }, { items[it].trackNumber }, { it })
        val groups = items.indices.groupBy { items[it].album }.mapValues { (_, tracks) -> tracks.sortedWith(byNumber) }
        val own = groups.getValue(items[start].album)
        val at = own.indexOf(start)
        val others = groups.filterKeys { it != items[start].album }.values.shuffled(random).flatten()
        return own.subList(at, own.size) + others + own.subList(0, at)
    }

    private fun smart(items: List<ShuffleItem>, start: Int, random: Random): List<Int> {
        val rest = items.indices - start
        val fresh = spread(rest.filter { !items[it].recent }, items, random)
        val recent = spread(rest.filter { items[it].recent }, items, random)
        return separate(listOf(start) + fresh + recent, items)
    }

    /**
     * Each artist's tracks at evenly spaced points of [0, 1) with a random phase and a little
     * jitter; tracks without an artist anywhere at random. Sorted by point.
     */
    private fun spread(indices: List<Int>, items: List<ShuffleItem>, random: Random): List<Int> {
        val groups = indices.groupBy { items[it].artists.minOrNull() ?: "\u0000$it" }
        val points = HashMap<Int, Double>(indices.size)
        for (tracks in groups.values) {
            val n = tracks.size
            val phase = random.nextDouble() / n
            tracks.shuffled(random).forEachIndexed { i, index ->
                val jitter = (random.nextDouble() - 0.5) * 0.2 / n
                points[index] = i.toDouble() / n + phase + jitter
            }
        }
        return indices.sortedBy { points.getValue(it) }
    }

    /**
     * Where two neighbours share an artist, swaps the second with the nearest later track such
     * that neither lands next to its own artist. The first track stays first.
     */
    private fun separate(order: List<Int>, items: List<ShuffleItem>): List<Int> {
        val out = order.toMutableList()
        fun clash(a: Int, b: Int) = items[a].artists.any(items[b].artists::contains)
        /** Would [track] at [at] clash with a neighbour, [ignoring] the slot it's being swapped with? */
        fun clashesAt(track: Int, at: Int, ignoring: Int) =
            (at - 1 >= 0 && at - 1 != ignoring && clash(track, out[at - 1])) ||
                (at + 1 < out.size && at + 1 != ignoring && clash(track, out[at + 1]))
        for (i in 1 until out.size) {
            if (!clash(out[i - 1], out[i])) continue
            // Bounded look-ahead: with one artist everywhere, no swap helps and a full scan per track is quadratic.
            val j = (i + 1 until minOf(out.size, i + 1 + SWAP_LOOKAHEAD)).firstOrNull { j ->
                // Adjacent swap: they become each other's neighbours.
                (j != i + 1 || !clash(out[i], out[j])) && !clashesAt(out[j], i, ignoring = j) && !clashesAt(out[i], j, ignoring = i)
            } ?: continue
            out[i] = out[j].also { out[j] = out[i] }
        }
        return out
    }
}

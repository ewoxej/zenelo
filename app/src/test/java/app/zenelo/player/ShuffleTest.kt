package app.zenelo.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleTest {
    private fun item(album: String, vararg artists: String, number: Int? = null, recent: Boolean = false) =
        ShuffleItem(album, artists.toSet(), number, recent)

    private fun assertPermutation(order: List<Int>, size: Int) = assertEquals((0 until size).toList(), order.sorted())

    @Test
    fun everyModeIsAPermutationWithStartFirst() {
        val items = List(50) { item("al${it % 7}", "ar${it % 5}", number = it % 7) }
        for (mode in ShuffleMode.entries) {
            repeat(20) { seed ->
                val order = Shuffle.order(items, 13, mode, Random(seed))
                assertPermutation(order, items.size)
                assertEquals(13, order.first())
            }
        }
    }

    @Test
    fun albumsPlayThroughByNumber() {
        // Album x listed out of order; y and z in order.
        val items = listOf(
            item("x", number = 3), item("x", number = 1), item("x", number = 2),
            item("y", number = 1), item("y", number = 2),
            item("z", number = 1), item("z", number = 2), item("z", number = 3),
        )
        repeat(20) { seed ->
            val order = Shuffle.order(items, 3, ShuffleMode.ALBUMS, Random(seed))
            assertEquals(listOf(3, 4), order.take(2))
            val albums = order.map { items[it].album }
            // Each album is one contiguous run.
            assertEquals(3, albums.zipWithNext().count { (a, b) -> a != b } + 1)
            val x = order.filter { items[it].album == "x" }.map { items[it].trackNumber }
            assertEquals(listOf(1, 2, 3), x)
        }
    }

    @Test
    fun albumsStartMidAlbumPutsEarlierTracksLast() {
        val items = listOf(item("x", number = 1), item("x", number = 2), item("x", number = 3), item("y", number = 1))
        val order = Shuffle.order(items, 1, ShuffleMode.ALBUMS, Random(1))
        assertEquals(listOf(1, 2, 3, 0), order)
    }

    @Test
    fun smartAvoidsSameArtistInARow() {
        // Three artists, 10 tracks each: always possible.
        val items = List(30) { item("al$it", "ar${it / 10}") }
        repeat(50) { seed ->
            val order = Shuffle.order(items, 0, ShuffleMode.SMART, Random(seed))
            assertPermutation(order, items.size)
            val clashes = order.zipWithNext().count { (a, b) -> items[a].artists == items[b].artists }
            assertEquals("seed $seed: $order", 0, clashes)
        }
    }

    @Test
    fun smartBigLibraryHasNoRepeats() {
        // Like the test library: 50 artists × 141 tracks, a few singles, some recently played.
        val items = List(50 * 141) { item("al${it / 141}", "ar${it / 141}", number = it % 141, recent = it % 97 == 0) } +
            List(10) { item("s$it", "solo${it % 3}") }
        repeat(5) { seed ->
            val order = Shuffle.order(items, 7, ShuffleMode.SMART, Random(seed))
            assertPermutation(order, items.size)
            val clashes = order.zipWithNext().count { (a, b) -> items[a].artists == items[b].artists }
            assertEquals("seed $seed", 0, clashes)
        }
    }

    @Test
    fun smartCountsEveryArtistOfATrack() {
        val items = listOf(item("a", "x", "y"), item("b", "y"), item("c", "z"), item("d", "z"), item("e", "w"))
        repeat(30) { seed ->
            val order = Shuffle.order(items, 0, ShuffleMode.SMART, Random(seed))
            assertTrue("seed $seed: $order", order[1] != 1)
        }
    }

    @Test
    fun smartPutsRecentlyPlayedLast() {
        val items = List(20) { item("al$it", "ar$it", recent = it >= 15) }
        repeat(20) { seed ->
            val order = Shuffle.order(items, 0, ShuffleMode.SMART, Random(seed))
            assertEquals(setOf(15, 16, 17, 18, 19), order.takeLast(5).toSet())
        }
    }

    @Test
    fun smartSpreadsAnArtist() {
        // 10 tracks of one artist among 90 singles: no long gap without them.
        val items = List(100) { if (it < 10) item("a$it", "big") else item("s$it", "solo$it") }
        repeat(20) { seed ->
            val order = Shuffle.order(items, 50, ShuffleMode.SMART, Random(seed))
            val at = order.indices.filter { order[it] < 10 }
            val gaps = at.zipWithNext().map { (a, b) -> b - a }
            assertTrue("seed $seed: $gaps", gaps.all { it in 3..20 })
        }
    }

    @Test(timeout = 2_000)
    fun smartOneArtistEverywhereIsFast() {
        val items = List(7000) { item("al${it / 10}", "only") }
        assertPermutation(Shuffle.order(items, 0, ShuffleMode.SMART, Random(1)), items.size)
    }

    @Test
    fun singleArtistStillWorks() {
        val items = List(5) { item("a", "only") }
        assertPermutation(Shuffle.order(items, 2, ShuffleMode.SMART, Random(3)), 5)
    }
}

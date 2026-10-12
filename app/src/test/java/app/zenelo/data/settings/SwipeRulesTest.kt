package app.zenelo.data.settings

import app.zenelo.data.settings.SwipeRules.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeRulesTest {
    private fun allowed(t: Target, server: Boolean = true) = SwipeAction.entries.filter { SwipeRules.applies(it, t, server) }.toSet()

    private val always = setOf(SwipeAction.ADD_TO_QUEUE, SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_PLAYLIST, SwipeAction.HIDE)

    @Test
    fun localFile() {
        assertEquals(always + SwipeAction.FAVORITE + SwipeAction.DELETE_FILE, allowed(Target()))
        // Already a favorite, matched to the server: no heart, Sonic path yes, nothing to download.
        assertEquals(always + SwipeAction.DELETE_FILE + SwipeAction.SONIC_PATH, allowed(Target(favorite = true, onServer = true)))
    }

    @Test
    fun serverTrack() {
        val cloud = Target(remote = true, onServer = true)
        assertEquals(always + SwipeAction.FAVORITE + SwipeAction.DOWNLOAD + SwipeAction.SONIC_PATH, allowed(cloud))
        // Downloaded already; offline / logged out: no download, no Sonic path.
        assertEquals(always + SwipeAction.FAVORITE + SwipeAction.SONIC_PATH, allowed(cloud.copy(hasLocalCopy = true)))
        assertEquals(always + SwipeAction.FAVORITE, allowed(cloud, server = false))
    }

    @Test
    fun folder() {
        assertEquals(always + SwipeAction.FAVORITE, allowed(Target(folder = true)))
        assertEquals(always + SwipeAction.DOWNLOAD, allowed(Target(folder = true, favorite = true, cloudInside = 3)))
    }

    @Test
    fun queue() {
        val t = Target(favorite = true)
        assertEquals(
            setOf(QueueSwipeAction.REMOVE, QueueSwipeAction.PLAY_NEXT, QueueSwipeAction.MOVE_TO_END),
            QueueSwipeAction.entries.filter { SwipeRules.applies(it, t, serverAvailable = true) }.toSet(),
        )
    }
}

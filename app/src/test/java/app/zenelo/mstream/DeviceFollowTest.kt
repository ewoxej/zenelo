package app.zenelo.mstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFollowTest {
    private fun dev(id: String, playing: Boolean = false, online: Boolean = true, lastPlayedAt: String? = null, queue: Int = 3) =
        PlaybackDevice(id, id, "web", online, playing, 0, null, null, queue, lastPlayedAt = lastPlayedAt, updatedAt = null, receivedAt = 0)

    private fun followed(list: List<PlaybackDevice>, localPlaying: Boolean = false, hint: String? = null) =
        MStreamDevices.followed(list, "me", localPlaying, hint)?.id

    @Test
    fun nothingWhileThisOnePlays() {
        assertNull(followed(listOf(dev("me", playing = true, lastPlayedAt = "2"), dev("pc", playing = true, lastPlayedAt = "3")), localPlaying = true))
    }

    @Test
    fun followsTheDevicePlaying() {
        assertEquals("pc", followed(listOf(dev("me", lastPlayedAt = "5"), dev("pc", playing = true, lastPlayedAt = "3"))))
    }

    @Test
    fun aPausedDeviceStaysFollowed() {
        assertEquals("pc", followed(listOf(dev("me", lastPlayedAt = "2"), dev("pc", lastPlayedAt = "3"))))
    }

    @Test
    fun ownPauseKeepsOwnPlayer() {
        assertNull(followed(listOf(dev("me", lastPlayedAt = "4"), dev("pc", lastPlayedAt = "3"))))
    }

    @Test
    fun offlineDeviceIsNotFollowed() {
        assertNull(followed(listOf(dev("me", lastPlayedAt = "2"), dev("pc", online = false, lastPlayedAt = "3"))))
    }

    @Test
    fun anEmptyQueueDoesNotCount() {
        assertNull(followed(listOf(dev("pc", lastPlayedAt = "3", queue = 0))))
    }

    @Test
    fun theDeviceJustMovedToWins() {
        assertEquals("tv", followed(listOf(dev("me", lastPlayedAt = "4"), dev("tv")), hint = "tv"))
    }
}

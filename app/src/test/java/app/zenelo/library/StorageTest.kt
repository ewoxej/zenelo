package app.zenelo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {
    private val local = "/sdcard/Music/a.flac"
    private val download = "/sdcard/mstream/music/b.flac"
    private val server = "mstream://music/b.flac"
    private val cloud = "mstream://music/c.flac"
    private val linked = setOf(download, server)

    @Test
    fun marks() {
        assertEquals(StorageMark.CLOUD, Storage.of(listOf(cloud), linked).mark)
        assertEquals(StorageMark.PARTIAL, Storage.of(listOf(cloud, download), linked).mark)
        assertEquals(StorageMark.DOWNLOADED, Storage.of(listOf(local, download), linked).mark)
        // A server track with a local copy counts as downloaded (the mStream source shows it).
        assertEquals(StorageMark.DOWNLOADED, Storage.of(listOf(server), linked).mark)
        assertEquals(StorageMark.NONE, Storage.of(listOf(local), linked).mark)
        assertEquals(StorageMark.NONE, Storage.of(emptyList(), linked).mark)
    }
}

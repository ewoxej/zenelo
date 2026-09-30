package app.zenelo.mstream

import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.LibrarySource
import app.zenelo.library.Library
import app.zenelo.library.LibraryMerge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MStreamTest {
    private fun track(path: String, title: String?, artist: String?, album: String?, number: Int?, size: Long = 100) = TrackEntity(
        path, path.substringBeforeLast('/'), 0, size, title, artist, album, null, number, 1000, 0, 0, 0, false, false, null, null,
        app.zenelo.library.TagReader.albumKey(artist, album),
    )

    private val local = track("/sdcard/Music/Radiohead/OK Computer/01 Airbag.flac", "Airbag", "Radiohead", "OK Computer", 1)
    private val serverTwin = track("mstream://music/Radiohead/OK Computer/01 Airbag.flac", "Airbag", "radiohead", "OK Computer", 1)
    private val serverOnly = track("mstream://music/Radiohead/OK Computer/02 Paranoid Android.flac", "Paranoid Android", "Radiohead", "OK Computer", 2)
    private val untaggedLocal = track("/sdcard/Music/x/song.mp3", null, null, null, null, size = 555)
    private val untaggedServer = track("mstream://music/y/song.mp3", null, null, null, null, size = 555)
    private val sameNameOtherSize = track("mstream://music/z/song.mp3", null, null, null, null, size = 556)

    private val all = listOf(local, serverTwin, serverOnly, untaggedLocal, untaggedServer, sameNameOtherSize)

    @Test
    fun allPrefersLocalTwins() {
        val visible = LibraryMerge.visible(all, LibrarySource.ALL).map { it.path }
        assertEquals(listOf(local.path, untaggedLocal.path, serverOnly.path, sameNameOtherSize.path), visible)
    }

    @Test
    fun oneSourceOnly() {
        assertEquals(listOf(local.path, untaggedLocal.path), LibraryMerge.visible(all, LibrarySource.LOCAL).map { it.path })
        assertEquals(4, LibraryMerge.visible(all, LibrarySource.MSTREAM).size)
    }

    @Test
    fun twins() {
        assertEquals(
            mapOf(serverTwin.path to local.path, untaggedServer.path to untaggedLocal.path),
            LibraryMerge.twins(all),
        )
    }

    @Test
    fun mergedAlbumIsOneAndNotRemote() {
        val albums = Library.albumsOf(LibraryMerge.visible(all, LibrarySource.ALL))
        val ok = albums.single { it.album == "OK Computer" }
        assertEquals(2, ok.tracks)
        assertFalse(ok.remote)
        assertTrue(Library.albumsOf(listOf(serverOnly)).single().remote)
    }

    @Test
    fun parsesManifestEntry() {
        val json = JSONObject(
            """
            {"filepath": "music/Server Band/Night Drive/01 Night Drive 1.flac",
             "metadata": {"title": "Night Drive 1", "artist": "Server Band", "artist-display": "Server Band feat. X",
               "album": "Night Drive", "album-art": "abc.jpeg", "track": 1, "duration": 170.5, "rating": 8,
               "replaygain-track": -6.5, "has-lyrics": false, "has-synced-lyrics": true},
             "id": 3, "file-size": 4096, "modified": 1790710705751, "hash": "h", "audio-hash": "ah", "format": "flac"}
            """,
        )
        val e = MStreamClient.parseEntry(json)
        assertEquals("music/Server Band/Night Drive/01 Night Drive 1.flac", e.filepath)
        assertEquals(170_500L, e.durationMs)
        assertEquals(8, e.rating)
        assertEquals(-6.5f, e.replayGainDb!!, 0f)
        assertEquals("ah", e.hash)
        assertTrue(e.hasSyncedLyrics)

        val t = MStreamSync.toTrack(e)
        assertEquals("mstream://music/Server Band/Night Drive/01 Night Drive 1.flac", t.path)
        assertEquals("mstream://music/Server Band/Night Drive", t.dir)
        assertEquals("Server Band feat. X", t.artist)
        assertEquals("server band|night drive", t.albumKey)
        assertTrue(t.lossless)
    }

    @Test
    fun nullsInTheManifest() {
        val e = MStreamClient.parseEntry(JSONObject("""{"filepath": "music/a.mp3", "metadata": {"title": null, "album": null, "track": null, "rating": null, "replaygain-track": null}}"""))
        assertNull(e.title)
        assertNull(e.track)
        assertNull(e.rating)
        assertNull(e.replayGainDb)
    }

    @Test
    fun serverAddresses() {
        assertEquals("http://192.168.1.5:3000/", MStreamClient.normalizeUrl("192.168.1.5:3000")!!.toString())
        assertEquals("https://music.example.com/mstream/", MStreamClient.normalizeUrl("https://music.example.com/mstream/")!!.toString())
        assertNull(MStreamClient.normalizeUrl("  "))
    }

    @Test
    fun paths() {
        val p = MStreamPaths.of("music/A/B/01 #1?.flac")
        assertTrue(MStreamPaths.isRemote(p))
        assertEquals("music/A/B/01 #1?.flac", MStreamPaths.serverPath(p))
        assertEquals("01 #1?.flac", MStreamPaths.fileName(p))
        assertFalse(MStreamPaths.isRemote("/storage/emulated/0/a.flac"))
    }

    @Test
    fun remoteFolders() {
        val root = RemoteFolders.ROOT
        // Children of the root are "<prefix><vpath>", with prefix = indexDir(root) + "/".
        assertEquals("mstream://", RemoteFolders.indexDir(root) + "/")
        val music = java.io.File("/mstream/music/Server Band")
        assertEquals("mstream://music/Server Band", RemoteFolders.indexDir(music))
        assertEquals(music, RemoteFolders.folder("mstream://music/Server Band"))
        assertEquals(root, RemoteFolders.folder("mstream://"))
        assertTrue(RemoteFolders.isRemote(music))
        assertFalse(RemoteFolders.isRemote(java.io.File("/mstreamer")))
        assertEquals("/storage/emulated/0/Music", RemoteFolders.indexPath(java.io.File("/storage/emulated/0/Music")))
    }

    @Test
    fun serverTimes() {
        assertEquals(1_790_709_133_745L, MStreamClient.parseTime("2026-09-29 19:12:13.745"))
        assertEquals(1_790_709_133_000L, MStreamClient.parseTime("2026-09-29T19:12:13Z"))
        assertEquals(1_790_709_133_000L, MStreamClient.parseTime(1_790_709_133))
        assertNull(MStreamClient.parseTime(null))
    }

    @Test
    fun localCopyWithoutGenreTakesTheServers() {
        val visible = LibraryMerge.visible(listOf(local, serverTwin.copy(genre = "Alternative Rock")), LibrarySource.ALL)
        assertEquals(listOf(local.path), visible.map { it.path })
        assertEquals("Alternative Rock", visible.single().genre)
        // Its own tag wins.
        assertEquals("Rock", LibraryMerge.visible(listOf(local.copy(genre = "Rock"), serverTwin.copy(genre = "Alt")), LibrarySource.ALL).single().genre)
    }

    @Test
    fun linkedCopyStaysHiddenAfterTagEdits() {
        // Retitled locally: no longer a twin by tags or size, but the link keeps the server copy out of "All".
        val edited = local.copy(title = "Air Bag", size = 999)
        assertEquals(listOf(edited.path, serverTwin.path), LibraryMerge.visible(listOf(edited, serverTwin), LibrarySource.ALL).map { it.path })
        val links = mapOf(serverTwin.path to edited.path)
        assertEquals(listOf(edited.path), LibraryMerge.visible(listOf(edited, serverTwin.copy(genre = "Alt")), LibrarySource.ALL, links).map { it.path })
        assertEquals("Alt", LibraryMerge.visible(listOf(edited, serverTwin.copy(genre = "Alt")), LibrarySource.ALL, links).single().genre)
    }

    @Test
    fun recentPlaysOfBothCopiesAreOneRow() {
        fun play(path: String, at: Long) = app.zenelo.data.db.RecentPlay(path, at, "Airbag", "Radiohead", null, null)
        val links = mapOf(serverTwin.path to local.path)
        // The server's (web player) play is newer; ours is older: one row, the local copy, the newest time.
        val list = listOf(play(serverTwin.path, 200), play(local.path, 100), play(serverOnly.path, 150))
        val recent = Library.recentOf(list, links)
        assertEquals(listOf(local.path to 200L, serverOnly.path to 150L), recent.map { it.path to it.playedAt })
    }

    @Test
    fun anotherVersionIsNoTwin() {
        // Same tags, but the server's is a 7-minute live take: both stay.
        val live = serverTwin.copy(durationMs = 420_000)
        assertEquals(emptyMap<String, String>(), LibraryMerge.twins(listOf(local, live)))
        assertEquals(2, LibraryMerge.visible(listOf(local, live), LibrarySource.ALL).size)
        // Unknown length on one side: tags decide.
        assertEquals(mapOf(serverTwin.path to local.path), LibraryMerge.twins(listOf(local, serverTwin.copy(durationMs = 0))))
    }

    @Test
    fun linksSurviveTagFixesNotRetagging() {
        assertTrue(LibraryMerge.stillSame(local.copy(album = "OK Computer OKNOTOK", trackNumber = 7), serverTwin))
        assertTrue(LibraryMerge.stillSame(local.copy(title = "Air Bag"), serverTwin)) // same file name
        val retagged = local.copy(path = "/sdcard/Music/x.flac", title = "Lucky", trackNumber = 11)
        assertFalse(LibraryMerge.stillSame(retagged, serverTwin))
        assertFalse(LibraryMerge.stillSame(local.copy(durationMs = 500_000), serverTwin))
    }
}

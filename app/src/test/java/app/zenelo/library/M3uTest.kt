package app.zenelo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uTest {
    @Test
    fun parsesExtinfAndSkipsComments() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:215,Air - La femme d'argent\r\nAir/01 La femme.flac\r\n\r\n# comment\r\nplain.mp3\r\n"
        assertEquals(
            listOf(M3u.Entry("Air/01 La femme.flac", "Air - La femme d'argent", 215), M3u.Entry("plain.mp3", null, null)),
            M3u.parse(text),
        )
    }

    @Test
    fun extinfWithoutDuration() {
        assertEquals(M3u.Entry("a.flac", "Title", null), M3u.parse("#EXTINF:-1,Title\na.flac").single())
    }

    @Test
    fun resolvesRelativeAbsoluteAndUris() {
        val base = "/storage/emulated/0/Music/Playlists"
        assertEquals("/storage/emulated/0/Music/Air/x.flac", M3u.resolve("../Air/x.flac", base))
        assertEquals("/storage/emulated/0/Music/Air/x.flac", M3u.resolve("..\\Air\\x.flac", base))
        assertEquals("/storage/emulated/0/Music/Playlists/x.flac", M3u.resolve("./x.flac", base))
        assertEquals("/sdcard/Music/x.flac", M3u.resolve("/sdcard/Music/x.flac", null))
        assertEquals("/storage/emulated/0/Music/A B/x+y.flac", M3u.resolve("file:///storage/emulated/0/Music/A%20B/x+y.flac", null))
        assertNull(M3u.resolve("x.flac", null))
        assertNull(M3u.resolve("http://radio/stream", base))
        assertNull(M3u.resolve("C:\\Music\\x.flac", base))
    }

    @Test
    fun bestMatchPrefersLongestTail() {
        val candidates = listOf(
            "/storage/emulated/0/Music/Air/Moon Safari/01 Intro.flac",
            "/storage/emulated/0/Music/Other/Best Of/01 Intro.flac",
        )
        assertEquals(candidates[0], M3u.bestMatch("C:\\Music\\Air\\Moon Safari\\01 Intro.flac", candidates))
        assertEquals(candidates[1], M3u.bestMatch("../best of/01 intro.flac", candidates))
        // Only the file name in common with both: ambiguous.
        assertNull(M3u.bestMatch("D:/x/01 Intro.flac", candidates))
        assertNull(M3u.bestMatch("nothing.flac", candidates))
    }

    @Test
    fun writesRelativePathsOnTheSameVolume() {
        val tracks = listOf(
            M3u.Track("/storage/emulated/0/Music/Air/01 La femme.flac", "Air", "La femme", 215_400),
            M3u.Track("/storage/1234-ABCD/Music/y.flac", null, null, null),
        )
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:215,Air - La femme\n../Air/01 La femme.flac\n" +
                "#EXTINF:-1,y\n/storage/1234-ABCD/Music/y.flac\n",
            M3u.write(tracks, "/storage/emulated/0/Music/Playlists"),
        )
    }

    @Test
    fun writeWithoutKnownFolderIsAbsolute() {
        val text = M3u.write(listOf(M3u.Track("/storage/emulated/0/a.flac", null, "A", 1000)), null)
        assertEquals("#EXTM3U\n#EXTINF:1,A\n/storage/emulated/0/a.flac\n", text)
    }

    @Test
    fun roundTrip() {
        val base = "/storage/emulated/0/Playlists"
        val paths = listOf("/storage/emulated/0/Music/a b/1.flac", "/storage/emulated/0/Playlists/2.mp3")
        val text = M3u.write(paths.map { M3u.Track(it, null, null, null) }, base)
        assertEquals(paths, M3u.parse(text).map { M3u.resolve(it.location, base) })
    }
}

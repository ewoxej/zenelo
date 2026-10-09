package app.zenelo.library

import app.zenelo.library.ScanScope.Companion.under
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScanScopeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val volumes = listOf(File("/storage/emulated/0"), File("/storage/1234-ABCD"))

    @Test
    fun underIsPathWise() {
        assertTrue("/a/b/c.mp3".under("/a/b"))
        assertTrue("/a/b".under("/a/b/"))
        assertFalse("/a/bc/x.mp3".under("/a/b"))
    }

    @Test
    fun allStorage() {
        val scope = ScanScope()
        assertEquals(volumes, scope.roots(volumes))
        assertTrue(scope.inLibrary("/storage/emulated/0/Ringtones/x.ogg"))
    }

    @Test
    fun whitelistWalksOnlyItsOutermostFolders() {
        val scope = ScanScope(
            ScanMode.ONLY,
            include = setOf("/storage/emulated/0/Music", "/storage/emulated/0/Music/Rock", "/storage/1234-ABCD/FLAC", "/mnt/gone"),
            always = setOf("/storage/emulated/0/mstream"),
        )
        assertEquals(
            listOf("/storage/1234-ABCD/FLAC", "/storage/emulated/0/Music", "/storage/emulated/0/mstream"),
            scope.roots(volumes).map { it.path },
        )
        assertTrue(scope.inLibrary("/storage/emulated/0/Music/Rock/a.flac"))
        assertTrue(scope.inLibrary("/storage/emulated/0/mstream/music/a.flac")) // downloads always
        assertFalse(scope.inLibrary("/storage/emulated/0/Ringtones/x.ogg"))
    }

    @Test
    fun emptyWhitelistScansEverything() {
        val scope = ScanScope(ScanMode.ONLY, always = setOf("/storage/emulated/0/mstream"))
        assertEquals(volumes, scope.roots(volumes))
        assertTrue(scope.inLibrary("/storage/emulated/0/Music/a.flac"))
    }

    @Test
    fun blacklistLeavesFoldersOut() {
        val scope = ScanScope(ScanMode.EXCEPT, exclude = setOf("/storage/emulated/0/Ringtones", "/storage/emulated/0"), always = setOf("/storage/emulated/0/mstream"))
        assertTrue(scope.excluded("/storage/emulated/0/Ringtones/x.ogg"))
        assertFalse(scope.excluded("/storage/emulated/0/mstream/x.flac")) // the download folder can't be blacklisted
        assertFalse(scope.inLibrary("/storage/emulated/0/Music/a.flac"))
        assertTrue(scope.inLibrary("/storage/1234-ABCD/a.flac"))
        // The lists are kept apart: switching the mode loses neither.
        assertEquals(ScanMode.ALL, scope.copy(mode = ScanMode.ALL).mode)
        assertTrue(scope.copy(mode = ScanMode.ALL).inLibrary("/storage/emulated/0/Ringtones/x.ogg"))
    }

    @Test
    fun nomediaHidesTheFolderAndBelow() {
        val volume = tmp.newFolder("vol")
        val hidden = File(volume, "Rec/2024").apply { mkdirs() }
        File(volume, "Rec/.nomedia").createNewFile()
        val music = File(volume, "Music").apply { mkdirs() }
        val scope = ScanScope()
        assertTrue(scope.skips(File(volume, "Rec")))
        assertFalse(scope.allows(hidden, listOf(volume)))
        assertTrue(scope.allows(music, listOf(volume)))
        assertTrue(scope.copy(nomedia = false).allows(hidden, listOf(volume)))
        assertFalse(scope.copy(nomedia = false).skips(File(volume, "Rec")))
    }
}

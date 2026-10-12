package app.zenelo.library

import app.zenelo.data.db.HiddenEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    private val hidden = Hidden.of(
        listOf(
            HiddenEntity("/m/Ringtones/beep.mp3", "beep"),
            HiddenEntity("/m/Voice", "Voice", folder = true),
            HiddenEntity("mstream://music/Live", "Live", folder = true),
        ),
    )

    @Test
    fun tracksAndFoldersByPath() {
        assertTrue(hidden.hides("/m/Ringtones/beep.mp3"))
        assertFalse(hidden.hides("/m/Ringtones/boop.mp3"))
        assertTrue(hidden.hides("/m/Voice/2024/note.m4a"))
        assertFalse(hidden.hides("/m/Voices/x.mp3")) // path-wise, not a string prefix
        assertTrue(hidden.hides("mstream://music/Live/a.flac"))
        assertTrue(hidden.hidesFolder("/m/Voice/2024"))
        assertFalse(hidden.hidesFolder("/m"))
    }

    @Test
    fun aTrackIsHiddenUnderItsOtherPathToo() {
        // Hidden as the local copy, shown as the server track (mStream source) — still hidden.
        assertTrue(hidden.hides("mstream://music/Ringtones/beep.mp3", copies = listOf("/m/Ringtones/beep.mp3")))
        // Hidden in the server's folder, played as the local copy.
        assertTrue(hidden.hides("/m/Downloads/a.flac", copies = listOf("mstream://music/Live/a.flac")))
        assertFalse(Hidden().hides("/anything"))
    }
}

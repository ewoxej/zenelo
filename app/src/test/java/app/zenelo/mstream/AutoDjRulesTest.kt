package app.zenelo.mstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjRulesTest {
    @Test
    fun camelotSpellings() {
        assertEquals("8A", AutoDjRules.toCamelot("A minor"))
        assertEquals("8A", AutoDjRules.toCamelot("Am"))
        assertEquals("8A", AutoDjRules.toCamelot("Amin"))
        assertEquals("8B", AutoDjRules.toCamelot("C"))
        assertEquals("8B", AutoDjRules.toCamelot("Cmaj"))
        assertEquals("1A", AutoDjRules.toCamelot("G#m"))
        assertEquals("1A", AutoDjRules.toCamelot("Ab minor"))
        assertEquals("12B", AutoDjRules.toCamelot("12b"))
        assertNull(AutoDjRules.toCamelot("13A"))
        assertNull(AutoDjRules.toCamelot("H minor"))
        assertNull(AutoDjRules.toCamelot(null))
    }

    @Test
    fun wheelNeighboursWrap() {
        assertEquals(setOf("8A", "8B", "7A", "7B", "9A", "9B"), AutoDjRules.neighbours("8A"))
        assertEquals(setOf("12B", "12A", "11B", "11A", "1B", "1A"), AutoDjRules.neighbours("12B"))
        assertEquals(setOf("1A", "1B", "12A", "12B", "2A", "2B"), AutoDjRules.neighbours("1A"))
    }

    @Test
    fun bpmWindows() {
        // As the web app: normal, half and double tempo, clamped to 20–300.
        assertEquals(listOf(112..128, 56..64, 224..256), AutoDjRules.bpmRanges(120.0, 8))
        assertEquals(listOf(152..168, 76..84), AutoDjRules.bpmRanges(160.0, 8))
        assertTrue(AutoDjRules.bpmRanges(10.0, 8).isEmpty())
        assertEquals(121.0, AutoDjRules.average(listOf(120.0, 121.0, 122.4))!!, 0.0)
        assertNull(AutoDjRules.average(emptyList()))
    }

    @Test
    fun blockedPicks() {
        fun song(bpm: Double?, key: String?) = ServerSong("music/a.mp3", "a", "x", bpm, key)
        val keys = AutoDjRules.neighbours("8A")
        assertFalse(AutoDjRules.blocked(song(125.0, "Am"), 120.0, 8, keys))
        assertFalse(AutoDjRules.blocked(song(61.0, null), 120.0, 8, keys)) // half tempo
        assertTrue(AutoDjRules.blocked(song(140.0, "Am"), 120.0, 8, keys))
        assertTrue(AutoDjRules.blocked(song(120.0, "F# major"), 120.0, 8, keys))
        // Untagged picks pass: the server ran out of tagged ones.
        assertFalse(AutoDjRules.blocked(song(null, null), 120.0, 8, keys))
    }

    @Test
    fun parsesPicks() {
        val s = MStreamClient.parseSong(org.json.JSONObject("""{"filepath":"/music/x.mp3","metadata":{"title":"X","artist":null,"bpm":94,"musical-key":"B minor"}}"""))
        assertEquals("music/x.mp3", s.filepath)
        assertNull(s.artist)
        assertEquals(94.0, s.bpm!!, 0.0)
        assertEquals("10A", AutoDjRules.toCamelot(s.musicalKey))
    }
}

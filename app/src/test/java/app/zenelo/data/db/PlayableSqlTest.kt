package app.zenelo.data.db

import app.zenelo.library.AudioFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableSqlTest {
    @Test
    fun folderCountsTakeThePlayableFormats() {
        val inSql = Regex("LIKE '%\\.(\\w+)'").findAll(PLAYABLE_SQL).map { it.groupValues[1] }.toSet()
        assertEquals(AudioFormats.playable, inSql)
        assertTrue(AudioFormats.extensions.containsAll(AudioFormats.playable))
    }
}

package app.zenelo.online

import app.zenelo.library.ArtistSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSearchTest {
    private val splitter = ArtistSplitter(ArtistSplitter.DEFAULT_SEPARATORS, ArtistSplitter.DEFAULT_EXCEPTIONS)

    private fun candidate(artist: String, title: String, duration: Double?, synced: String? = null, plain: String? = null) =
        LyricsSearch.Candidate(artist, title, duration, synced, plain, instrumental = false)

    @Test
    fun titleVariantsEndInTheCore() {
        assertEquals(listOf("Синячу - Unplugged в подъезде", "Синячу"), LyricsSearch.titleVariants("Синячу - Unplugged в подъезде"))
        assertEquals("Мур-мур-мур", LyricsSearch.titleVariants("Мур-мур-мур [muzhub.net]").last())
        assertEquals("Почему", LyricsSearch.titleVariants("Почему (feat. Ежемесячные)").last())
        assertEquals("Song", LyricsSearch.titleVariants("Song (Remastered 2011) [Explicit]").last())
        assertEquals("Song", LyricsSearch.titleVariants("Song feat. Someone").last())
        // A hyphen inside a word isn't a " - " suffix.
        assertEquals(listOf("Питер-Тайланд"), LyricsSearch.titleVariants("Питер-Тайланд"))
    }

    @Test
    fun artistVariants() {
        assertEquals(listOf("Daft Punk feat. Pharrell", "Daft Punk"), LyricsSearch.artistVariants("Daft Punk feat. Pharrell", null, splitter))
        assertEquals(listOf("A; B", "A", "Album Artist"), LyricsSearch.artistVariants("A; B", "Album Artist", splitter))
        assertEquals(listOf("Solo"), LyricsSearch.artistVariants("Solo", "Various Artists", splitter))
        assertEquals(listOf("Band"), LyricsSearch.artistVariants(null, "Band", splitter))
    }

    @Test
    fun anotherVersionGivesPlainText() {
        // The unplugged version is longer than the one on LRCLIB: its text, without the timing.
        val results = listOf(candidate("Альбина Сексова", "Синячу", 172.0, synced = "[00:01.00]Строка\n[00:03.50]Вторая"))
        val match = LyricsSearch.pick(results, listOf("Синячу - Unplugged в подъезде"), listOf("Альбина Сексова"), durationSec = 201)!!
        assertFalse(match.exact)
        assertNull(match.lyrics.synced)
        assertEquals("Строка\nВторая", match.lyrics.plain)
    }

    @Test
    fun sameLengthPrefersSynced() {
        val results = listOf(
            candidate("Ежемесячные", "Режим сна", 326.0, plain = "plain"),
            candidate("Ежемесячные", "Режим сна", 327.0, synced = "[00:01.00]synced"),
            candidate("Ежемесячные", "Режим сна", 240.0, synced = "[00:01.00]other version"),
        )
        val match = LyricsSearch.pick(results, listOf("Режим сна"), listOf("Ежемесячные"), durationSec = 325)!!
        assertTrue(match.exact)
        assertEquals("[00:01.00]synced", match.lyrics.synced)
    }

    @Test
    fun otherSongsAndArtistsDontMatch() {
        val results = listOf(
            candidate("Ежемесячные", "Интро", 117.0, synced = "x"),
            candidate("Только по дури", "Ежемесячные", 135.0, synced = "x"),
        )
        assertNull(LyricsSearch.pick(results, listOf("Питер-Тайланд"), listOf("Ежемесячные"), durationSec = 200))
    }

    @Test
    fun featuredArtistInTheRecordMatches() {
        val results = listOf(candidate("СД feat. Ежемесячные", "Почему (feat. Ежемесячные)", 291.0, synced = "[00:01.00]x"))
        assertTrue(LyricsSearch.pick(results, listOf("Почему"), listOf("Ежемесячные"), durationSec = 290)!!.exact)
    }

    @Test
    fun caseAndYoDontMatter() {
        val results = listOf(candidate("ЗАВТРАККУСТО", "Мёртвый матрос", 216.0, plain = "p"))
        assertTrue(LyricsSearch.pick(results, listOf("мертвый матрос"), listOf("Завтраккусто"), durationSec = 215)!!.exact)
    }

    @Test
    fun stripTimestampsDropsTags() {
        assertEquals("a\nb", LyricsSearch.stripTimestamps("[ar:Someone]\n[00:01.00]a\n[00:02.00][00:05.00]b"))
    }
}

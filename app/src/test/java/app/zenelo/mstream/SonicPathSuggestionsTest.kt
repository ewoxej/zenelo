package app.zenelo.mstream

import org.junit.Assert.assertEquals
import org.junit.Test

class SonicPathSuggestionsTest {
    @Test
    fun groupsKeepEachTrackOnceAndSkipUnusable() {
        val usable = setOf("a", "b", "c", "d", "e")
        val groups = SonicPathSuggestions.of(
            playing = "a",
            upNext = listOf("b", "local-only", "a"),
            recent = listOf("c", "b", "x"),
            favorites = listOf("d", "e"),
            usable = { it in usable },
            exclude = "e",
        )
        assertEquals(
            listOf(
                SonicPathSuggestions.Group("Playing", listOf("a")),
                SonicPathSuggestions.Group("Up next", listOf("b")),
                SonicPathSuggestions.Group("Recently played", listOf("c")),
                SonicPathSuggestions.Group("Favorites", listOf("d")),
            ),
            groups,
        )
    }

    @Test
    fun emptyGroupsAreLeftOutAndLongOnesCut() {
        val groups = SonicPathSuggestions.of(null, emptyList(), (1..40).map { "$it" }, emptyList(), { true }, null, perGroup = 3)
        assertEquals(listOf(SonicPathSuggestions.Group("Recently played", listOf("1", "2", "3"))), groups)
    }
}

package app.zenelo.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    private val homeWithSettings = Navigation.DEFAULT_HOME
    private val homeWithout = Navigation.DEFAULT_HOME.map { if (it.section == Section.SETTINGS) it.copy(mode = HomeMode.OFF) else it }

    @Test
    fun settingsAsATabIsEnough() {
        assertTrue(Navigation.settingsReachable(listOf(Section.SETTINGS), homeWithout))
    }

    @Test
    fun settingsOnHomeNeedsHomeAsAPage() {
        assertTrue(Navigation.settingsReachable(listOf(Section.HOME, Section.FOLDERS), homeWithSettings))
        assertFalse(Navigation.settingsReachable(listOf(Section.FOLDERS), homeWithSettings))
        // No bar at all: Home is the only page.
        assertTrue(Navigation.settingsReachable(emptyList(), homeWithSettings))
        assertFalse(Navigation.settingsReachable(emptyList(), homeWithout))
    }

    @Test
    fun homeListIsCompletedWithMissingSectionsOff() {
        val home = Navigation.completeHome(listOf(HomeItem(Section.ALBUMS, HomeMode.LIST)))
        assertEquals(HomeItem(Section.ALBUMS, HomeMode.LIST), home.first())
        assertEquals(Section.entries.size - 1, home.size)
        assertTrue(home.drop(1).all { it.mode == HomeMode.OFF })
    }

    @Test
    fun sortOrderRoundTrips() {
        val order = SortOrder(SortField.DATE_ADDED, descending = true)
        assertEquals(order, SortOrder.decode(order.encode()))
        assertEquals("BY DATE ADDED · NEWEST FIRST", order.label)
    }
}

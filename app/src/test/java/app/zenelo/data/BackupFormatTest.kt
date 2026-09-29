package app.zenelo.data

import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.PlayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupFormatTest {
    @Test
    fun roundTrip() {
        val data = BackupData(
            createdAt = 1_790_000_000_000,
            appVersion = "1.2.3",
            settings = mapOf(
                "tabs" to "HOME,FOLDERS",
                "confirm_remove" to true,
                "crossfade_ms" to 5000,
                "preamp_db" to 2.5f,
                "some_long" to 7_000_000_000L,
                "a_set" to setOf("x", "y"),
            ),
            favorites = listOf(
                FavoriteEntity("/m/a.flac", FavoriteKind.TRACK, "A", "Artist", 10),
                FavoriteEntity("album:k", FavoriteKind.ALBUM, "Album", null, 20),
            ),
            playlists = listOf(BackupPlaylist("Evening", 30, listOf("/m/a.flac", "/m/b.flac")), BackupPlaylist("Empty", 40, emptyList())),
            plays = listOf(PlayEntity(path = "/m/a.flac", playedAt = 50), PlayEntity(path = "/m/b.flac", playedAt = 60)),
        )
        assertEquals(data, BackupFormat.fromJson(BackupFormat.toJson(data)))
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.fromJson("#EXTM3U") }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.fromJson("{\"tabs\": 1}") }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.fromJson("{\"format\": \"zenelo-backup\", \"version\": 99}") }
    }

    @Test
    fun skipsUnknownFavoriteKindsAndSettingTypes() {
        val json = """
            {"format": "zenelo-backup", "version": 1,
             "settings": {"a": {"t": "s", "v": "x"}, "b": {"t": "future", "v": 1}},
             "favorites": [{"path": "p", "kind": "PODCAST", "title": "t"}]}
        """.trimIndent()
        val data = BackupFormat.fromJson(json)
        assertEquals(mapOf<String, Any>("a" to "x"), data.settings)
        assertEquals(emptyList<FavoriteEntity>(), data.favorites)
    }
}

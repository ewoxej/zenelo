package app.zenelo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    abstract fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    abstract fun observeIsFavorite(path: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    abstract suspend fun isFavorite(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE path = :path")
    abstract suspend fun delete(path: String)

    /** Returns true if the item is a favorite after the call. */
    @Transaction
    open suspend fun toggle(favorite: FavoriteEntity): Boolean {
        if (isFavorite(favorite.path)) {
            delete(favorite.path)
            return false
        }
        insert(favorite)
        return true
    }
}

@Dao
abstract class PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.createdAt,
               (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount
        FROM playlists p ORDER BY p.createdAt DESC
        """,
    )
    abstract fun observeWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    abstract fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun delete(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun nextPosition(playlistId: Long): Int

    @Insert
    abstract suspend fun insertEntry(entry: PlaylistEntryEntity)

    @Transaction
    open suspend fun append(playlistId: Long, path: String) {
        insertEntry(PlaylistEntryEntity(playlistId, nextPosition(playlistId), path))
    }
}

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM loudness WHERE path = :path")
    suspend fun get(path: String): LoudnessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LoudnessEntity)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun get(path: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE path IN (:paths)")
    suspend fun getMany(paths: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path")
    fun observe(path: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE dir = :dir")
    fun observeInDir(dir: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE dir = :dir AND albumKey IS :albumKey")
    suspend fun albumTracksInDir(dir: String, albumKey: String?): List<TrackEntity>

    @Query("SELECT path, modified, size FROM tracks")
    suspend fun stamps(): List<TrackStamp>

    @Query("SELECT * FROM tracks WHERE hasArtwork = 0")
    suspend fun withoutArtwork(): List<TrackEntity>

    /** Tracks with no lyrics row yet, or an empty result older than [retryBefore]. */
    @Query(
        """
        SELECT t.* FROM tracks t LEFT JOIN lyrics l ON l.path = t.path
        WHERE l.path IS NULL
           OR (l.synced IS NULL AND l.plain IS NULL AND l.instrumental = 0 AND l.fetchedAt < :retryBefore)
        """,
    )
    suspend fun needingLyrics(retryBefore: Long): List<TrackEntity>

    @Query("SELECT COUNT(*) AS tracks, COALESCE(SUM(hasArtwork), 0) AS withArtwork FROM tracks")
    fun observeStats(): Flow<LibraryStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun delete(paths: List<String>)
}

@Dao
interface CoverDao {
    @Query("SELECT * FROM covers WHERE albumKey = :albumKey")
    suspend fun get(albumKey: String): CoverEntity?

    @Query("SELECT * FROM covers WHERE albumKey IN (:albumKeys)")
    suspend fun getMany(albumKeys: List<String>): List<CoverEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cover: CoverEntity)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE path = :path")
    suspend fun get(path: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE path = :path")
    fun observe(path: String): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lyrics: LyricsEntity)
}

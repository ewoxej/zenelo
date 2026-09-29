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

    @Query("SELECT * FROM favorites")
    abstract suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    abstract suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(favorites: List<FavoriteEntity>)

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
               (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount,
               (SELECT e.path FROM playlist_entries e WHERE e.playlistId = p.id ORDER BY e.position LIMIT 1) AS coverPath
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

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    abstract fun observe(playlistId: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT e.position AS position, e.path AS path, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS durationMs
        FROM playlist_entries e LEFT JOIN tracks t ON t.path = e.path
        WHERE e.playlistId = :playlistId ORDER BY e.position
        """,
    )
    abstract fun observeTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    @Query("SELECT path FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    abstract suspend fun paths(playlistId: Long): List<String>

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    abstract suspend fun rename(playlistId: Long, name: String)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun clear(playlistId: Long)

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Transaction
    open suspend fun appendAll(playlistId: Long, paths: List<String>) {
        val start = nextPosition(playlistId)
        insertEntries(paths.mapIndexed { i, path -> PlaylistEntryEntity(playlistId, start + i, path) })
    }

    @Query("SELECT * FROM playlists ORDER BY createdAt")
    abstract suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    abstract suspend fun allEntries(): List<PlaylistEntryEntity>

    @Query("DELETE FROM playlist_entries")
    abstract suspend fun deleteAllEntries()

    @Query("DELETE FROM playlists")
    abstract suspend fun deleteAllPlaylists()

    /** A new playlist holding [paths]; returns its id. */
    @Transaction
    open suspend fun create(name: String, paths: List<String>, createdAt: Long = System.currentTimeMillis()): Long {
        val id = insert(PlaylistEntity(name = name, createdAt = createdAt))
        insertEntries(paths.mapIndexed { i, path -> PlaylistEntryEntity(id, i, path) })
        return id
    }

    /** Rewrites the playlist as [paths] (reorder, removal): positions become 0..n-1. */
    @Transaction
    open suspend fun replace(playlistId: Long, paths: List<String>) {
        clear(playlistId)
        insertEntries(paths.mapIndexed { i, path -> PlaylistEntryEntity(playlistId, i, path) })
    }
}

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM loudness WHERE path = :path")
    suspend fun get(path: String): LoudnessEntity?

    /** Measurements of an album's tracks in one folder (for album gain without tags). */
    @Query(
        """
        SELECT l.* FROM loudness l JOIN tracks t ON t.path = l.path
        WHERE t.dir = :dir AND t.albumKey IS :albumKey AND l.fileModified = t.modified AND l.integratedLufs > -100
        """,
    )
    suspend fun forAlbum(dir: String, albumKey: String?): List<LoudnessEntity>

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

    /** Paths ending in "/[name]" (LIKE: `_` / `%` in the name match loosely; callers check). */
    @Query("SELECT path FROM tracks WHERE path LIKE '%/' || :name")
    suspend fun pathsNamed(name: String): List<String>

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

    /** Tracks without ReplayGain tags whose loudness hasn't been measured for their current version. */
    @Query(
        """
        SELECT t.* FROM tracks t LEFT JOIN loudness l ON l.path = t.path
        WHERE t.trackGainDb IS NULL AND (l.path IS NULL OR l.fileModified != t.modified)
        """,
    )
    suspend fun needingLoudness(): List<TrackEntity>

    /** Track count and duration per folder, for every folder under a path range (see [DirStats]). */
    @Query(
        """
        SELECT dir, COUNT(*) AS tracks, COALESCE(SUM(durationMs), 0) AS durationMs FROM tracks
        WHERE path >= :from AND path < :to GROUP BY dir
        """,
    )
    fun observeDirStats(from: String, to: String): Flow<List<DirStats>>

    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) AS tracks, COALESCE(SUM(hasArtwork), 0) AS withArtwork FROM tracks")
    fun observeStats(): Flow<LibraryStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<TrackEntity>)

    /** The mStream server's tracks (see `MStreamPaths`). */
    @Query("DELETE FROM tracks WHERE path LIKE 'mstream://%'")
    suspend fun deleteRemote()

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun delete(paths: List<String>)
}

@Dao
interface PlayDao {
    @Insert
    suspend fun insert(play: PlayEntity)

    /** Latest play of each track since [since], newest first. */
    @Query(
        """
        SELECT p.path AS path, MAX(p.playedAt) AS playedAt, t.title AS title, t.artist AS artist,
               t.album AS album, t.durationMs AS durationMs
        FROM plays p LEFT JOIN tracks t ON t.path = p.path
        WHERE p.playedAt >= :since GROUP BY p.path ORDER BY playedAt DESC
        """,
    )
    fun observeSince(since: Long): Flow<List<RecentPlay>>

    @Query("SELECT DISTINCT path FROM plays WHERE playedAt >= :since")
    suspend fun pathsSince(since: Long): List<String>

    @Query("SELECT * FROM plays ORDER BY playedAt")
    suspend fun all(): List<PlayEntity>

    @Query("DELETE FROM plays")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(plays: List<PlayEntity>)

    @Query("DELETE FROM plays WHERE playedAt < :before")
    suspend fun prune(before: Long)
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
interface PendingWriteDao {
    @Query("SELECT * FROM pending_writes WHERE path = :path")
    suspend fun get(path: String): PendingWriteEntity?

    @Query("SELECT * FROM pending_writes")
    suspend fun all(): List<PendingWriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(write: PendingWriteEntity)

    @Query("DELETE FROM pending_writes WHERE path = :path")
    suspend fun delete(path: String)
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

@Dao
interface RemoteTrackDao {
    @Query("SELECT * FROM remote_tracks WHERE path = :path")
    suspend fun get(path: String): RemoteTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<RemoteTrackEntity>)

    @Query("SELECT path FROM remote_tracks")
    suspend fun paths(): List<String>

    @Query("DELETE FROM remote_tracks WHERE path IN (:paths)")
    suspend fun delete(paths: List<String>)

    @Query("DELETE FROM remote_tracks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM remote_tracks")
    fun observeCount(): Flow<Int>
}

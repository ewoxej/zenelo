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

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path IN (:paths))")
    abstract fun observeIsFavoriteAny(paths: List<String>): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path IN (:paths))")
    abstract suspend fun isFavoriteAny(paths: List<String>): Boolean

    @Query("DELETE FROM favorites WHERE path IN (:paths)")
    abstract suspend fun deletePaths(paths: List<String>)

    /**
     * Returns true if the item is a favorite after the call. [copies]: the same track under other
     * paths (its local / server copy, `ServerLinks.copiesOf`) — liked under any counts, unliking clears all.
     */
    @Transaction
    open suspend fun toggle(favorite: FavoriteEntity, copies: List<String> = emptyList()): Boolean {
        val paths = listOf(favorite.path) + copies
        if (isFavoriteAny(paths)) {
            deletePaths(paths)
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
               (SELECT e.path FROM playlist_entries e WHERE e.playlistId = p.id ORDER BY e.position LIMIT 1) AS coverPath,
               p.remote AS remote
        FROM playlists p WHERE p.deleted = 0 ORDER BY p.remote, p.createdAt DESC
        """,
    )
    abstract fun observeWithCounts(): Flow<List<PlaylistWithCount>>

    /** Every playlist's entries (offline: which playlists have nothing playable). */
    @Query("SELECT * FROM playlist_entries")
    abstract fun observeAllEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    abstract fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun deleteRow(playlistId: Long)

    /** A server playlist: its edits wait for the next push to the server (see `PlaylistEntity`). */
    @Query("UPDATE playlists SET dirty = 1 WHERE id = :playlistId AND remote = 1")
    abstract suspend fun markDirty(playlistId: Long)

    @Query("UPDATE playlists SET deleted = 1, dirty = 1 WHERE id = :playlistId")
    abstract suspend fun markDeleted(playlistId: Long)

    /** Deletes a playlist; a server playlist stays as a tombstone until the server's copy is deleted too. */
    @Transaction
    open suspend fun delete(playlistId: Long) {
        val p = get(playlistId) ?: return
        if (p.remote && p.serverName != null) {
            clear(playlistId)
            markDeleted(playlistId)
        } else {
            deleteRow(playlistId)
        }
    }

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    abstract suspend fun get(playlistId: Long): PlaylistEntity?

    /** Server playlists with edits (or deletions) the server doesn't have yet. */
    @Query("SELECT * FROM playlists WHERE remote = 1 AND dirty = 1")
    abstract suspend fun pendingRemote(): List<PlaylistEntity>

    @Query("SELECT COUNT(*) FROM playlists WHERE remote = 1 AND dirty = 1")
    abstract fun observePendingRemote(): Flow<Int>

    @Query("UPDATE playlists SET dirty = 0, serverName = :serverName WHERE id = :playlistId")
    abstract suspend fun markPushed(playlistId: Long, serverName: String)

    @Query("UPDATE playlists SET serverName = :serverName WHERE id = :playlistId")
    abstract suspend fun setServerName(playlistId: Long, serverName: String)

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun nextPosition(playlistId: Long): Int

    @Insert
    abstract suspend fun insertEntry(entry: PlaylistEntryEntity)

    @Transaction
    open suspend fun append(playlistId: Long, path: String) {
        insertEntry(PlaylistEntryEntity(playlistId, nextPosition(playlistId), path))
        markDirty(playlistId)
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
    abstract suspend fun setName(playlistId: Long, name: String)

    @Transaction
    open suspend fun rename(playlistId: Long, name: String) {
        setName(playlistId, name)
        markDirty(playlistId)
    }

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun clear(playlistId: Long)

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Transaction
    open suspend fun appendAll(playlistId: Long, paths: List<String>) {
        val start = nextPosition(playlistId)
        insertEntries(paths.mapIndexed { i, path -> PlaylistEntryEntity(playlistId, start + i, path) })
        markDirty(playlistId)
    }

    @Query("SELECT * FROM playlists ORDER BY createdAt")
    abstract suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    abstract suspend fun allEntries(): List<PlaylistEntryEntity>

    @Query("DELETE FROM playlist_entries")
    abstract suspend fun deleteAllEntries()

    @Query("DELETE FROM playlists")
    abstract suspend fun deleteAllPlaylists()

    @Query("SELECT * FROM playlists WHERE remote = 1")
    abstract suspend fun remotePlaylists(): List<PlaylistEntity>

    @Query("DELETE FROM playlists WHERE remote = 1")
    abstract suspend fun deleteRemote()

    /**
     * The server's playlists, as they are there now: they replace our clean copies. Ones with edits
     * not pushed yet (or deleted here) stay as they are, and hide the server's copy of their name.
     */
    @Transaction
    open suspend fun replaceRemote(playlists: List<Pair<String, List<String>>>) {
        val old = remotePlaylists()
        val pending = old.filter { it.dirty }.mapNotNull { it.serverName }.toSet()
        // Clean copies are updated in place (same id: an open playlist page stays on it).
        val clean = old.filter { !it.dirty }.associateBy { it.serverName ?: it.name }
        val onServer = playlists.map { it.first }.toSet()
        clean.filterKeys { it !in onServer }.values.forEach { deleteRow(it.id) }
        for ((name, paths) in playlists) {
            if (name in pending) continue
            val existing = clean[name]
            val id = existing?.id ?: insert(PlaylistEntity(name = name, createdAt = 0, remote = true, serverName = name))
            if (existing != null && paths(id) == paths && existing.name == name) continue
            if (existing != null) setName(id, name)
            clear(id)
            insertEntries(paths.mapIndexed { i, path -> PlaylistEntryEntity(id, i, path) })
        }
    }

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
        markDirty(playlistId)
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

    /** Emits on every change of the table (cheap): a trigger for work that then reads what it needs. */
    @Query("SELECT COUNT(*) FROM tracks")
    fun observeChanges(): Flow<Int>

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

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

    /** Folders at or under a range of dirs (the server's folder tree, see `RemoteFolders`). */
    @Query("SELECT DISTINCT dir FROM tracks WHERE dir >= :from AND dir < :to")
    suspend fun dirsIn(from: String, to: String): List<String>

    @Query("SELECT * FROM tracks WHERE dir = :dir")
    suspend fun inDir(dir: String): List<TrackEntity>

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

    @Query("SELECT COUNT(*) FROM plays WHERE path = :path AND playedAt BETWEEN :from AND :to")
    suspend fun countNear(path: String, from: Long, to: Long): Int

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

    @Query("SELECT * FROM remote_tracks")
    suspend fun all(): List<RemoteTrackEntity>

    @Query("UPDATE remote_tracks SET rating = :rating WHERE path = :path")
    suspend fun setRating(path: String, rating: Int?)

    @Query("DELETE FROM remote_tracks WHERE path IN (:paths)")
    suspend fun delete(paths: List<String>)

    @Query("DELETE FROM remote_tracks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM remote_tracks")
    fun observeCount(): Flow<Int>
}

@Dao
interface ServerLinkDao {
    @Query("SELECT * FROM server_links")
    suspend fun all(): List<ServerLinkEntity>

    @Query("SELECT * FROM server_links")
    fun observeAll(): Flow<List<ServerLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ServerLinkEntity>)

    @Query("DELETE FROM server_links WHERE localPath IN (:localPaths)")
    suspend fun delete(localPaths: List<String>)

    @Query("DELETE FROM server_links WHERE serverPath = :serverPath")
    suspend fun deleteServer(serverPath: String)

    @Query("DELETE FROM server_links")
    suspend fun deleteAll()
}

@Dao
interface MStreamOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRating(rating: PendingRatingEntity)

    @Query("SELECT * FROM pending_ratings")
    suspend fun ratings(): List<PendingRatingEntity>

    @Query("DELETE FROM pending_ratings WHERE path = :path")
    suspend fun dropRating(path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlay(play: PlayOutboxEntity)

    @Query("SELECT * FROM play_outbox ORDER BY startedAt LIMIT 200")
    suspend fun plays(): List<PlayOutboxEntity>

    @Query("DELETE FROM play_outbox WHERE id IN (:ids)")
    suspend fun dropPlays(ids: List<String>)
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<DownloadEntity>)

    /** The next one to try (fewest failures first, then oldest). */
    @Query("SELECT * FROM downloads WHERE attempts < :maxAttempts ORDER BY attempts, addedAt LIMIT 1")
    suspend fun next(maxAttempts: Int): DownloadEntity?

    @Query("UPDATE downloads SET attempts = attempts + 1 WHERE path = :path")
    suspend fun failed(path: String)

    @Query("DELETE FROM downloads WHERE path = :path")
    suspend fun remove(path: String)

    @Query("DELETE FROM downloads")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM downloads WHERE attempts < :maxAttempts")
    fun observePending(maxAttempts: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE attempts < :maxAttempts")
    suspend fun pendingCount(maxAttempts: Int): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE attempts >= :maxAttempts")
    fun observeFailed(maxAttempts: Int): Flow<Int>
}

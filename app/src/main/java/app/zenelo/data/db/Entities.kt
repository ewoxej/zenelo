package app.zenelo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FavoriteKind { TRACK, ALBUM, FOLDER, ARTIST }

/**
 * A favorited track file, folder, album or artist. `path`: the absolute file or folder path;
 * albums and artists use [albumFavoriteId] / [artistFavoriteId] of their library key.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val kind: FavoriteKind,
    val title: String,
    val subtitle: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** A copy of one of the user's playlists on the mStream server (read-only here, replaced on sync). */
    @ColumnInfo(defaultValue = "0") val remote: Boolean = false,
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val path: String,
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackCount: Int,
    /** The first track, for a cover. */
    val coverPath: String? = null,
    val remote: Boolean = false,
)

/**
 * Measured loudness (EBU R128) for files without ReplayGain tags.
 * `fileModified` invalidates the entry when the file changes. `integratedLufs` below -100 marks a
 * file that couldn't be measured (e.g. DSD), so it isn't retried until it changes.
 */
@Entity(tableName = "loudness")
data class LoudnessEntity(
    @PrimaryKey val path: String,
    val fileModified: Long,
    val integratedLufs: Float,
    val truePeakDbtp: Float?,
)

/**
 * Tags and stream info of one audio file, read by [app.zenelo.library.TagReader].
 * `modified`/`size` detect changed files; `albumKey` groups tracks sharing one cover lookup.
 */
@Entity(tableName = "tracks", indices = [Index("dir"), Index("albumKey")])
data class TrackEntity(
    @PrimaryKey val path: String,
    val dir: String,
    val modified: Long,
    val size: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val durationMs: Long,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val bitrateKbps: Int,
    val lossless: Boolean,
    val hasArtwork: Boolean,
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val albumKey: String?,
    /** ReplayGain peaks (linear sample peak, 1.0 = full scale); bound the gain so it doesn't clip. */
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val extension: String get() = path.substringAfterLast('.', "").lowercase()
}

data class TrackStamp(val path: String, val modified: Long, val size: Long)

/** One cover lookup per album (or per track when the album is unknown). `file == null`: not found. */
@Entity(tableName = "covers")
data class CoverEntity(
    @PrimaryKey val albumKey: String,
    val file: String?,
    val source: String?,
    val fetchedAt: Long,
)

/**
 * Lyrics per track from a sibling .lrc, the file's tags, or LRCLIB.
 * All of synced/plain null and not instrumental means "looked, found nothing".
 */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val path: String,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
    val source: String,
    val fetchedAt: Long,
) {
    val isEmpty: Boolean get() = synced.isNullOrBlank() && plain.isNullOrBlank() && !instrumental
}

data class LibraryStats(val tracks: Int, val withArtwork: Int)

fun albumFavoriteId(albumKey: String) = "album:$albumKey"

fun artistFavoriteId(artistKey: String) = "artist:$artistKey"

/** A playlist entry with its track's tags (null when the file isn't indexed, e.g. deleted). */
data class PlaylistTrack(
    val position: Int,
    val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/** One play of a track (counted after 30 s, or half of a shorter track), for "Recently played". */
@Entity(tableName = "plays", indices = [Index("playedAt")])
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val playedAt: Long,
)

/** A track's latest play, with its tags (null when the file isn't indexed). */
data class RecentPlay(
    val path: String,
    val playedAt: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/** An album of the library: tracks sharing an album key (album artist + album). */
data class AlbumRow(
    val key: String,
    val album: String,
    val artist: String?,
    val tracks: Int,
    val durationMs: Long,
    /** Newest file mtime: when the album (last) arrived. */
    val added: Long,
    /** A track to take the cover from, one with embedded art if any. */
    val coverPath: String,
    /** All its tracks are on the mStream server only (cloud mark). */
    val remote: Boolean = false,
)

/** An artist of the library, by album artist (else track artist); [key] is its lowercase name. */
data class ArtistRow(
    val key: String,
    val name: String,
    val albums: Int,
    val tracks: Int,
    val added: Long,
    val coverPath: String,
)

data class DirStats(val dir: String, val tracks: Int, val durationMs: Long)

/**
 * A write to a file that was playing when it was requested: applied when the track stops playing.
 * [tagsJson]: edited text tags (see `PendingWrites`), [coverFile]: image to embed. Either may be null.
 */
@Entity(tableName = "pending_writes")
data class PendingWriteEntity(
    @PrimaryKey val path: String,
    val tagsJson: String?,
    val coverFile: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * What the mStream server says about one of its tracks beyond the tags in `tracks` (whose row has
 * the same `mstream://` path): its album-art file, the user's rating (0–10) and whether it
 * carries lyrics.
 */
@Entity(tableName = "remote_tracks")
data class RemoteTrackEntity(
    @PrimaryKey val path: String,
    val artFile: String?,
    val rating: Int?,
    val hasLyrics: Boolean,
    val hash: String?,
)

/** A rating change for a server track not yet accepted by the server (sent on the next sync). */
@Entity(tableName = "pending_ratings")
data class PendingRatingEntity(
    @PrimaryKey val path: String,
    val rating: Int?,
)

/** A play of a server track not yet reported to the server. [id] is the report's idempotency key. */
@Entity(tableName = "play_outbox")
data class PlayOutboxEntity(
    @PrimaryKey val id: String,
    val path: String,
    val startedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
)

/** A server track waiting for "Download" (see `MStreamDownloads`); [attempts] failed tries so far. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val path: String,
    val addedAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

package app.zenelo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FavoriteKind { TRACK, ALBUM, FOLDER }

/** A favorited track file, album or folder. `path` is the absolute file or folder path. */
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

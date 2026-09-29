package app.zenelo.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        LoudnessEntity::class,
        TrackEntity::class,
        CoverEntity::class,
        LyricsEntity::class,
        PendingWriteEntity::class,
        PlayEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = ZeneloDatabase.ReadPeaks::class),
        AutoMigration(from = 5, to = 6, spec = ZeneloDatabase.RecountMp3::class),
    ],
)
abstract class ZeneloDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun playlists(): PlaylistDao
    abstract fun loudness(): LoudnessDao
    abstract fun tracks(): TrackDao
    abstract fun covers(): CoverDao
    abstract fun lyrics(): LyricsDao
    abstract fun pendingWrites(): PendingWriteDao
    abstract fun plays(): PlayDao

    /**
     * 5 adds ReplayGain peaks: files with ReplayGain tags get a stale stamp so the next scan reads
     * them again (not the ones waiting for a tag write: their row holds the edit, not the file's tags).
     */
    class ReadPeaks : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE tracks SET modified = 0 WHERE (trackGainDb IS NOT NULL OR albumGainDb IS NOT NULL) " +
                    "AND path NOT IN (SELECT path FROM pending_writes)",
            )
        }
    }

    /** 6: MP3 lengths counted from the frames where the headers can't tell (`Mp3Scan`): re-read MP3s once. */
    class RecountMp3 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE tracks SET modified = 0 WHERE LOWER(path) LIKE '%.mp3' AND path NOT IN (SELECT path FROM pending_writes)")
        }
    }

    companion object {
        const val NAME = "zenelo.db"

        fun create(context: Context): ZeneloDatabase =
            Room.databaseBuilder(context, ZeneloDatabase::class.java, NAME).build()
    }
}

package app.zenelo.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class ZeneloDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun playlists(): PlaylistDao
    abstract fun loudness(): LoudnessDao
    abstract fun tracks(): TrackDao
    abstract fun covers(): CoverDao
    abstract fun lyrics(): LyricsDao
    abstract fun pendingWrites(): PendingWriteDao

    companion object {
        fun create(context: Context): ZeneloDatabase =
            Room.databaseBuilder(context, ZeneloDatabase::class.java, "zenelo.db").build()
    }
}

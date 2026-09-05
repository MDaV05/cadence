package com.cadence.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cadence.music.data.legacyPrefixSql
import com.cadence.music.data.tags.albumNormKey
import com.cadence.music.data.tags.primaryArtist

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        DownloadEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LyricsEntity::class,
        ArtistInfoEntity::class,
        PendingScrobbleEntity::class,
        CustomThemeEntity::class,
        TrackArtOverrideEntity::class,
        AlbumArtOverrideEntity::class,
        ArtistOverrideEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun downloadDao(): DownloadDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun artistInfoDao(): ArtistInfoDao
    abstract fun overrideDao(): OverrideDao
    abstract fun pendingScrobbleDao(): PendingScrobbleDao
    abstract fun themeDao(): ThemeDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserves user libraries across upgrade (no destructive wipe).
                db.execSQL("ALTER TABLE tracks ADD COLUMN albumMediaId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playlists (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playlist_tracks (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`trackId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks(trackId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tables written but never read anywhere in the app.
                db.execSQL("DROP TABLE IF EXISTS artists")
                db.execSQL("DROP TABLE IF EXISTS cache_entries")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Offline caches for the metadata auto-downloader.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lyrics (" +
                        "`trackId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`syncedLrc` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS artist_info (" +
                        "`name` TEXT NOT NULL PRIMARY KEY, " +
                        "`bio` TEXT, " +
                        "`imageUrl` TEXT, " +
                        "`fetchedAt` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Starred/favorite state + offline scrobble queue.
                db.execSQL("ALTER TABLE tracks ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_scrobbles (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`album` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Playlist cover images + user-defined themes.
                db.execSQL("ALTER TABLE playlists ADD COLUMN coverPath TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_themes (" +
                        "`name` TEXT NOT NULL PRIMARY KEY, " +
                        "`accentLight` INTEGER NOT NULL, " +
                        "`accentDark` INTEGER NOT NULL, " +
                        "`bgLight` INTEGER NOT NULL, " +
                        "`bgDark` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Single-server legacy rows gain the "primary" entry prefix; local rows untouched.
                legacyPrefixSql().forEach { db.execSQL(it) }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Merge key for deluxe/edition variants; backfilled in place so
                // play stats, ids, and playlist references survive.
                db.execSQL("ALTER TABLE tracks ADD COLUMN albumNorm TEXT NOT NULL DEFAULT ''")
                db.query("SELECT id, artistName, albumName FROM tracks").use { c ->
                    val idIdx = c.getColumnIndexOrThrow("id")
                    val artistIdx = c.getColumnIndexOrThrow("artistName")
                    val albumIdx = c.getColumnIndexOrThrow("albumName")
                    while (c.moveToNext()) {
                        val id = c.getLong(idIdx)
                        val artist = c.getString(artistIdx).orEmpty()
                        val album = c.getString(albumIdx).orEmpty()
                        db.execSQL(
                            "UPDATE tracks SET artistName = ?, albumNorm = ? WHERE id = ?",
                            arrayOf(primaryArtist(artist), albumNormKey(album, artist), id),
                        )
                    }
                }
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // User metadata overrides; empty on upgrade, filled by the UI flows.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS track_art_override (" +
                        "`trackId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`path` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS album_art_override (" +
                        "`norm` TEXT NOT NULL PRIMARY KEY, " +
                        "`path` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS artist_override (" +
                        "`name` TEXT NOT NULL PRIMARY KEY, " +
                        "`bio` TEXT, " +
                        "`imagePath` TEXT)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cadence.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                // No paths exist from schema 1/2 (they predate exported schemas);
                // those dev-only installs rebuild destructively instead of crashing.
                // ponytail: downgrade-only — a missing v9 migration must crash loudly, never wipe.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}

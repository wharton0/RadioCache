package com.radiocache.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radiocache.app.data.local.Mappers.toEntity
import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import com.radiocache.app.data.model.StationType
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * RadioCache database, version 1.
 *
 * Pre-release: [fallbackToDestructiveMigration] is on because no user data
 * exists in the wild yet. Before 1.0 release, replace with real
 * AutoMigrations — a TODO is tracked in code for that.
 */
@Database(
    entities = [StationEntity::class, ShowEntity::class, RecordingEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class RadioCacheDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
    abstract fun showDao(): ShowDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        private const val DB_NAME = "radiocache.db"

        @Volatile
        private var instance: RadioCacheDatabase? = null

        fun get(context: Context, logger: AppLogger = AndroidLogger()): RadioCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context, logger).also { instance = it }
            }

        private fun build(context: Context, logger: AppLogger): RadioCacheDatabase =
            Room.databaseBuilder(context, RadioCacheDatabase::class.java, DB_NAME)
                // Real migration keeps user data (subscriptions, recordings index).
                // Destructive fallback remains only for dev-hour disasters.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .addCallback(SeedCallback(logger))
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shows ADD COLUMN lastSyncEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shows ADD COLUMN lastSyncResult TEXT")
            }
        }

        /**
         * Public-domain build: remove every commercial-broadcaster seed row
         * and the recordings they produced. Leaving cached BBC/Capital audio
         * on disk would defeat the point of the rebuild, so the files go too.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val showIds = RETIRED_SHOW_IDS.joinToString(",") { "'$it'" }
                val stationIds = RETIRED_STATION_IDS.joinToString(",") { "'$it'" }
                // Capture localPath first: deleting the row loses the path,
                // and stale files would be unreachable garbage.
                val paths = mutableListOf<String>()
                db.query("SELECT localPath FROM recordings WHERE localPath IS NOT NULL")
                    .use { c -> while (c.moveToNext()) paths += c.getString(0) }

                db.execSQL("DELETE FROM recordings WHERE showId IN ($showIds)")
                db.execSQL("DELETE FROM shows WHERE id IN ($showIds)")
                db.execSQL("DELETE FROM stations WHERE id IN ($stationIds)")

                // Delete the retired stations' recording directories wholesale.
                paths.forEach { p ->
                    runCatching { java.io.File(p).parentFile?.deleteRecursively() }
                }
            }
        }

        /** Visible for tests: in-memory DB via Room.inMemoryDatabaseBuilder. */
        fun seedData(): Pair<List<Station>, List<Show>> = Pair(SEED_STATIONS, SEED_SHOWS)

        // --- Seed catalogue: public-domain sources only ---
        // The F-Droid build ships NO commercial broadcaster content. Every
        // show below is backed by an item on archive.org (or Project
        // Gutenberg) whose licence permits redistribution; see
        // `data/seed/PublicDomainSeeds` for per-item provenance.
        private val SEED_STATIONS = listOf(
            Station("archive-radio", "Archive Radio", "INTL", StationType.MUSIC, listOf("archive"), logoUrl = null, subscribed = true),
            Station("gutenberg", "Gutenberg Audio", "INTL", StationType.MUSIC, listOf("gutenberg"), logoUrl = null, subscribed = true),
        )

        private val SEED_SHOWS = listOf(
            Show("golden-age", "archive-radio", "Golden Age of Radio", "Anthology", 45, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "ia:USA-Golden-Age-of-Radio", subscribed = true),
            Show("old-time-radio", "archive-radio", "Old Time Radio", "Anthology", 30, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "ia:old-time-radio-collection", subscribed = true),
            Show("weird-circle", "archive-radio", "Weird Circle", "Anthology", 30, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "ia:Weird_Circle_otr", subscribed = true),
            Show("librivox-misc", "archive-radio", "LibriVox Recordings", "Anthology", 30, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "ia:librivox", subscribed = true),
            Show("gutenberg-audio", "gutenberg", "Project Gutenberg Audio", "Anthology", 30, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "gutenberg", subscribed = true),
        )

        /** Station/show rows from pre-public-domain builds. Purged in v3. */
        private val RETIRED_SHOW_IDS = listOf(
            "capital-breakfast", "r1-breakfast", "absolute-breakfast",
            "heart-breakfast", "classicfm-breakfast", "smooth-kate",
            "r2-music", "r2-pick-of-pops",
        )

        private val RETIRED_STATION_IDS = listOf(
            "capital", "bbc-r1", "absolute", "heart", "classicfm", "smooth", "bbc-r2",
        )


        /**
         * Feed backfills. The commercial-broadcaster entries that used to live
         * here were removed for the public-domain build — the retired shows
         * themselves are purged by MIGRATION_2_3, so there is nothing left to
         * backfill. Kept as an empty hook for future PD feed discovery.
         */
        fun feedBackfills(): List<FeedBackfill> = emptyList()

        data class FeedBackfill(
            val showId: String,
            val name: String,
            val feedUrl: String,
            val programmeKey: String?,
        )

        /** Station artwork for existing installs (same URLs as seed). */
        fun logoBackfills(): List<Pair<String, String>> =
            SEED_STATIONS.mapNotNull { s -> s.logoUrl?.let { s.id to it } }

        private class SeedCallback(private val logger: AppLogger) : Callback() {
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Room guarantees onCreate runs once per fresh install.
                // Insert via direct SQL here is racy with DAO init, so defer:
                scope.launch {
                    try {
                        instance?.let { database ->
                            val (stations, shows) = seedData()
                            database.stationDao().upsertAll(stations.map { it.toEntity() })
                            database.showDao().upsertAll(shows.map { it.toEntity() })
                            logger.i("RadioCacheDB", "Seeded ${stations.size} stations, ${shows.size} shows")
                        }
                    } catch (t: Throwable) {
                        logger.e("RadioCacheDB", "Seed failed", t)
                    }
                }
            }
        }
    }
}

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
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .addCallback(SeedCallback(logger))
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shows ADD COLUMN lastSyncEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shows ADD COLUMN lastSyncResult TEXT")
            }
        }

        /** Visible for tests: in-memory DB via Room.inMemoryDatabaseBuilder. */
        fun seedData(): Pair<List<Station>, List<Show>> = Pair(SEED_STATIONS, SEED_SHOWS)

        // --- Seed catalogue: the five music-radio test sources ---
        private val SEED_STATIONS = listOf(
            Station("capital", "Capital London", "UK", StationType.MUSIC, listOf("global"), logoUrl = "https://herald.musicradio.com/media/0d3d891d-32f2-4c53-95ee-8d1e35bdd126.png", subscribed = true, liveStreamUrl = "https://www.globalplayer.com/live/capital/london/", providerStationKey = "capital/london"),
            Station("bbc-r1", "BBC Radio 1", "UK", StationType.MUSIC, listOf("bbc"), logoUrl = "https://ichef.bbci.co.uk/images/ic/512x512/p0lz2vhw.jpg", subscribed = true),
            Station("absolute", "Absolute Radio", "UK", StationType.MUSIC, listOf("absolute"), logoUrl = "https://listenapi.s3.amazonaws.com/img/BrandLogoImageUrl/1.png", liveStreamUrl = "https://www.hellorayo.co.uk/absolute-radio/"),
            Station("heart", "Heart London", "UK", StationType.MUSIC, listOf("global"), logoUrl = "https://herald.musicradio.com/media/49b9e8cb-15bf-4bf2-8c28-a4850cc6b0f3.png", liveStreamUrl = "https://www.globalplayer.com/live/heart/london/", providerStationKey = "heart/london"),
            Station("classicfm", "Classic FM", "UK", StationType.MUSIC, listOf("global"), logoUrl = "https://herald.musicradio.com/media/6a3ef437-0652-463d-9e9d-980d453c9168.png", liveStreamUrl = "https://www.globalplayer.com/live/classicfm/uk/", providerStationKey = "classicfm/uk"),
            Station("smooth", "Smooth", "UK", StationType.MUSIC, listOf("global"), logoUrl = "https://herald.musicradio.com/media/61a4b6da-0e8d-4b2f-b6e4-ad7e150a9ab0.png", liveStreamUrl = "https://www.globalplayer.com/live/smooth/uk/", providerStationKey = "smooth/uk"),
        )

        private val SEED_SHOWS = listOf(
            Show("capital-breakfast", "capital", "Capital Breakfast", "Mon–Fri 06:00–10:00", 240, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "46w7Auo", subscribed = true),
            Show("r1-breakfast", "bbc-r1", "Radio 1 Breakfast (All Day podcast)", "Mon–Fri 07:00–10:30", 210, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "b0080x5m", feedUrl = "https://podcasts.files.bbci.co.uk/p02nrw8h.rss", subscribed = true),
            Show("absolute-breakfast", "absolute", "Hometime with Bush and Richie", "Mon–Fri 16:00–19:00", 180, feedUrl = "https://rss.podplaystudio.com/1305.xml"),
            Show("heart-breakfast", "heart", "Heart Breakfast", "Mon–Fri 06:30–10:00", 210),
            Show("classicfm-breakfast", "classicfm", "Classic FM Breakfast", "Mon–Fri 06:30–09:00", 150, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "46wcDcx"),
            Show("smooth-kate", "smooth", "Kate Garraway", "Mon–Fri 10:00–13:00", 180, DownloadStrategy.CATCH_UP_PREFERRED, providerProgrammeKey = "b8F8VgS"),
        )

        /**
         * Verified programme identities / official podcast feeds discovered
         * after v1 seed shipped. Applied to existing installs where feedUrl
         * is still blank (see backfillFeed). Empty feed = identity-only
         * (no public audio; awaits live capture).
         */
        fun feedBackfills(): List<FeedBackfill> = listOf(
            FeedBackfill("r1-breakfast", "Radio 1 Breakfast (All Day podcast)", "https://podcasts.files.bbci.co.uk/p02nrw8h.rss", "b0080x5m"),
            FeedBackfill("absolute-breakfast", "Hometime with Bush and Richie", "https://rss.podplaystudio.com/1305.xml", null),
            FeedBackfill("r2-music", "Pick of the Pops", "", "b006wqx7"),
        )

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

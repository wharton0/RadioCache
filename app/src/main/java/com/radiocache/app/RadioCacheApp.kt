package com.radiocache.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.radiocache.app.di.AppContainer
import com.radiocache.app.data.local.Mappers.toEntity
import com.radiocache.app.data.local.RadioCacheDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Manual [AppContainer] DI (no Hilt): explicit, testable, maps 1:1 to a
 * future Hilt graph. App start ensures the periodic Wi-Fi sync exists.
 */
class RadioCacheApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ensureSyncChannel()
        appScope.launch {
            try {
                // Seed backfill: new stations/shows reach existing installs
                // (IGNORE keeps every present row untouched).
                val (stations, shows) = RadioCacheDatabase.seedData()
                runCatching {
                    container.database.stationDao().insertIgnore(stations.map { it.toEntity() })
                    container.database.showDao().insertIgnore(shows.map { it.toEntity() })
                }
                RadioCacheDatabase.logoBackfills().forEach { (stationId, logo) ->
                    runCatching {
                        container.database.stationDao().backfillLogo(stationId, logo)
                    }
                }
                // Backfill verified programme identities / official podcast
                // feeds found after first install.
                RadioCacheDatabase.feedBackfills().forEach { backfill ->
                    runCatching {
                        container.database.showDao().backfillFeed(
                            backfill.showId, backfill.name,
                            backfill.feedUrl, backfill.programmeKey,
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "feed backfill failed", t)
            }
            try {
                container.syncScheduler.schedulePeriodic()
            } catch (t: Throwable) {
                Log.e(TAG, "schedulePeriodic failed", t)
            }
        }
        Log.i(TAG, "RadioCache initialized (stage4)")
    }

    private fun ensureSyncChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(SYNC_CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(SYNC_CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val TAG = "RadioCacheApp"
        const val SYNC_CHANNEL = "radiocache_sync"
    }
}

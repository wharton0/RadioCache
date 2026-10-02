package com.radiocache.app.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.radiocache.app.data.settings.SettingsRepository
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed scheduler.
 *
 * - Periodic sync every 6h, Wi-Fi-only by default ([SettingsRepository.wifiOnly]
 *   relaxes the constraint to any connection).
 * - Manual [syncNow] for the Downloads screen button.
 * - Unique work names keep scheduling idempotent across app restarts.
 */
class SyncSchedulerImpl(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val logger: AppLogger = AndroidLogger(),
) : SyncScheduler {

    override suspend fun schedulePeriodic() {
        if (!runCatching { settings.autoSync.first() }.getOrDefault(true)) {
            logger.i(TAG, "auto-sync disabled, skipping schedule")
            return
        }
        // UPDATE (not KEEP): periodic must not carry TAG_SYNC — a pending
        // periodic work is almost always ENQUEUED, and SyncStateMonitor
        // counts that tag as "sync running" (stuck-syncing bug on <=0.7.6,
        // where old tagged periodic specs were replaced here).
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, buildPeriodic()
        )
        logger.i(TAG, "periodic sync ensured")
    }

    override suspend fun reschedulePeriodic() {
        if (!runCatching { settings.autoSync.first() }.getOrDefault(true)) {
            cancelPeriodic()
            logger.i(TAG, "auto-sync disabled, periodic cancelled")
            return
        }
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, buildPeriodic()
        )
        logger.i(TAG, "periodic sync rescheduled")
    }

    override fun cancelPeriodic() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_PERIODIC)
        logger.i(TAG, "periodic sync cancelled")
    }

    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(TAG_SYNC)
            .build()
        // No constraints on manual sync: the user explicitly asked.
        // KEEP (not APPEND): rapid re-taps while a sync runs must not pile
        // up duplicate workers that download the same files concurrently.
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.KEEP, request
        )
        logger.i(TAG, "immediate sync enqueued")
    }

    override fun syncShow(showId: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_SHOW_ID to showId))
            .addTag(TAG_SYNC)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "$WORK_NOW-$showId", ExistingWorkPolicy.REPLACE, request
        )
        logger.i(TAG, "immediate single-show sync enqueued: $showId")
    }

    override fun cancelAll() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_PERIODIC)
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NOW)
        logger.i(TAG, "sync cancelled")
    }

    private suspend fun constraints(): Constraints {
        val wifiOnly = runCatching { settings.wifiOnly.first() }.getOrDefault(true)
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    private suspend fun buildPeriodic(): androidx.work.PeriodicWorkRequest {
        val days = runCatching { settings.syncIntervalDays.first() }
            .getOrDefault(SettingsRepository.DEFAULT_INTERVAL_DAYS).coerceIn(1, 30)
        val hour = runCatching { settings.syncHour.first() }
            .getOrDefault(SettingsRepository.DEFAULT_SYNC_HOUR)
        val minute = runCatching { settings.syncMinute.first() }
            .getOrDefault(SettingsRepository.DEFAULT_SYNC_MINUTE)
        val initialDelay = SyncTime.delayToNext(
            hour.coerceIn(0, 23), minute.coerceIn(0, 59),
            java.time.ZonedDateTime.now(),
        )
        return PeriodicWorkRequestBuilder<SyncWorker>(days * 24L, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setInitialDelay(initialDelay)
            // Own tag: TAG_SYNC is reserved for one-time syncs so the
            // running-state monitor never sees a pending periodic as "running".
            .addTag(TAG_SYNC_PERIODIC)
            .build()
    }

    companion object {
        private const val TAG = "SyncScheduler"
        const val TAG_SYNC = "radiocache-sync"
        const val TAG_SYNC_PERIODIC = "radiocache-sync-periodic"
        const val WORK_PERIODIC = "radiocache-periodic-sync"
        const val WORK_NOW = "radiocache-immediate-sync"
    }
}

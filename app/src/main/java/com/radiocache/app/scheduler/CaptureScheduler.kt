package com.radiocache.app.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Schedules timed live captures. Called from [SyncWorker] when a show has
 * no free catch-up but a resolvable live stream + known airing time.
 *
 * Each capture is a unique one-time worker firing at broadcast start;
 * re-syncs replace stale schedules (REPLACE), never pile up.
 */
object CaptureScheduler {

    private const val TAG = "CaptureScheduler"

    /**
     * Hard ceiling per capture. A mis-configured duration (or a stream that
     * never ends) must not fill the device: 4h covers any real phone show.
     */
    const val MAX_CAPTURE_MS = 4L * 60 * 60 * 1000

    fun schedule(
        context: Context,
        showId: String,
        stationId: String,
        title: String,
        streamUrl: String,
        scheduledStartEpochMs: Long,
        durationMs: Long,
        broadcastDateEpochMs: Long,
        logger: AppLogger = AndroidLogger(),
    ) {
        val delayMs = scheduledStartEpochMs - System.currentTimeMillis()
        if (delayMs < -5 * 60 * 1000L) {
            logger.i(TAG, "airing already passed for $showId, not scheduling")
            return
        }
        if (durationMs <= 0) {
            logger.w(TAG, "no duration for $showId, not scheduling")
            return
        }
        val capped = durationMs.coerceAtMost(MAX_CAPTURE_MS)
        if (capped != durationMs) {
            logger.w(TAG, "duration capped to ${capped / 60000} min for $showId")
        }
        val request = OneTimeWorkRequestBuilder<CaptureWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    CaptureWorker.KEY_SHOW_ID to showId,
                    CaptureWorker.KEY_STATION_ID to stationId,
                    CaptureWorker.KEY_TITLE to title,
                    CaptureWorker.KEY_STREAM_URL to streamUrl,
                    CaptureWorker.KEY_START_MS to scheduledStartEpochMs,
                    CaptureWorker.KEY_DURATION_MS to capped,
                    CaptureWorker.KEY_BROADCAST_DATE to broadcastDateEpochMs,
                )
            )
            // Own tag, NOT TAG_SYNC: a capture stays ENQUEUED until its
            // airing hours later, which must not read as "sync running"
            // on the Home button.
            .addTag(TAG_CAPTURE)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "capture-$showId-$scheduledStartEpochMs",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        logger.i(TAG, "capture scheduled: $showId in ${delayMs / 60000} min for ${capped / 60000} min")
    }

    const val TAG_CAPTURE = "radiocache-capture"
}
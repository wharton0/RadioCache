package com.radiocache.app.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.radiocache.app.RadioCacheApp
import com.radiocache.app.data.local.Mappers.toDomain
import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.SourceType
import com.radiocache.app.download.RecordingFiles
import com.radiocache.app.util.AndroidLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records one scheduled broadcast from a live stream, then stores it as a
 * normal offline recording. Enqueued by [CaptureScheduler] at sync time.
 */
class CaptureWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val logger = AndroidLogger()

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (t: Throwable) {
            logger.w(TAG, "foreground unavailable, continuing", t)
        }
        val container = (applicationContext as RadioCacheApp).container
        val showId = inputData.getString(KEY_SHOW_ID) ?: return Result.failure()
        val stationId = inputData.getString(KEY_STATION_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: showId
        val streamUrl = inputData.getString(KEY_STREAM_URL) ?: return Result.failure()
        val startMs = inputData.getLong(KEY_START_MS, 0L)
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
            .coerceIn(1L, CaptureScheduler.MAX_CAPTURE_MS)
        val broadcastDateMs = inputData.getLong(KEY_BROADCAST_DATE, 0L)
        val recId = "live:$showId:$startMs"

        // Skip if catch-up filled the gap meanwhile.
        val existing = container.database.recordingDao().getById(recId)
        if (existing?.status == DownloadStatus.COMPLETED.name) {
            logger.i(TAG, "already captured: $recId")
            return Result.success()
        }

        // Late start: a worker that wakes minutes after the airing began
        // would otherwise record from the middle. Record only what is left,
        // and label it as partial.
        val nowMs = System.currentTimeMillis()
        val lateByMs = (nowMs - startMs).coerceAtLeast(0L)
        val remainingMs = durationMs - lateByMs
        if (remainingMs <= MIN_CAPTURE_MS) {
            logger.i(TAG, "airing for $showId already over by ${lateByMs / 60000} min, skipping")
            clearPending(container, showId)
            return Result.success()
        }
        val partial = lateByMs >= 60_000L
        if (partial) logger.i(TAG, "starting late for $showId: missing ${lateByMs / 60000} min")

        val ext = if (".m3u8" in streamUrl.substringBefore("?").lowercase()) "ts" else null
        val dest = RecordingFiles.destination(
            applicationContext.filesDir, stationId, showId,
            broadcastDateMs, "live-$startMs", streamUrl, extOverride = ext,
        )
        val displayTitle = if (partial) "$title (live, partial)" else "$title (live)"
        container.roomRepository.upsertRecording(
            Recording(recId, showId, stationId, displayTitle, null, broadcastDateMs, startMs, startMs + durationMs, System.currentTimeMillis(), SourceType.STREAM_CAPTURE, streamUrl, streamUrl, dest.absolutePath, remainingMs, DownloadStatus.DOWNLOADING, 0)
        )

        // Progress: bytes are unknown for live streams, so report against the
        // elapsed media time (5% steps) — the UI stays live during a long capture.
        // The engine's callback is not suspending, so DB writes are launched
        // on a fire-and-forget scope (at most ~20 per capture).
        val progressScope = CoroutineScope(Dispatchers.IO)
        var lastPercent = -1
        val startedAt = System.currentTimeMillis()
        val result = try {
            container.captureEngine.capture(streamUrl, dest, remainingMs) {
                val pct = (((System.currentTimeMillis() - startedAt) * 100) / remainingMs)
                    .toInt().coerceIn(0, 99)
                if (pct - lastPercent >= 5) {
                    lastPercent = pct
                    val dao = container.database.recordingDao()
                    progressScope.launch {
                        runCatching { dao.updateStatus(recId, DownloadStatus.DOWNLOADING.name, pct) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // worker stopped — leave the row DOWNLOADING for a retry
        } finally {
            progressScope.cancel()
        }

        return if (result.isSuccess) {
            val file = result.getOrThrow()
            val actualMs = readDurationMs(file.absolutePath)
            if (actualMs <= 0L) {
                // Segments downloaded but the container is unreadable — better
                // an honest FAILED than a "cached" file that won't play.
                logger.w(TAG, "captured file has no readable duration: ${file.name}")
                markFailed(container, showId, stationId, recId, displayTitle, broadcastDateMs, startMs, durationMs, streamUrl, "captured file unreadable")
                return Result.failure()
            }
            container.roomRepository.upsertRecording(
                Recording(recId, showId, stationId, displayTitle, null, broadcastDateMs, startMs, startMs + durationMs, System.currentTimeMillis(), SourceType.STREAM_CAPTURE, streamUrl, streamUrl, file.absolutePath, actualMs, DownloadStatus.COMPLETED, 100)
            )
            clearPending(container, showId)
            logger.i(TAG, "captured $recId (${file.length()} bytes, ${actualMs / 60000} min)")
            Result.success()
        } else {
            markFailed(
                container, showId, stationId, recId, displayTitle, broadcastDateMs, startMs, durationMs,
                streamUrl, "live capture failed: ${result.exceptionOrNull()?.message?.take(60)}",
            )
            logger.e(TAG, "capture failed: $recId", result.exceptionOrNull())
            Result.failure()
        }
    }

    /** Drops the sync-created PENDING placeholder — success or failure. */
    private suspend fun clearPending(container: com.radiocache.app.di.AppContainer, showId: String) {
        runCatching { container.database.recordingDao().deletePendingPlaceholders(showId) }
    }

    private suspend fun markFailed(
        container: com.radiocache.app.di.AppContainer,
        showId: String,
        stationId: String,
        recId: String,
        displayTitle: String,
        broadcastDateMs: Long,
        startMs: Long,
        durationMs: Long,
        streamUrl: String,
        reason: String,
    ) {
        container.roomRepository.upsertRecording(
            Recording(recId, showId, stationId, displayTitle, reason, broadcastDateMs, startMs, startMs + durationMs, System.currentTimeMillis(), SourceType.STREAM_CAPTURE, streamUrl, streamUrl, null, 0L, DownloadStatus.FAILED, 0)
        )
        clearPending(container, showId)
    }

    private fun readDurationMs(path: String): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val title = inputData.getString(KEY_TITLE) ?: "Radio programme"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Recording $title")
            .setContentText("Capturing live broadcast for offline listening")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val TAG = "CaptureWorker"
        private const val CHANNEL_ID = "radiocache_sync"
        private const val NOTIF_ID = 1002

        /** Less than this left to record isn't worth starting a worker for. */
        private const val MIN_CAPTURE_MS = 60_000L

        const val KEY_SHOW_ID = "show_id"
        const val KEY_STATION_ID = "station_id"
        const val KEY_TITLE = "title"
        const val KEY_STREAM_URL = "stream_url"
        const val KEY_START_MS = "start_ms"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_BROADCAST_DATE = "broadcast_date"
    }
}
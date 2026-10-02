package com.radiocache.app.scheduler

import android.app.Notification
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
import androidx.work.workDataOf
import com.radiocache.app.RadioCacheApp
import com.radiocache.app.data.local.Mappers.toDomain
import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.SourceType
import com.radiocache.app.download.RecordingFiles
import com.radiocache.app.engine.download.DownloadPlanner
import com.radiocache.app.util.AndroidLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The Wi-Fi sync worker. Per subscribed show:
 *
 * 1. Pick the first [CatchUpProvider] that supports it
 *    (Global → BBC → Absolute → RSS).
 * 2. Resolve recent broadcast programmes.
 * 3. [DownloadPlanner.decide]: catch-up download, else a PENDING
 *    placeholder for the Stage-6 live-capture engine.
 * 4. Report progress into Room (the Downloads UI reads it).
 * 5. After all shows: retention cleanup per keep-days setting.
 *
 * Per-show failures are logged and skipped — one bad station never aborts
 * the whole sync. Download-level retries live in AudioDownloader.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val logger = AndroidLogger()

    override suspend fun doWork(): Result {
        runForegroundSafely()
        val container = (applicationContext as RadioCacheApp).container
        return try {
            val keepDays = container.settings.keepDays.first()
            val allShows = container.database.showDao().getSubscribedOnce()
            val onlyShowId = inputData.getString(KEY_SHOW_ID)
            val shows = if (onlyShowId == null) allShows else allShows.filter { it.id == onlyShowId }
            logger.i(TAG, "sync start: ${shows.size} subscribed shows (keep=$keepDays days)")
            if (shows.isEmpty()) {
                container.roomRepository.cleanupOldRecordings(keepDays)
                return Result.success()
            }
            var ok = 0
            var failed = 0
            shows.forEachIndexed { index, showEntity ->
                setProgress(workDataOf(KEY_PROGRESS to ((index * 100) / shows.size)))
                try {
                    processShow(showEntity.id)
                    ok++
                } catch (e: CancellationException) {
                    throw e // worker stopped: propagate, don't log as failure
                } catch (t: Throwable) {
                    failed++
                    logger.e(TAG, "show sync failed: ${showEntity.id}", t)
                }
            }
            val removed = container.roomRepository.cleanupOldRecordings(keepDays)
            logger.i(TAG, "sync done: ok=$ok failed=$failed cleaned=$removed")
            Result.success(
                workDataOf(KEY_DONE_OK to ok, KEY_DONE_FAILED to failed, KEY_CLEANED to removed)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.e(TAG, "sync worker crashed", t)
            Result.failure()
        }
    }

    private suspend fun processShow(showId: String) {
        val container = (applicationContext as RadioCacheApp).container
        val show = container.database.showDao().getById(showId)?.toDomain() ?: return
        val outcome: String = try {
            resolveAndSync(show)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            "Sync error: ${t.message?.take(80)}"
        }
        runCatching {
            container.database.showDao().updateSyncInfo(showId, System.currentTimeMillis(), outcome)
        }
        logger.i(TAG, "show $showId: $outcome")
    }

    /** Returns a short human-readable outcome for the shows table / UI. */
    private suspend fun resolveAndSync(show: com.radiocache.app.data.model.Show): String {
        val container = (applicationContext as RadioCacheApp).container
        // Explicit official feed always wins: a show with feedUrl goes to
        // the RSS provider first, even if a station provider also claims it
        // (e.g. BBC shows fall back from metadata-only to podcast audio).
        val ordered = if (!show.feedUrl.isNullOrBlank()) {
            container.catchUpProviders.sortedBy { if (it.providerId == "rss") 0 else 1 }
        } else {
            container.catchUpProviders
        }
        val provider = ordered.firstOrNull { it.supports(show) }
            ?: return "No provider for this show"
        val programmes = provider.fetchProgrammes(show, 7).getOrElse {
            return "Resolve failed (${provider.providerId}): ${it.message?.take(80)}"
        }
        if (programmes.isEmpty()) return "No episodes listed (${provider.providerId})"
        return when (val decision = DownloadPlanner.decide(show, programmes)) {
            is DownloadPlanner.Decision.DownloadCatchUp ->
                downloadCatchUp(show.id, show.stationId, decision.programme)
            is DownloadPlanner.Decision.CaptureLive -> {
                upsertPendingPlaceholder(show.id, show.stationId, decision.programme.programmeId, decision.programme.title, "catch-up missing — scheduled for live capture")
                scheduleCaptureIfPossible(show, decision.programme)
            }
            is DownloadPlanner.Decision.ScheduleCapture -> {
                upsertPendingPlaceholder(show.id, show.stationId, "next", show.name, "no episode metadata — capture at next airing")
                "No episode metadata — will capture at next airing"
            }
            is DownloadPlanner.Decision.Skip -> decision.reason
        }
    }

    private suspend fun downloadCatchUp(
        showId: String,
        stationId: String,
        programme: com.radiocache.app.data.model.BroadcastProgramme,
    ): String {
        val container = (applicationContext as RadioCacheApp).container
        val recId = "catchup:${programme.programmeId}"
        val existing = container.database.recordingDao().getById(recId)
        if (existing?.status == DownloadStatus.COMPLETED.name) {
            logger.i(TAG, "already cached: $recId")
            return "Up to date — ${programme.title.take(40)}"
        }
        val audioUrl = programme.resolvedAudioUrl ?: return "No audio URL resolved"
        val dest = RecordingFiles.destination(
            applicationContext.filesDir, stationId, showId,
            programme.broadcastDateEpochMs, programme.programmeId, audioUrl,
        )
        // Mark DOWNLOADING first so the UI shows progress immediately.
        container.roomRepository.upsertRecording(
            Recording(recId, showId, stationId, programme.title, programme.description, programme.broadcastDateEpochMs, programme.scheduledStartEpochMs, programme.scheduledEndEpochMs, System.currentTimeMillis(), SourceType.CATCH_UP, programme.sourceUrl, audioUrl, dest.absolutePath, programme.durationMs ?: 0L, DownloadStatus.DOWNLOADING, 0)
        )
        var lastPercent = -1
        val result = container.downloader.download(audioUrl, dest) { written, total ->
            // Throttle Room writes to 5% steps so the Downloads UI stays live
            // without hammering the DB on every 32KB chunk.
            if (total > 0) {
                val pct = ((written * 100) / total).toInt()
                if (pct - lastPercent >= 5) {
                    lastPercent = pct
                    runCatching {
                        container.database.recordingDao().updateStatus(recId, DownloadStatus.DOWNLOADING.name, pct)
                    }
                }
            }
        }
        result.fold(
            onSuccess = { file ->
                val durationMs = readDurationMs(file.absolutePath)
                container.roomRepository.upsertRecording(
                    Recording(recId, showId, stationId, programme.title, programme.description, programme.broadcastDateEpochMs, programme.scheduledStartEpochMs, programme.scheduledEndEpochMs, System.currentTimeMillis(), SourceType.CATCH_UP, programme.sourceUrl, audioUrl, file.absolutePath, durationMs, DownloadStatus.COMPLETED, 100)
                )
                runCatching { container.database.recordingDao().deletePendingPlaceholders(showId) }
                logger.i(TAG, "cached $recId (${file.length()} bytes)")
            },
            onFailure = { t ->
                container.roomRepository.upsertRecording(
                    Recording(recId, showId, stationId, programme.title, programme.description, programme.broadcastDateEpochMs, programme.scheduledStartEpochMs, programme.scheduledEndEpochMs, System.currentTimeMillis(), SourceType.CATCH_UP, programme.sourceUrl, audioUrl, null, 0L, DownloadStatus.FAILED, 0)
                )
                logger.e(TAG, "download failed: $recId", t)
            },
        )
        return if (result.isSuccess) "Downloaded ${programme.title.take(40)}"
        else "Download failed: ${result.exceptionOrNull()?.message?.take(60)}"
    }

    /**
     * Stage 6 hook: when catch-up has no free audio, schedule a timed live
     * recording if the station publishes an openly reachable stream and the
     * airing is upcoming. Never breaks the sync — all failures degrade to
     * the PENDING placeholder message.
     */
    private suspend fun scheduleCaptureIfPossible(
        show: com.radiocache.app.data.model.Show,
        programme: com.radiocache.app.data.model.BroadcastProgramme,
    ): String {
        val container = (applicationContext as RadioCacheApp).container
        return try {
            val station = container.database.stationDao().getById(show.stationId)?.toDomain()
                ?: return "Catch-up has no free audio — awaiting live capture"
            var liveUrl: String? = null
            for (provider in container.liveStreamProviders) {
                if (!provider.supports(station)) continue
                val info = runCatching { provider.resolveLiveStream(station) }.getOrNull()?.getOrNull()
                val url = info?.streamUrl
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    liveUrl = url
                    break
                }
            }
            if (liveUrl == null) return "Catch-up has no free audio — no open live stream found"
            CaptureScheduler.schedule(
                applicationContext, show.id, show.stationId, programme.title, liveUrl,
                programme.scheduledStartEpochMs, show.durationMinutes * 60_000L,
                programme.broadcastDateEpochMs,
            )
            val whenText = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(programme.scheduledStartEpochMs))
            "Catch-up unavailable — live recording scheduled ($whenText)"
        } catch (t: Throwable) {
            logger.w(TAG, "capture schedule failed for ${show.id}", t)
            "Catch-up has no free audio — awaiting live capture"
        }
    }

    private suspend fun upsertPendingPlaceholder(
        showId: String,
        stationId: String,
        key: String,
        title: String,
        reason: String,
    ) {
        val container = (applicationContext as RadioCacheApp).container
        container.roomRepository.upsertRecording(
            Recording(
                id = "capture:$showId:$key", showId = showId, stationId = stationId,
                title = title, description = "$reason (live capture coming soon)",
                recordedAtEpochMs = System.currentTimeMillis(),
                sourceType = SourceType.STREAM_CAPTURE, localPath = null,
                status = DownloadStatus.PENDING, downloadProgress = 0,
            )
        )
    }

    private fun readDurationMs(path: String): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            logger.w(TAG, "duration read failed: $path", t)
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private suspend fun runForegroundSafely() {
        try {
            setForeground(getForegroundInfo())
        } catch (t: Throwable) {
            // No notification permission on Android 13+: continue backgrounded.
            logger.w(TAG, "foreground unavailable, continuing", t)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("RadioCache syncing")
            .setContentText("Checking subscribed shows over Wi-Fi")
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
        private const val TAG = "SyncWorker"
        private const val CHANNEL_ID = "radiocache_sync"
        private const val NOTIF_ID = 1001
        const val KEY_SHOW_ID = "show_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DONE_OK = "done_ok"
        const val KEY_DONE_FAILED = "done_failed"
        const val KEY_CLEANED = "cleaned"
    }
}

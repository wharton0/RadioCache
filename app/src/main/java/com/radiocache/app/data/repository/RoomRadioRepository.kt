package com.radiocache.app.data.repository

import com.radiocache.app.data.local.Mappers.toDomain
import com.radiocache.app.data.local.Mappers.toEntity
import com.radiocache.app.data.local.RadioCacheDatabase
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Room-backed [RadioRepository]. ViewModels are untouched — they only see
 * the interface. All DB errors are caught and logged; reads fall back to
 * empty lists rather than crashing the UI.
 */
class RoomRadioRepository(
    private val db: RadioCacheDatabase,
    private val logger: AppLogger = AndroidLogger(),
    /** Root of all recordings — lets cleanup find orphaned `.part` files. */
    private val filesRoot: File? = null,
) : RadioRepository {

    override fun observeStations(): Flow<List<Station>> =
        db.stationDao().observeAll().map { rows ->
            runCatching { rows.map { it.toDomain() } }
                .onFailure { logger.e(TAG, "map stations failed", it) }
                .getOrDefault(emptyList())
        }

    override fun observeShows(stationId: String?): Flow<List<Show>> {
        val flow = if (stationId == null) db.showDao().observeAll()
        else db.showDao().observeByStation(stationId)
        return flow.map { rows ->
            runCatching { rows.map { it.toDomain() } }
                .onFailure { logger.e(TAG, "map shows failed", it) }
                .getOrDefault(emptyList())
        }
    }

    override fun observeRecordings(): Flow<List<Recording>> =
        db.recordingDao().observeAll().map { rows ->
            runCatching { rows.map { it.toDomain() } }
                .onFailure { logger.e(TAG, "map recordings failed", it) }
                .getOrDefault(emptyList())
        }

    override suspend fun setStationSubscribed(stationId: String, subscribed: Boolean) {
        runCatching { db.stationDao().setSubscribed(stationId, subscribed) }
            .onFailure { logger.e(TAG, "setStationSubscribed failed", it) }
    }

    override suspend fun setShowSubscribed(showId: String, subscribed: Boolean) {
        runCatching { db.showDao().setSubscribed(showId, subscribed) }
            .onFailure { logger.e(TAG, "setShowSubscribed failed", it) }
    }

    override suspend fun upsertRecording(recording: Recording) {
        runCatching { db.recordingDao().upsert(recording.toEntity()) }
            .onFailure { logger.e(TAG, "upsertRecording failed", it) }
    }

    override suspend fun updatePlaybackPosition(recordingId: String, positionMs: Long) {
        runCatching { db.recordingDao().updatePosition(recordingId, positionMs) }
            .onFailure { logger.e(TAG, "updatePlaybackPosition failed", it) }
    }

    /**
     * Retention cleanup, run automatically at the end of every sync and on
     * manual request. Single rule: drop broadcasts older than [keepDays]
     * days (with their audio files). The per-show newest-N trim exists as
     * [RetentionPolicy.idsBeyondLatest] but is currently unenforced —
     * keep-days is the only active rule (no duplicate settings in UI).
     */
    suspend fun cleanupOldRecordings(
        keepDays: Int,
        keepCount: Int = Int.MAX_VALUE,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Int {
        var removed = 0
        try {
            // Rule 1: age cutoff.
            val cutoff = RetentionPolicy.cutoffEpochMs(nowEpochMs, keepDays)
            val all = db.recordingDao().observeAll().first()
            val expired = all.filter { it.broadcastDateEpochMs > 0 && it.broadcastDateEpochMs <= cutoff }
            removed += db.recordingDao().deleteOlderThan(cutoff)
            deleteFiles(expired.mapNotNull { it.localPath })

            // Rule 2: newest-N per show (never trim an in-flight download).
            val showIds = all.map { it.showId }.distinct()
            for (showId in showIds) {
                val rows = db.recordingDao().getByShow(showId)
                val downloadingIds = rows
                    .filter { it.status == DownloadStatus.DOWNLOADING.name }
                    .map { it.id }.toSet()
                val trimmable = rows.filter { it.id !in downloadingIds }
                val extras = RetentionPolicy.idsBeyondLatest(trimmable.map { it.id }, keepCount)
                if (extras.isNotEmpty()) {
                    val byId = rows.associateBy { it.id }
                    removed += db.recordingDao().deleteByIds(extras)
                    deleteFiles(extras.mapNotNull { byId[it]?.localPath })
                }
            }
            logger.i(TAG, "Retention cleanup: removed $removed (keep=$keepDays days, latest=$keepCount/show)")
            // Orphan partials (failed recordings keep localPath null, so the
            // paths above can't reach them): drop anything older than the cutoff.
            removed += deleteStalePartials(cutoff)
        } catch (t: Throwable) {
            logger.e(TAG, "cleanup failed", t)
        }
        return removed
    }

    private fun deleteFiles(paths: List<String>) {
        paths.forEach { path ->
            runCatching { File(path).takeIf { it.exists() }?.delete() }
                .onFailure { logger.w(TAG, "file delete failed: $path", it) }
            // Abandoned partial from a never-completed download.
            runCatching { File("$path.part").takeIf { it.exists() }?.delete() }
        }
    }

    /** Deletes `.part` files untouched since [cutoffEpochMs] (failed downloads aren't in the paths list). */
    private fun deleteStalePartials(cutoffEpochMs: Long): Int {
        val root = filesRoot ?: return 0
        var removed = 0
        runCatching {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".part") && it.lastModified() <= cutoffEpochMs }
                .forEach { f -> if (f.delete()) removed++ }
        }.onFailure { logger.w(TAG, "partial cleanup failed", it) }
        return removed
    }

    suspend fun recordingCount(): Int =
        runCatching { db.recordingDao().count() }.getOrDefault(0)

    companion object {
        private const val TAG = "RoomRadioRepo"
    }
}

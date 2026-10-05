package com.radiocache.app.data

import com.radiocache.app.data.local.RadioCacheDatabase
import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reconciles the recordings index with what is actually on disk.
 *
 * ## Why this exists
 *
 * The database can claim a recording is COMPLETED while its audio file is
 * gone — storage was reclaimed, the user cleared a folder, a download was
 * interrupted after the row was written, or a cleanup ran partially. When
 * that happens the UI offers an episode that throws
 * `FileNotFoundException` the moment it is played.
 *
 * [com.radiocache.app.playback.RadioPlayerManager.play] already guards the
 * moment of playback. [Reconciler] runs the same reconciliation eagerly at
 * startup so the UI is correct the first time it is drawn, instead of
 * degrading one bad tap at a time.
 *
 * Rows are marked FAILED rather than deleted: the title and metadata are
 * still worth showing, FAILED is what makes the Home screen offer a re-sync,
 * and the next sync re-downloads the file.
 */
object ConsistencyChecker {

    private const val TAG = "Consistency"

    /**
     * A recording is only playable if its file exists AND has bytes.
     * Pure and I/O-light so it is unit-testable without Room.
     */
    fun isHealthy(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            val f = File(path)
            f.isFile && f.length() > 0L
        }.getOrDefault(false)
    }

    /** Performs the database writes for [reconcileCompleted]. */
    class Reconciler(
        private val database: RadioCacheDatabase,
        private val logger: AppLogger = AndroidLogger(),
    ) {

        data class Result(val checked: Int, val repaired: Int)

        /**
         * Marks COMPLETED rows whose audio file is missing or zero-length.
         *
         * @param dryRun when true, reports what it would do without writing.
         */
        suspend fun reconcileCompleted(dryRun: Boolean = false): Result {
            val rows = runCatching { database.recordingDao().observeAll().first() }
                .getOrElse {
                    logger.e(TAG, "reconcile: could not read recordings", it)
                    return Result(0, 0)
                }
            var repaired = 0
            var checked = 0
            for (row in rows) {
                if (row.status != DownloadStatus.COMPLETED.name) continue
                checked++
                if (isHealthy(row.localPath)) continue

                logger.w(TAG, "orphaned COMPLETED row: ${row.id} at ${row.localPath}")
                if (!dryRun) {
                    runCatching {
                        database.recordingDao().updateStatus(row.id, DownloadStatus.FAILED.name, 0)
                    }.onFailure { logger.e(TAG, "reconcile: update failed for ${row.id}", it) }
                }
                repaired++
            }
            if (checked > 0) {
                logger.i(
                    TAG,
                    "reconcile: checked $checked completed, " +
                        "${if (dryRun) "would repair" else "repaired"} $repaired",
                )
            }
            return Result(checked, repaired)
        }

        /** Fire-and-forget; never blocks the caller's thread. */
        fun runInBackground(scope: CoroutineScope) {
            scope.launch(Dispatchers.IO) { runCatching { reconcileCompleted() } }
        }
    }
}
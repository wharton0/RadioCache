package com.radiocache.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations ORDER BY name")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE subscribed = 1 ORDER BY name")
    fun observeSubscribed(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun getById(id: String): StationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stations: List<StationEntity>)

    /** Seed backfill for existing installs: adds missing rows, never touches present ones. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(stations: List<StationEntity>)

    @Query("UPDATE stations SET subscribed = :subscribed WHERE id = :stationId")
    suspend fun setSubscribed(stationId: String, subscribed: Boolean)

    /** Logo backfill for existing installs; never overwrites a present logo. */
    @Query("UPDATE stations SET logoUrl = :logoUrl WHERE id = :stationId AND (logoUrl IS NULL OR logoUrl = '')")
    suspend fun backfillLogo(stationId: String, logoUrl: String)
}

@Dao
interface ShowDao {
    @Query("SELECT * FROM shows ORDER BY name")
    fun observeAll(): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows WHERE stationId = :stationId ORDER BY name")
    fun observeByStation(stationId: String): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows WHERE subscribed = 1")
    fun observeSubscribed(): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows WHERE subscribed = 1")
    suspend fun getSubscribedOnce(): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun getById(id: String): ShowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shows: List<ShowEntity>)

    /** Seed backfill for existing installs: adds missing rows, never touches present ones. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(shows: List<ShowEntity>)

    @Query("UPDATE shows SET subscribed = :subscribed WHERE id = :showId")
    suspend fun setSubscribed(showId: String, subscribed: Boolean)

    /**
     * Backfills official podcast feeds discovered after first install.
     * Only fills blank feedUrls — never overwrites user data. Null key
     * leaves the existing programme key untouched.
     */
    @Query("UPDATE shows SET feedUrl = :feedUrl, name = :name, providerProgrammeKey = COALESCE(:programmeKey, providerProgrammeKey) WHERE id = :showId AND (feedUrl IS NULL OR feedUrl = '')")
    suspend fun backfillFeed(showId: String, name: String, feedUrl: String, programmeKey: String?)

    @Query("UPDATE shows SET lastSyncEpochMs = :epochMs, lastSyncResult = :result WHERE id = :showId")
    suspend fun updateSyncInfo(showId: String, epochMs: Long, result: String)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY recordedAtEpochMs DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = 'COMPLETED' ORDER BY recordedAtEpochMs DESC")
    fun observeCompleted(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recording: RecordingEntity)

    @Query("UPDATE recordings SET lastPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long)

    @Query("UPDATE recordings SET status = :status, downloadProgress = :progress WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, progress: Int)

    /**
     * Retention cleanup: delete recordings whose broadcast day is older than
     * [cutoffEpochMs]. Returns the removed row count for logging.
     */
    @Query("DELETE FROM recordings WHERE broadcastDateEpochMs < :cutoffEpochMs AND broadcastDateEpochMs > 0")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int

    /**
     * Removes stale live-capture placeholders for a show once real catch-up
     * audio lands (e.g. PENDING rows from an earlier unresolved sync).
     */
    @Query("DELETE FROM recordings WHERE showId = :showId AND status = 'PENDING' AND localPath IS NULL")
    suspend fun deletePendingPlaceholders(showId: String): Int

    @Query("SELECT * FROM recordings WHERE showId = :showId ORDER BY recordedAtEpochMs DESC")
    suspend fun getByShow(showId: String): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int
}

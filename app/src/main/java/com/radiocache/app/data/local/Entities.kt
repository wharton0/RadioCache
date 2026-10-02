package com.radiocache.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entities mirror the domain models in
 * [com.radiocache.app.data.model] 1:1. Conversion lives in [Mappers]
 * so domain code never imports Room, and Room never leaks into UI.
 */

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val country: String,
    val type: String,
    /** Comma-separated provider IDs (see [Converters]). */
    val providerIds: String,
    val logoUrl: String?,
    val subscribed: Boolean,
    val liveStreamUrl: String?,
    val providerStationKey: String?,
)

@Entity(tableName = "shows")
data class ShowEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    val name: String,
    val schedule: String,
    val durationMinutes: Int,
    val strategy: String,
    val feedUrl: String?,
    val providerProgrammeKey: String?,
    val subscribed: Boolean,
    /** Last sync wall-clock ms (0 = never). */
    val lastSyncEpochMs: Long = 0L,
    /** Human-readable last sync outcome (shown under the station). */
    val lastSyncResult: String? = null,
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val showId: String,
    val stationId: String,
    val title: String,
    val description: String?,
    val broadcastDateEpochMs: Long,
    val scheduledStartEpochMs: Long?,
    val scheduledEndEpochMs: Long?,
    val recordedAtEpochMs: Long,
    val sourceType: String,
    val sourceUrl: String?,
    val resolvedAudioUrl: String?,
    val localPath: String?,
    val durationMs: Long,
    val status: String,
    val downloadProgress: Int,
    val lastPositionMs: Long,
    val playbackSpeed: Float,
)

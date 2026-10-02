package com.radiocache.app.data.local

import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.SourceType
import com.radiocache.app.data.model.Station
import com.radiocache.app.data.model.StationType

/**
 * Entity <-> domain mapping. Pure functions — unit-tested in [MappersTest].
 * Unknown enum strings fall back to safe defaults instead of crashing.
 */
object Mappers {

    fun Station.toEntity(): StationEntity = StationEntity(
        id = id,
        name = name,
        country = country,
        type = type.name,
        providerIds = providerIds.joinToString(","),
        logoUrl = logoUrl,
        subscribed = subscribed,
        liveStreamUrl = liveStreamUrl,
        providerStationKey = providerStationKey,
    )

    fun StationEntity.toDomain(): Station = Station(
        id = id,
        name = name,
        country = country,
        type = runCatching { StationType.valueOf(type) }.getOrDefault(StationType.MUSIC),
        providerIds = if (providerIds.isBlank()) emptyList() else providerIds.split(","),
        logoUrl = logoUrl,
        subscribed = subscribed,
        liveStreamUrl = liveStreamUrl,
        providerStationKey = providerStationKey,
    )

    fun Show.toEntity(): ShowEntity = ShowEntity(
        id = id,
        stationId = stationId,
        name = name,
        schedule = schedule,
        durationMinutes = durationMinutes,
        strategy = strategy.name,
        feedUrl = feedUrl,
        providerProgrammeKey = providerProgrammeKey,
        subscribed = subscribed,
        lastSyncEpochMs = lastSyncEpochMs,
        lastSyncResult = lastSyncResult,
    )

    fun ShowEntity.toDomain(): Show = Show(
        id = id,
        stationId = stationId,
        name = name,
        schedule = schedule,
        durationMinutes = durationMinutes,
        strategy = runCatching { DownloadStrategy.valueOf(strategy) }
            .getOrDefault(DownloadStrategy.CATCH_UP_PREFERRED),
        feedUrl = feedUrl,
        providerProgrammeKey = providerProgrammeKey,
        subscribed = subscribed,
        lastSyncEpochMs = lastSyncEpochMs,
        lastSyncResult = lastSyncResult,
    )

    fun Recording.toEntity(): RecordingEntity = RecordingEntity(
        id = id,
        showId = showId,
        stationId = stationId,
        title = title,
        description = description,
        broadcastDateEpochMs = broadcastDateEpochMs,
        scheduledStartEpochMs = scheduledStartEpochMs,
        scheduledEndEpochMs = scheduledEndEpochMs,
        recordedAtEpochMs = recordedAtEpochMs,
        sourceType = sourceType.name,
        sourceUrl = sourceUrl,
        resolvedAudioUrl = resolvedAudioUrl,
        localPath = localPath,
        durationMs = durationMs,
        status = status.name,
        downloadProgress = downloadProgress,
        lastPositionMs = lastPositionMs,
        playbackSpeed = playbackSpeed,
    )

    fun RecordingEntity.toDomain(): Recording = Recording(
        id = id,
        showId = showId,
        stationId = stationId,
        title = title,
        description = description,
        broadcastDateEpochMs = broadcastDateEpochMs,
        scheduledStartEpochMs = scheduledStartEpochMs,
        scheduledEndEpochMs = scheduledEndEpochMs,
        recordedAtEpochMs = recordedAtEpochMs,
        sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.CATCH_UP),
        sourceUrl = sourceUrl,
        resolvedAudioUrl = resolvedAudioUrl,
        localPath = localPath,
        durationMs = durationMs,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.PENDING),
        downloadProgress = downloadProgress,
        lastPositionMs = lastPositionMs,
        playbackSpeed = playbackSpeed,
    )
}

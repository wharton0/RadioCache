package com.radiocache.app.data.local

import androidx.room.TypeConverter
import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.SourceType
import com.radiocache.app.data.model.StationType

/**
 * Enums persist as their names (stable across reorders); string lists as
 * comma-separated values. Station/provider IDs never contain commas, so
 * this is safe and keeps the DB human-readable for debugging.
 */
class Converters {

    @TypeConverter
    fun stationTypeToString(v: StationType): String = v.name

    @TypeConverter
    fun stringToStationType(v: String): StationType =
        runCatching { StationType.valueOf(v) }.getOrDefault(StationType.MUSIC)

    @TypeConverter
    fun strategyToString(v: DownloadStrategy): String = v.name

    @TypeConverter
    fun stringToStrategy(v: String): DownloadStrategy =
        runCatching { DownloadStrategy.valueOf(v) }.getOrDefault(DownloadStrategy.CATCH_UP_PREFERRED)

    @TypeConverter
    fun sourceTypeToString(v: SourceType): String = v.name

    @TypeConverter
    fun stringToSourceType(v: String): SourceType =
        runCatching { SourceType.valueOf(v) }.getOrDefault(SourceType.CATCH_UP)

    @TypeConverter
    fun statusToString(v: DownloadStatus): String = v.name

    @TypeConverter
    fun stringToStatus(v: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(v) }.getOrDefault(DownloadStatus.PENDING)

    @TypeConverter
    fun audioFormatToString(v: AudioFormat): String = v.name

    @TypeConverter
    fun stringToAudioFormat(v: String): AudioFormat =
        runCatching { AudioFormat.valueOf(v) }.getOrDefault(AudioFormat.UNKNOWN)

    @TypeConverter
    fun listToString(v: List<String>): String = v.joinToString(",")

    @TypeConverter
    fun stringToList(v: String): List<String> =
        if (v.isBlank()) emptyList() else v.split(",")
}

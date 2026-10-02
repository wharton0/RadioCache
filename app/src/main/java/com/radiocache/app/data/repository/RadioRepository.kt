package com.radiocache.app.data.repository

import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Repository contract. The UI depends ONLY on this interface — never on
 * Room, Retrofit, or the filesystem. Stage 2 swaps the implementation
 * for a Room-backed one without touching ViewModels.
 */
interface RadioRepository {
    fun observeStations(): Flow<List<Station>>
    fun observeShows(stationId: String? = null): Flow<List<Show>>
    fun observeRecordings(): Flow<List<Recording>>

    suspend fun setStationSubscribed(stationId: String, subscribed: Boolean)
    suspend fun setShowSubscribed(showId: String, subscribed: Boolean)
    suspend fun upsertRecording(recording: Recording)
    suspend fun updatePlaybackPosition(recordingId: String, positionMs: Long)
}

/**
 * In-memory implementation used by previews and tests. Seeds the SAME
 * public-domain catalogue as the Room seed — deliberately not commercial
 * broadcasters, which this build must not redistribute.
 */
class InMemoryRadioRepository : RadioRepository {

    private val stations = MutableStateFlow(
        listOf(
            Station("archive-radio", "Archive Radio", "INTL", com.radiocache.app.data.model.StationType.MUSIC, listOf("archive"), subscribed = true),
            Station("gutenberg", "Gutenberg Audio", "INTL", com.radiocache.app.data.model.StationType.MUSIC, listOf("gutenberg"), subscribed = true),
        )
    )
    private val shows = MutableStateFlow(
        listOf(
            Show("golden-age", "archive-radio", "Golden Age of Radio", "Anthology", 45, providerProgrammeKey = "ia:USA-Golden-Age-of-Radio", subscribed = true),
            Show("old-time-radio", "archive-radio", "Old Time Radio", "Anthology", 30, providerProgrammeKey = "ia:old-time-radio-collection", subscribed = true),
            Show("weird-circle", "archive-radio", "Weird Circle", "Anthology", 30, providerProgrammeKey = "ia:Weird_Circle_otr", subscribed = true),
            Show("librivox-misc", "archive-radio", "LibriVox Recordings", "Anthology", 30, providerProgrammeKey = "ia:librivox", subscribed = true),
            Show("gutenberg-audio", "gutenberg", "Project Gutenberg Audio", "Anthology", 30, providerProgrammeKey = "gutenberg", subscribed = true),
        )
    )
    private val recordings = MutableStateFlow<List<Recording>>(emptyList())

    override fun observeStations(): Flow<List<Station>> = stations.asStateFlow()
    override fun observeShows(stationId: String?): Flow<List<Show>> =
        shows.asStateFlow().map { list ->
            if (stationId == null) list else list.filter { it.stationId == stationId }
        }
    override fun observeRecordings(): Flow<List<Recording>> = recordings.asStateFlow()

    override suspend fun setStationSubscribed(stationId: String, subscribed: Boolean) {
        stations.update { list -> list.map { if (it.id == stationId) it.copy(subscribed = subscribed) else it } }
    }

    override suspend fun setShowSubscribed(showId: String, subscribed: Boolean) {
        shows.update { list -> list.map { if (it.id == showId) it.copy(subscribed = subscribed) else it } }
    }

    override suspend fun upsertRecording(recording: Recording) {
        recordings.update { list ->
            val idx = list.indexOfFirst { it.id == recording.id }
            if (idx == -1) list + recording else list.toMutableList().also { it[idx] = recording }
        }
    }

    override suspend fun updatePlaybackPosition(recordingId: String, positionMs: Long) {
        recordings.update { list ->
            list.map { if (it.id == recordingId) it.copy(lastPositionMs = positionMs) else it }
        }
    }
}

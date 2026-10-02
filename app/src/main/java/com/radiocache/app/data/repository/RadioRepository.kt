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
 * Stage-1 in-memory implementation seeded with demo stations so the UI
 * and player are testable without a database or network.
 * NOT persisted — Stage 2 replaces with Room.
 */
class InMemoryRadioRepository : RadioRepository {

    private val stations = MutableStateFlow(
        listOf(
            Station("capital", "Capital London", "UK", com.radiocache.app.data.model.StationType.MUSIC, listOf("global"), subscribed = true, liveStreamUrl = "https://www.globalplayer.com/live/capital/london/", providerStationKey = "capital/london"),
            Station("bbc-r1", "BBC Radio 1", "UK", com.radiocache.app.data.model.StationType.MUSIC, listOf("bbc"), subscribed = true),
            Station("bbc-r2", "BBC Radio 2", "UK", com.radiocache.app.data.model.StationType.MUSIC, listOf("bbc"), subscribed = false),
            Station("absolute", "Absolute Radio", "UK", com.radiocache.app.data.model.StationType.MUSIC, listOf("absolute"), subscribed = false, liveStreamUrl = "https://www.hellorayo.co.uk/absolute-radio/"),
            Station("heart", "Heart London", "UK", com.radiocache.app.data.model.StationType.MUSIC, listOf("global"), subscribed = false, liveStreamUrl = "https://www.globalplayer.com/live/heart/london/", providerStationKey = "heart/london"),
        )
    )
    private val shows = MutableStateFlow(
        listOf(
            Show("capital-breakfast", "capital", "Capital Breakfast", "Mon–Fri 06:00–10:00", 240, providerProgrammeKey = "46w7Auo", subscribed = true),
            Show("r1-breakfast", "bbc-r1", "Radio 1 Breakfast", "Mon–Fri 07:00–10:30", 210, providerProgrammeKey = "b0080x5m", subscribed = true),
            Show("r2-music", "bbc-r2", "BBC Radio 2 music programme", "Config PID", 180, providerProgrammeKey = "b0080c9l"),
            Show("absolute-breakfast", "absolute", "Absolute Radio Breakfast", "Mon–Fri 06:00–10:00", 240),
            Show("heart-breakfast", "heart", "Heart Breakfast", "Mon–Fri 06:30–10:00", 210),
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

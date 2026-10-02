package com.radiocache.app.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.SourceType
import com.radiocache.app.data.model.Station
import com.radiocache.app.data.repository.RadioRepository
import com.radiocache.app.playback.RadioPlayerManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Player ViewModel: bridges local-file imports (Stage 1 manual test path)
 * into the repository + Media3 player. Wi-Fi sync will call the same
 * repository methods in Stage 4.
 */
class PlayerViewModel(
    private val repository: RadioRepository,
    val player: RadioPlayerManager,
) : ViewModel() {
    val nowPlaying: StateFlow<com.radiocache.app.playback.NowPlaying> = player.nowPlaying

    val stations: StateFlow<List<Station>> = repository.observeStations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shows: StateFlow<List<Show>> = repository.observeShows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recordings: StateFlow<List<Recording>> = repository.observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(recording: Recording) = player.play(recording)
    fun toggle() = player.togglePlayPause()
    fun pause() = player.pause()
    fun stop() = player.stop()
    fun refresh() = player.refreshPosition()
    fun seekBy(ms: Long) = player.seekBy(ms)
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setSpeed(speed: Float) = player.setSpeed(speed)

    /** Station switch: plays the station's newest cached programme, if any. */
    fun playStation(stationId: String) {
        val latest = recordings.value
            .filter { it.stationId == stationId && it.status == DownloadStatus.COMPLETED }
            .maxByOrNull { it.recordedAtEpochMs }
        if (latest != null) player.play(latest)
    }

    /** Stage-1 manual path: import a device audio file as a LOCAL_FILE recording. */
    fun importLocalFile(uri: Uri, displayName: String, appFilesDir: File) {
        viewModelScope.launch {
            val dest = File(appFilesDir, "imports/${UUID.randomUUID()}_$displayName")
            dest.parentFile?.mkdirs()
            // Copy happens in the Activity (needs ContentResolver); here we
            // just register — Activity calls registerImportedFile() after copy.
            repository.upsertRecording(
                Recording(
                    id = UUID.randomUUID().toString(),
                    showId = "local",
                    stationId = "local",
                    title = displayName,
                    sourceType = SourceType.LOCAL_FILE,
                    localPath = dest.absolutePath,
                )
            )
        }
    }

    suspend fun registerImportedFile(title: String, absolutePath: String, durationMs: Long = 0L): Recording {
        val rec = Recording(
            id = UUID.randomUUID().toString(),
            showId = "local",
            stationId = "local",
            title = title,
            sourceType = SourceType.LOCAL_FILE,
            localPath = absolutePath,
            durationMs = durationMs,
            recordedAtEpochMs = System.currentTimeMillis(),
        )
        repository.upsertRecording(rec)
        return rec
    }

    class Factory(
        private val repository: RadioRepository,
        private val player: RadioPlayerManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(repository, player) as T
    }
}

package com.radiocache.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.radiocache.app.ui.components.StationLogo
import coil.compose.AsyncImage
import com.radiocache.app.ui.theme.StationColors
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Universal player layout: artwork / title / seek bar / transport controls.
 * Transport: [-10s] [play/pause] [+30s] [stop] — podcast-player standard.
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onPickFile: () -> Unit) {
    val state by viewModel.nowPlaying.collectAsState()
    val stations by viewModel.stations.collectAsState()
    val shows by viewModel.shows.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val currentStationId = state.recording?.stationId
    // Same rule as Home: subscribed stations + the one currently playing.
    val subIds = (shows.filter { it.subscribed }.map { it.stationId } +
        stations.filter { it.subscribed }.map { it.id }).toSet()
    val visibleStations = stations.filter { it.id in subIds || it.id == currentStationId }

    // 1s position ticker while playing (drives slider + time labels).
    val playing = state.isPlaying
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            delay(1000)
            viewModel.refresh()
        }
    }

    val duration = state.durationMs.coerceAtLeast(0L)
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    val sliderValue = if (seeking) seekFraction
    else if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Artwork: current station logo (cached offline), music note fallback.
        val currentStation = stations.firstOrNull { it.id == currentStationId }
val artUrl = currentStation?.logoUrl
StationLogo(
            stationId = currentStation?.id ?: "",
            stationName = currentStation?.name ?: "",
            logoUrl = artUrl,
            size = 220.dp,
            circular = false,
        )

        Text(
            state.recording?.title ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
        )
        state.recording?.let { rec ->
            Text(
                "${rec.stationId} • ${rec.sourceType}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Seek bar + time labels.
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    seeking = true
                    seekFraction = it
                },
                onValueChangeFinished = {
                    if (duration > 0) viewModel.seekTo((seekFraction * duration).toLong())
                    seeking = false
                },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(state.positionMs), style = MaterialTheme.typography.bodySmall)
                Text(formatMs(duration), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Transport controls.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.seekBy(-10_000) }, enabled = state.recording != null) {
                Icon(Icons.Filled.Replay10, "Back 10 seconds", Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { viewModel.toggle() },
                enabled = state.recording != null,
                modifier = Modifier.size(72.dp).padding(4.dp),
                shape = CircleShape,
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play",
                    Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { viewModel.seekBy(30_000) }, enabled = state.recording != null) {
                Icon(Icons.Filled.Forward30, "Forward 30 seconds", Modifier.size(36.dp))
            }
            IconButton(onClick = { viewModel.stop() }, enabled = state.recording != null) {
                Icon(Icons.Filled.Stop, "Stop", Modifier.size(32.dp))
            }
        }

        // Station switch (brand colors, like speed chips).
        // Same station set as Home: subscribed + currently playing.
        if (visibleStations.isNotEmpty()) {
            Text("Stations", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                visibleStations.forEach { station ->
                    val brand = StationColors.forStation(station.id)
                    val hasCache = recordings.any {
                        it.stationId == station.id && it.status.name == "COMPLETED"
                    }
                    val selected = currentStationId == station.id
                    FilledIconButton(
                        onClick = { viewModel.playStation(station.id) },
                        enabled = hasCache,
                        shape = CircleShape,
                        modifier = Modifier.size(if (selected) 56.dp else 48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (selected) brand else brand.copy(alpha = 0.35f),
                            contentColor = StationColors.onColor(brand),
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        StationLogo(
                            stationId = station.id,
                            stationName = station.name,
                            logoUrl = station.logoUrl,
                            size = 40.dp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("Import local audio file")
        }
        state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

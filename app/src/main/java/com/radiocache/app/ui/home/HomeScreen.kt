package com.radiocache.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import com.radiocache.app.playback.NowPlaying
import com.radiocache.app.ui.components.StationLogo
import com.radiocache.app.ui.theme.StationColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RadioPlayer-style home, compact: 5 stations fit one screen.
 *
 * - Station rows are compact; tap the header to expand its episodes.
 * - The current station sinks to the bottom with brand emphasis;
 *   its button shows pause only while audio actually plays.
 * - Per-station refresh re-syncs just that station (for rotated URLs).
 * - Episode rows toggle in place; playing jumps to Player (see MainActivity).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    nowPlaying: NowPlaying,
    isSyncing: Boolean,
    onToggle: (Recording) -> Unit,
    onSync: (showId: String?) -> Unit,
) {
    val recordings by viewModel.recordings.collectAsState()
    val stations by viewModel.stations.collectAsState()
    val shows by viewModel.shows.collectAsState()
    val ready = recordings.filter { it.status.name == "COMPLETED" }
    val byStation = recordings.groupBy { it.stationId }
    val showByStation = shows.groupBy { it.stationId }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // My stations: subscribed only (recordings alone don't keep a station
    // listed — unsubscribing removes it from Home; files stay reachable
    // in Downloads and Player). Playing station always visible, sunk to
    // the bottom (RadioPlayer-style now-playing zone).
    // Sinks while playing AND paused — only a station change moves it back.
    val playingStationId = nowPlaying.recording?.stationId
    val subscribedIds = (shows.filter { it.subscribed }.map { it.stationId } +
        stations.filter { it.subscribed }.map { it.id }).toSet()
    val myStations = stations
        .filter { it.id in subscribedIds || it.id == playingStationId }
        .sortedBy { if (it.id == playingStationId) 1 else 0 }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Home", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (ready.isEmpty()) "Nothing cached yet — tap Sync."
                    else "${ready.size} programmes • ${formatTotal(ready.sumOf { it.durationMs })} offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { onSync(null) }, enabled = !isSyncing) {
                Icon(Icons.Filled.Sync, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (isSyncing) "Syncing…" else "Sync")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            myStations.forEach { station ->
                val recs = (byStation[station.id] ?: emptyList())
                    .sortedByDescending { it.recordedAtEpochMs }
                val latest = recs.filter { it.status.name == "COMPLETED" }
                    .maxByOrNull { it.recordedAtEpochMs }
                val isCurrent = station.id == playingStationId
                val playing = isCurrent && nowPlaying.isPlaying
                val isExpanded = expanded[station.id] ?: isCurrent
                val stationShows = showByStation[station.id] ?: emptyList()
                val syncLine = stationShows.firstOrNull { it.subscribed }?.let { syncText(it) }
                    ?: stationShows.firstOrNull()?.let { syncText(it) }

                item(key = "station_${station.id}") {
                    StationRow(
                        station = station,
                        cachedCount = recs.count { it.status.name == "COMPLETED" },
                        hasCache = latest != null,
                        isCurrent = isCurrent,
                        playing = playing,
                        expanded = isExpanded,
                        syncLine = syncLine,
                        isSyncing = isSyncing,
                        onPlay = {
                            // Paused current station resumes its episode;
                            // otherwise start the latest cached one.
                            val target = if (isCurrent && !playing && nowPlaying.recording != null)
                                nowPlaying.recording else latest
                            if (target != null) onToggle(target)
                            else onSync(stationShows.firstOrNull()?.id)
                        },
                        onRefresh = { onSync(stationShows.firstOrNull()?.id) },
                        onExpand = { expanded[station.id] = !isExpanded },
                    )
                }
                if (isExpanded) {
                    recs.forEach { rec ->
                        item(key = "rec_${rec.id}") {
                            val isCurrent = nowPlaying.recording?.id == rec.id
                            RecordingRow(rec, isCurrent && nowPlaying.isPlaying, isCurrent, onToggle)
                        }
                    }
                }
            }
        }
    }
}

private fun syncText(show: Show): String? {
    val result = show.lastSyncResult ?: return null
    val ago = timeAgo(show.lastSyncEpochMs)
    return if (ago == null) result else "$result • $ago"
}

private fun timeAgo(epochMs: Long): String? {
    if (epochMs <= 0) return null
    val min = (System.currentTimeMillis() - epochMs) / 60000
    if (min < 0) return null
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 60 * 24 -> "${min / 60}h ago"
        else -> "${min / (60 * 24)}d ago"
    }
}

@Composable
private fun StationRow(
    station: Station,
    cachedCount: Int,
    hasCache: Boolean,
    /** This station holds the active recording (stays true while paused). */
    isCurrent: Boolean,
    /** Audio is actually playing right now — drives the pause icon only. */
    playing: Boolean,
    expanded: Boolean,
    syncLine: String?,
    isSyncing: Boolean,
    onPlay: () -> Unit,
    onRefresh: () -> Unit,
    onExpand: () -> Unit,
) {
    val brand = StationColors.forStation(station.id)
    Card(
        Modifier.fillMaxWidth()
            .then(if (!isCurrent) Modifier.alpha(0.92f) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = brand.copy(alpha = 0.30f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StationLogo(
                    stationId = station.id,
                    stationName = station.name,
                    logoUrl = station.logoUrl,
                    size = if (isCurrent) 52.dp else 44.dp,
                )
                Column(
                    Modifier.weight(1f).clickable(onClick = onExpand),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        if (isCurrent) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "NOW PLAYING",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        if (hasCache) "$cachedCount cached" else "No cache yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!syncLine.isNullOrBlank()) {
                        Text(
                            syncLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                // Manual refresh for rotated/expired source URLs.
                IconButton(
                    onClick = onRefresh,
                    enabled = !isSyncing,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.Sync, "Re-sync ${station.name}", Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = onPlay,
                    shape = CircleShape,
                    modifier = Modifier.size(if (isCurrent) 52.dp else 44.dp),
                ) {
                    Icon(
                        when {
                            playing -> Icons.Filled.Pause
                            hasCache -> Icons.Filled.PlayArrow
                            else -> Icons.Filled.Sync
                        },
                        station.name,
                        Modifier.size(if (isCurrent) 28.dp else 24.dp),
                    )
                }
            }
            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onExpand),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        if (expanded) "Collapse" else "Expand",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    rec: Recording,
    playing: Boolean,
    isCurrent: Boolean,
    onToggle: (Recording) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(start = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = if (isCurrent) CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(rec.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    "${dateOf(rec)} • ${formatMs(rec.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rec.status.name != "COMPLETED") {
                    Text(
                        if (rec.status.name == "DOWNLOADING") "Downloading ${rec.downloadProgress}%…"
                        else rec.description ?: rec.status.name.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                    )
                }
            }
            FilledIconButton(
                onClick = { onToggle(rec) },
                enabled = rec.status.name == "COMPLETED",
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pause" else "Play",
                    Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun dateOf(rec: Recording): String {
    if (rec.broadcastDateEpochMs <= 0) return rec.sourceType.name.lowercase()
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(rec.broadcastDateEpochMs))
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "--:--"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatTotal(ms: Long): String {
    if (ms <= 0) return "0 min"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}

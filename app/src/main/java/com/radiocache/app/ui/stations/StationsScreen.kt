package com.radiocache.app.ui.stations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radiocache.app.ui.components.StationLogo
import com.radiocache.app.ui.theme.StationColors

@Composable
fun StationsScreen(viewModel: StationsViewModel) {
    val stations by viewModel.stations.collectAsState()
    val shows by viewModel.shows.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Stations", style = MaterialTheme.typography.headlineSmall)
            Text("Subscriptions drive Wi-Fi sync.", style = MaterialTheme.typography.bodyMedium)
        }
        items(stations, key = { it.id }) { station ->
            val brand = StationColors.forStation(station.id)
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = brand.copy(alpha = 0.12f),
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Brand logo (official artwork, letter fallback).
                        StationLogo(
                            stationId = station.id,
                            stationName = station.name,
                            logoUrl = station.logoUrl,
                            size = 48.dp,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(station.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${station.country} • ${station.type}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        FilterChip(
                            selected = station.subscribed,
                            onClick = { viewModel.toggleStation(station.id, station.subscribed) },
                            label = { Text(if (station.subscribed) "On" else "Off") },
                        )
                    }
                    shows.filter { it.stationId == station.id }.forEach { show ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(show.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${show.schedule} • ${show.durationMinutes} min",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Source: ${show.feedUrl?.let { "podcast feed" } ?: show.providerProgrammeKey?.let { "provider $it" } ?: "auto-discover"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                show.lastSyncResult?.let { result ->
                                    Text(
                                        "Last sync: $result",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                    )
                                }
                            }
                            FilterChip(
                                selected = show.subscribed,
                                onClick = { viewModel.toggleShow(show.id, show.subscribed) },
                                label = { Text(if (show.subscribed) "Sub" else "Add") },
                            )
                        }
                    }
                }
            }
        }
    }
}

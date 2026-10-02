package com.radiocache.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel) {
    val recordings by viewModel.recordings.collectAsState()
    val keepDays by viewModel.keepDays.collectAsState()
    val autoSync by viewModel.autoSync.collectAsState()
    val intervalDays by viewModel.syncIntervalDays.collectAsState()
    val syncHour by viewModel.syncHour.collectAsState()
    val syncMinute by viewModel.syncMinute.collectAsState()
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall)

        // Master auto-sync switch.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-sync on Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (autoSync) "Runs automatically in the background"
                    else "Off — use Sync now manually",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoSync, onCheckedChange = { viewModel.setAutoSync(it) })
        }

        // Editable interval + keep-count.
        NumberRow(
            label = "Sync every",
            suffix = "day(s)",
            value = intervalDays,
            range = 1..30,
            enabled = autoSync,
            onCommit = { viewModel.setSyncIntervalDays(it) },
        )
        // Daily clock time. Auto-commits every valid keystroke (VM chains
        // writes in order) + blur/✓/Done. `edit` is non-null ONLY while the
        // user is changing the value — otherwise the field follows the saved
        // flow directly, so a stale ViewModel default can never be written
        // back (the old commit-on-focus-attach reverted edits to 06:00).
        var hourEdit by remember { mutableStateOf<String?>(null) }
        var minuteEdit by remember { mutableStateOf<String?>(null) }
        val hourShown = hourEdit ?: syncHour.toString()
        val minuteShown = minuteEdit ?: "%02d".format(syncMinute)
        fun commitHour() {
            hourEdit?.toIntOrNull()?.let { h ->
                viewModel.setSyncTime(h.coerceIn(0, 23), syncMinute)
            }
            hourEdit = null
        }
        fun commitMinute() {
            minuteEdit?.toIntOrNull()?.let { m ->
                viewModel.setSyncTime(syncHour, m.coerceIn(0, 59))
            }
            minuteEdit = null
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sync daily at", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = hourShown,
                onValueChange = {
                    val f = it.filter(Char::isDigit).take(2)
                    hourEdit = f
                    // Auto-save while typing; VM serializes the writes.
                    f.toIntOrNull()?.let { h ->
                        viewModel.setSyncTime(h.coerceIn(0, 23), syncMinute)
                    }
                },
                modifier = Modifier.width(96.dp)
                    .onFocusChanged { if (!it.isFocused) commitHour() },
                enabled = autoSync,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitHour() }),
                trailingIcon = {
                    IconButton(onClick = ::commitHour, enabled = autoSync) {
                        Icon(Icons.Filled.Check, "Save hour")
                    }
                },
            )
            Text(":", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = minuteShown,
                onValueChange = {
                    val f = it.filter(Char::isDigit).take(2)
                    minuteEdit = f
                    // Auto-save while typing; VM serializes the writes.
                    f.toIntOrNull()?.let { m ->
                        viewModel.setSyncTime(syncHour, m.coerceIn(0, 59))
                    }
                },
                modifier = Modifier.width(96.dp)
                    .onFocusChanged { if (!it.isFocused) commitMinute() },
                enabled = autoSync,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitMinute() }),
                trailingIcon = {
                    IconButton(onClick = ::commitMinute, enabled = autoSync) {
                        Icon(Icons.Filled.Check, "Save minute")
                    }
                },
            )
        }
        // Retention rule 1: age.
        Text("Keep recordings: $keepDays days", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 7, 14, 30).forEach { days ->
                FilterChip(
                    selected = keepDays == days,
                    onClick = { viewModel.setKeepDays(days) },
                    label = { Text("${days}d") },
                )
            }
        }
        // Manual actions.
        OutlinedButton(onClick = { viewModel.runCleanupNow() }) {
            Text("Clean up old recordings now")
        }
        androidx.compose.material3.Button(onClick = { viewModel.syncNow() }) {
            Text("Sync now (Wi-Fi)")
        }
        if (recordings.isEmpty()) {
            Text("Nothing cached yet. Capital Breakfast will appear here after the first sync.")
        } else {
            recordings.forEach { rec ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rec.title, style = MaterialTheme.typography.bodyLarge)
                    Text("${rec.status} • ${rec.downloadProgress}%", style = MaterialTheme.typography.bodySmall)
                    // Every non-downloadable item carries its reason in description
                    // (e.g. BBC: catch-up unavailable → awaiting live capture).
                    if (rec.status.name != "COMPLETED" && rec.description != null) {
                        Text(
                            rec.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (rec.status.name == "DOWNLOADING") {
                        LinearProgressIndicator(progress = { rec.downloadProgress / 100f })
                    }
                }
            }
        }
    }
}

/**
 * Editable number setting: label + numeric field + suffix.
 * `edit` non-null only while the user is typing — otherwise the field
 * follows [value], so a stale initial value is never committed back.
 */
@Composable
private fun NumberRow(
    label: String,
    suffix: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var edit by remember { mutableStateOf<String?>(null) }
    val shown = edit ?: value.toString()
    fun commit() {
        edit?.toIntOrNull()?.let { n -> onCommit(n.coerceIn(range.first, range.last)) }
        edit = null
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = shown,
            onValueChange = {
                edit = it.filter(Char::isDigit).take(2)
            },
            modifier = Modifier.width(72.dp)
                .onFocusChanged { if (!it.isFocused) commit() },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        Text(suffix, style = MaterialTheme.typography.bodyMedium)
    }
}

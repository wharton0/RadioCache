package com.radiocache.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.repository.RadioRepository
import com.radiocache.app.data.repository.RoomRadioRepository
import com.radiocache.app.data.settings.SettingsRepository
import com.radiocache.app.scheduler.SyncScheduler
import com.radiocache.app.scheduler.SyncStateMonitor
import com.radiocache.app.util.AndroidLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    repository: RadioRepository,
    private val settings: SettingsRepository,
    private val room: RoomRadioRepository,
    private val scheduler: SyncScheduler,
    monitor: SyncStateMonitor,
) : ViewModel() {
    private val logger = AndroidLogger()

    val recordings: StateFlow<List<Recording>> = repository.observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Settings flows start collecting at VM creation (Eagerly): any UI that
    // reads them on first composition must see the persisted value, never
    // the placeholder default (a stale default got committed back to disk
    // and silently reverted user edits).
    val keepDays: StateFlow<Int> = settings.keepDays
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_KEEP_DAYS)

    val autoSync: StateFlow<Boolean> = settings.autoSync
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val syncIntervalDays: StateFlow<Int> = settings.syncIntervalDays
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_INTERVAL_DAYS)

    val syncHour: StateFlow<Int> = settings.syncHour
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_SYNC_HOUR)

    val syncMinute: StateFlow<Int> = settings.syncMinute
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_SYNC_MINUTE)

    fun setKeepDays(days: Int) {
        viewModelScope.launch { settings.setKeepDays(days) }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoSync(enabled)
            scheduler.reschedulePeriodic()
        }
    }

    /** Saves eagerly; reschedules only when the value actually changed. */
    fun setSyncIntervalDays(days: Int) {
        viewModelScope.launch {
            val current = settings.syncIntervalDays.first()
            if (current == days.coerceIn(1, 30)) return@launch
            settings.setSyncIntervalDays(days)
            scheduler.reschedulePeriodic()
        }
    }

    /**
     * Saves eagerly; writes are strictly ordered (each keystroke's save
     * waits for the previous one) so a fast "14" can never be overwritten
     * by an earlier "1". Logs every actual write for device verification.
     */
    fun setSyncTime(hour: Int, minute: Int) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val prev = lastTimeSave
        lastTimeSave = viewModelScope.launch {
            prev?.join()
            val prevH = syncHour.value
            val prevM = syncMinute.value
            settings.setSyncTime(h, m)
            logger.i("DownloadsVM", "sync time saved: %02d:%02d (was %02d:%02d)".format(h, m, prevH, prevM))
            if (prevH != h || prevM != m) scheduler.reschedulePeriodic()
        }
    }

    private var lastTimeSave: Job? = null

    /** Manual "clean now" — the worker calls the same method after every sync. */
    fun runCleanupNow() {
        viewModelScope.launch {
            room.cleanupOldRecordings(keepDays.value)
        }
    }

    /** Live "sync running" flag — drives button disabled state (anti-pile-up). */
    val isSyncing: StateFlow<Boolean> = monitor.syncing

    /** Manual Wi-Fi sync trigger (unconstrained — user explicitly asked). */
    fun syncNow() {
        if (!isSyncing.value) scheduler.syncNow()
    }

    class Factory(
        private val repository: RadioRepository,
        private val settings: SettingsRepository,
        private val room: RoomRadioRepository,
        private val scheduler: SyncScheduler,
        private val monitor: SyncStateMonitor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(repository, settings, room, scheduler, monitor) as T
    }
}

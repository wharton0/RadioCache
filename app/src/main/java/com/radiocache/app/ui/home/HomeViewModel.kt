package com.radiocache.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import com.radiocache.app.data.repository.RadioRepository
import com.radiocache.app.scheduler.SyncStateMonitor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Home: station list + their offline-ready programmes. */
class HomeViewModel(
    repository: RadioRepository,
    monitor: SyncStateMonitor,
) : ViewModel() {
    val recordings: StateFlow<List<Recording>> =
        repository.observeRecordings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stations: StateFlow<List<Station>> =
        repository.observeStations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shows: StateFlow<List<Show>> =
        repository.observeShows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isSyncing: StateFlow<Boolean> = monitor.syncing

    class Factory(
        private val repository: RadioRepository,
        private val monitor: SyncStateMonitor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository, monitor) as T
    }
}

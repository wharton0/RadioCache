package com.radiocache.app.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import com.radiocache.app.data.repository.RadioRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StationsViewModel(private val repository: RadioRepository) : ViewModel() {
    val stations: StateFlow<List<Station>> = repository.observeStations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shows: StateFlow<List<Show>> = repository.observeShows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleStation(id: String, subscribed: Boolean) {
        viewModelScope.launch { repository.setStationSubscribed(id, !subscribed) }
    }

    fun toggleShow(id: String, subscribed: Boolean) {
        viewModelScope.launch { repository.setShowSubscribed(id, !subscribed) }
    }

    class Factory(private val repository: RadioRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StationsViewModel(repository) as T
    }
}

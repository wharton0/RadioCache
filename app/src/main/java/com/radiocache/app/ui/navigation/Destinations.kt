package com.radiocache.app.ui.navigation

/** Bottom-nav destinations for Stage 1. */
sealed class Destination(val route: String, val label: String) {
    data object Home : Destination("home", "Home")
    data object Stations : Destination("stations", "Stations")
    data object Downloads : Destination("downloads", "Downloads")
    data object Player : Destination("player", "Player")

    companion object {
        val tabs = listOf(Home, Stations, Downloads, Player)
    }
}

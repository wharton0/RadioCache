package com.radiocache.app

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.radiocache.app.playback.RadioPlayerManager
import com.radiocache.app.ui.downloads.DownloadsScreen
import com.radiocache.app.ui.downloads.DownloadsViewModel
import com.radiocache.app.ui.home.HomeScreen
import com.radiocache.app.ui.home.HomeViewModel
import com.radiocache.app.ui.navigation.Destination
import com.radiocache.app.ui.player.PlayerScreen
import com.radiocache.app.ui.player.PlayerViewModel
import com.radiocache.app.ui.stations.StationsScreen
import com.radiocache.app.ui.stations.StationsViewModel
import com.radiocache.app.ui.theme.RadioCacheTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var playerManager: RadioPlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RadioCacheApp).container
        playerManager = container.playerManager
        playerManager.connect()
        requestNotificationPermissionIfNeeded()

        setContent {
            RadioCacheTheme {
                val nav = rememberNavController()
                var current by remember { mutableStateOf(Destination.Home.route) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                val playerVm: PlayerViewModel = viewModel(
                    factory = PlayerViewModel.Factory(container.repository, playerManager)
                )
                val pickFile = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        scope.launch {
                            val rec = copyIntoCache(uri)
                            if (rec != null) playerVm.play(rec)
                        }
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavItem(Destination.Home, current) { go(nav, it); current = it.route }
                            NavItem(Destination.Stations, current) { go(nav, it); current = it.route }
                            NavItem(Destination.Downloads, current) { go(nav, it); current = it.route }
                            NavItem(Destination.Player, current) { go(nav, it); current = it.route }
                        }
                    }
                ) { inner ->
                    NavHost(nav, Destination.Home.route, Modifier.padding(inner)) {
                        composable(Destination.Home.route) {
                            val vm: HomeViewModel = viewModel(
                                factory = HomeViewModel.Factory(container.repository, container.syncMonitor)
                            )
                            val nowPlaying by playerVm.nowPlaying.collectAsState()
                            val homeSyncing by vm.isSyncing.collectAsState()
                            HomeScreen(
                                vm, nowPlaying, homeSyncing,
                                onToggle = { rec ->
                                    val cur = playerVm.nowPlaying.value
                                    if (cur.recording?.id == rec.id && cur.isPlaying) {
                                        // Pausing: stay on Home.
                                        playerVm.pause()
                                    } else {
                                        // Playing (new or resume): jump to Player.
                                        playerVm.play(rec)
                                        go(nav, Destination.Player)
                                        current = Destination.Player.route
                                    }
                                },
                                onSync = { showId ->
                                    if (showId == null) container.syncScheduler.syncNow()
                                    else container.syncScheduler.syncShow(showId)
                                },
                            )
                        }
                        composable(Destination.Stations.route) {
                            val vm: StationsViewModel = viewModel(
                                factory = StationsViewModel.Factory(container.repository)
                            )
                            StationsScreen(vm)
                        }
                        composable(Destination.Downloads.route) {
                            val vm: DownloadsViewModel = viewModel(
                                factory = DownloadsViewModel.Factory(
                                    container.repository,
                                    container.settings,
                                    container.roomRepository,
                                    container.syncScheduler,
                                    container.syncMonitor,
                                )
                            )
                            DownloadsScreen(vm)
                        }
                        composable(Destination.Player.route) {
                            PlayerScreen(playerVm, onPickFile = { pickFile.launch(arrayOf("audio/*")) })
                        }
                    }
                }
            }
        }
    }

    private suspend fun copyIntoCache(uri: Uri): com.radiocache.app.data.model.Recording? =
        withContext(Dispatchers.IO) {
            try {
                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx != -1) c.getString(idx) else "audio.mp3"
                } ?: "audio.mp3"
                val dest = File(filesDir, "imports/$name").also { it.parentFile?.mkdirs() }
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                } ?: return@withContext null
                val container = (application as RadioCacheApp).container
                val vm = PlayerViewModel(container.repository, playerManager)
                vm.registerImportedFile(name, dest.absolutePath)
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "import failed", t)
                null
            }
        }

    override fun onDestroy() {
        playerManager.disconnect()
        super.onDestroy()
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    /** Sync progress notification needs this on Android 13+; denial is safe (worker continues backgrounded). */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private fun go(nav: NavHostController, d: Destination) {
            // popUpTo(start) so the back stack never grows past 2 levels —
            // without it, tapping the bottom bar 20 times buries Back under
            // 20 duplicate destinations.
            nav.navigate(d.route) {
                launchSingleTop = true
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            }
        }

        @Composable
        private fun androidx.compose.foundation.layout.RowScope.NavItem(
            dest: Destination,
            selected: String,
            onClick: (Destination) -> Unit,
        ) {
            val icon = when (dest) {
                Destination.Home -> Icons.Filled.Home
                Destination.Stations -> Icons.Filled.Radio
                Destination.Downloads -> Icons.Filled.Download
                Destination.Player -> Icons.Filled.PlayArrow
            }
            NavigationBarItem(
                selected = selected == dest.route,
                onClick = { onClick(dest) },
                icon = { Icon(icon, dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}

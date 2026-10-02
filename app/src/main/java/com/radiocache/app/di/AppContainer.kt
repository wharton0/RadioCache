package com.radiocache.app.di

import android.content.Context
import com.radiocache.app.data.local.RadioCacheDatabase
import com.radiocache.app.data.repository.RadioRepository
import com.radiocache.app.data.repository.RoomRadioRepository
import com.radiocache.app.data.settings.SettingsRepository
import com.radiocache.app.download.AudioDownloader
import com.radiocache.app.engine.LiveStreamProvider
import com.radiocache.app.engine.ProgrammeResolver
import com.radiocache.app.engine.capture.HttpStreamCaptureEngine
import com.radiocache.app.engine.capture.NoOpStreamCaptureEngine
import com.radiocache.app.engine.capture.StreamCaptureEngine
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.engine.providers.archive.InternetArchiveProvider
import com.radiocache.app.engine.providers.rss.RssCatchUpProvider
import com.radiocache.app.playback.RadioPlayerManager
import androidx.work.WorkManager
import com.radiocache.app.scheduler.NoOpSyncScheduler
import com.radiocache.app.scheduler.SyncScheduler
import com.radiocache.app.scheduler.SyncSchedulerImpl
import com.radiocache.app.scheduler.SyncStateMonitor
import okhttp3.OkHttpClient

/**
 * Manual service locator for Stage 1.
 *
 * Why manual DI: zero annotation processing, fully testable (tests can build
 * a container with fakes), and maps 1:1 to a future Hilt/Dagger graph.
 * Each property documents which development stage owns it.
 */
class AppContainer(private val appContext: Context) {

    // --- Stage 1: playback ---
    val playerManager: RadioPlayerManager by lazy {
        RadioPlayerManager(appContext)
    }

    // --- Stage 2: Room database (persistent; replaces in-memory) ---
    val database: RadioCacheDatabase by lazy {
        RadioCacheDatabase.get(appContext)
    }

    // --- Stage 2: repository (Room-backed; ViewModels see only the interface) ---
    val repository: RadioRepository by lazy {
        RoomRadioRepository(database, filesRoot = appContext.filesDir)
    }

    /** Direct access for retention cleanup etc. */
    val roomRepository: RoomRadioRepository
        get() = repository as RoomRadioRepository

    val settings: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    // --- Catch-up engine: public-domain archive first, generic RSS last ---
    // The F-Droid build deliberately ships no commercial-broadcaster
    // provider: shipping code that redistributes someone else's broadcast
    // audio would make the app non-free. The engine is source-agnostic, so
    // adding a licensed source later is a one-line change here.
    val archiveProvider: InternetArchiveProvider by lazy {
        InternetArchiveProvider(http)
    }
    val catchUpProviders: List<CatchUpProvider> by lazy {
        listOf(archiveProvider, RssCatchUpProvider(http))
    }

    // No live-stream provider: the live-capture engine is retained (it is
    // generic code) but has no station to point at in this build.
    val liveStreamProviders: List<LiveStreamProvider> by lazy { emptyList() }

    private val http: OkHttpClient by lazy { OkHttpClient() }

    // --- Shared HTTP downloader (sync worker + future manual downloads) ---
    val downloader: AudioDownloader by lazy {
        AudioDownloader(http)
    }

    // --- Stage 6: live stream capture (timed HLS / direct-stream recording) ---
    val captureEngine: StreamCaptureEngine by lazy {
        HttpStreamCaptureEngine()
    }

    // Kept for tests / fallback reference.
    val noopCaptureEngine: StreamCaptureEngine by lazy {
        NoOpStreamCaptureEngine()
    }

    val workManager: WorkManager by lazy {
        WorkManager.getInstance(appContext)
    }

    val syncMonitor: SyncStateMonitor by lazy {
        SyncStateMonitor(workManager)
    }

    // --- Stage 4: WorkManager Wi-Fi scheduler ---
    val syncScheduler: SyncScheduler by lazy {
        SyncSchedulerImpl(appContext, settings)
    }
}

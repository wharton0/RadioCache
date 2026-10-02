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
import com.radiocache.app.engine.providers.absolute.AbsoluteRadioProvider
import com.radiocache.app.engine.providers.bbc.BbcProvider
import com.radiocache.app.engine.providers.globalplayer.GlobalPlayerProvider
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

    // --- Catch-up engine: music-radio providers first, RSS only as fallback ---
    val globalProvider: GlobalPlayerProvider by lazy {
        GlobalPlayerProvider(http)
    }
    val bbcProvider: BbcProvider by lazy {
        BbcProvider(http)
    }
    val absoluteProvider: AbsoluteRadioProvider by lazy {
        AbsoluteRadioProvider()
    }
    val catchUpProviders: List<CatchUpProvider> by lazy {
        listOf(globalProvider, bbcProvider, absoluteProvider, RssCatchUpProvider(http))
    }
    val programmeResolvers: List<ProgrammeResolver> by lazy {
        listOf(globalProvider, bbcProvider, absoluteProvider)
    }
    val liveStreamProviders: List<LiveStreamProvider> by lazy {
        listOf(globalProvider, bbcProvider, absoluteProvider)
    }

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

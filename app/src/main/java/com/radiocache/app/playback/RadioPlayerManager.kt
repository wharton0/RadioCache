package com.radiocache.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.radiocache.app.data.model.Recording
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private val Context.playbackStore by preferencesDataStore(name = "playback_prefs")

/** UI-friendly snapshot of player state. */
data class NowPlaying(
    val recording: Recording? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * Single owner of Media3 playback for the whole app.
 *
 * - Plays LOCAL files only in Stage 1 (fully offline).
 * - Binds to [PlaybackService] via MediaController: background playback,
 *   notification controls, Bluetooth/Auto controls come from the MediaSession.
 * - Persists resume position + speed in DataStore.
 */
class RadioPlayerManager(
    private val appContext: Context,
    private val logger: AppLogger = AndroidLogger(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentRecording: Recording? = null

    companion object {
        private const val TAG = "RadioPlayer"
        private val KEY_LAST_ID = stringPreferencesKey("last_recording_id")
        private val KEY_LAST_POS = longPreferencesKey("last_position_ms")
        private val KEY_SPEED = floatPreferencesKey("playback_speed")
    }

    /** Connect to the session service. Safe to call repeatedly. */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync().also { future ->
            scope.launch {
                try {
                    controller = future.await()
                    controller?.addListener(controllerListener)
                    // Restore speed, then resume position lazily on next play().
                    val prefs = appContext.playbackStore.data.first()
                    setSpeed(prefs[KEY_SPEED] ?: 1f)
                    logger.i(TAG, "MediaController connected")
                } catch (t: Throwable) {
                    logger.e(TAG, "Controller connect failed", t)
                    _nowPlaying.value = _nowPlaying.value.copy(error = t.message)
                }
            }
        }
    }

    fun disconnect() {
        controller?.removeListener(controllerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    fun release() {
        scope.launch {
            currentRecording?.let { persistPosition(it.id, controller?.currentPosition ?: 0L) }
        }
        disconnect()
        scope.cancel()
    }

    /** Play a local recording from its stored file; resumes saved position. */
    fun play(recording: Recording) {
        val path = recording.localPath
        if (path.isNullOrBlank()) {
            _nowPlaying.value = _nowPlaying.value.copy(error = "No local file for '${recording.title}'")
            logger.w(TAG, "play() with null path: ${recording.id}")
            return
        }
        // Self-heal: the DB can say COMPLETED while the file is gone (manual
        // deletion, storage reclaim, an interrupted cleanup). Handing such a row
        // to the data source throws FileNotFoundException deep in Media3 and
        // surfaces as a player error, so verify the file before preparing.
        if (!fileExists(path)) {
            logger.w(TAG, "missing file for ${recording.id} at $path — marking FAILED")
            _nowPlaying.value = _nowPlaying.value.copy(
                error = "File missing — re-sync this show to download it again",
            )
            scope.launch { markMissing(recording) }
            return
        }
        scope.launch {
            try {
                // Persist previous track position before switching.
                currentRecording?.let { prev ->
                    persistPosition(prev.id, controller?.currentPosition ?: 0L)
                }
                currentRecording = recording
                val prefs = appContext.playbackStore.data.first()
                val resumeMs = if (prefs[KEY_LAST_ID] == recording.id) {
                    prefs[KEY_LAST_POS] ?: recording.lastPositionMs
                } else {
                    recording.lastPositionMs
                }
                val ctrl = ensureController()
                if (ctrl == null) {
                    _nowPlaying.value = NowPlaying(recording, error = "Player not ready yet")
                    return@launch
                }
                ctrl.setMediaItem(mediaItemFor(recording))
                ctrl.prepare()
                if (resumeMs > 0) ctrl.seekTo(resumeMs)
                ctrl.play()
                _nowPlaying.value = NowPlaying(
                    recording = recording,
                    isPlaying = true,
                    positionMs = resumeMs,
                    durationMs = recording.durationMs,
                    speed = ctrl.playbackParameters.speed,
                )
                logger.i(TAG, "Playing ${recording.id} from $resumeMs ms")
            } catch (t: Throwable) {
                logger.e(TAG, "play(${recording.id}) failed", t)
                _nowPlaying.value = _nowPlaying.value.copy(error = t.message)
            }
        }
    }

private fun fileExists(path: String): Boolean =
        runCatching { java.io.File(path).let { it.exists() && it.length() > 0 } }.getOrDefault(false)

    /**
     * Marks a row whose audio vanished as FAILED so the UI stops offering it
     * as playable and the next sync re-downloads it. The row is kept (not
     * deleted) because the title/metadata is still worth showing.
     */
    private suspend fun markMissing(recording: Recording) {
        runCatching {
            val db = (appContext.applicationContext as? com.radiocache.app.RadioCacheApp)
                ?.container?.database ?: return@runCatching
            db.recordingDao().updateStatus(
                recording.id,
                com.radiocache.app.data.model.DownloadStatus.FAILED.name,
                0,
            )
        }.onFailure { logger.w(TAG, "could not mark ${recording.id} as failed", it) }
    }

    /**
     * Media item with real metadata so the lock-screen / notification shows the
     * programme title and station instead of a raw file path.
     */
private fun mediaItemFor(recording: com.radiocache.app.data.model.Recording): MediaItem {
    val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(recording.title)
        .setArtist(recording.description ?: null)
        .setAlbumTitle(recording.showId.takeIf { it != "local" })
        .build()
    return MediaItem.Builder()
        .setMediaId(recording.id)
        .setUri("file://${recording.localPath}")
        .setMediaMetadata(metadata)
        .build()
}

fun pause() {
    scope.launch {
        persistCurrentPosition()
        try { controller?.pause() } catch (t: Throwable) {
            logger.e(TAG, "pause failed", t)
        }
    }
}

    /** Universal "stop": halt playback. Next play() re-prepares from the saved position. */
    fun stop() {
        scope.launch {
            persistCurrentPosition()
            try { controller?.stop() } catch (t: Throwable) {
                logger.e(TAG, "stop failed", t)
            }
            _nowPlaying.value = _nowPlaying.value.copy(isPlaying = false)
        }
    }

    /** Pulls live position/duration from the controller (1s ticker in Player UI). */
    fun refreshPosition() {
        val ctrl = controller ?: return
        _nowPlaying.value = _nowPlaying.value.copy(
            positionMs = ctrl.currentPosition.coerceAtLeast(0L),
            durationMs = if (ctrl.duration > 0) ctrl.duration else _nowPlaying.value.durationMs,
            isPlaying = ctrl.isPlaying,
        )
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) pause() else {
            currentRecording?.let { play(it) } ?: run {
                try { ctrl.play() } catch (t: Throwable) {
                    logger.e(TAG, "resume failed", t)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        try { controller?.seekTo(positionMs) } catch (t: Throwable) {
            logger.e(TAG, "seek failed", t)
        }
    }

    fun seekBy(deltaMs: Long) {
        val ctrl = controller ?: return
        ctrl.seekTo((ctrl.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 3f)
        try { controller?.setPlaybackSpeed(s) } catch (t: Throwable) {
            logger.e(TAG, "setSpeed failed", t)
        }
        scope.launch {
            appContext.playbackStore.edit { it[KEY_SPEED] = s }
        }
        _nowPlaying.value = _nowPlaying.value.copy(speed = s)
    }

    private suspend fun ensureController(): MediaController? {
        var ctrl = controller
        if (ctrl != null) return ctrl
        connect()
        // Brief wait for the async bind (controller listener updates state).
        repeat(20) {
            kotlinx.coroutines.delay(100)
            ctrl = controller
            if (ctrl != null) return ctrl
        }
        return controller
    }

    private suspend fun persistCurrentPosition() {
        val rec = currentRecording ?: return
        persistPosition(rec.id, controller?.currentPosition ?: return)
    }

    private suspend fun persistPosition(recordingId: String, positionMs: Long) {
        try {
            appContext.playbackStore.edit {
                it[KEY_LAST_ID] = recordingId
                it[KEY_LAST_POS] = positionMs
            }
        } catch (t: Throwable) {
            logger.e(TAG, "persist position failed", t)
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _nowPlaying.value = _nowPlaying.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val ctrl = controller ?: return
            _nowPlaying.value = _nowPlaying.value.copy(
                positionMs = ctrl.currentPosition.coerceAtLeast(0L),
                durationMs = if (ctrl.duration > 0) ctrl.duration else _nowPlaying.value.durationMs,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            logger.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            _nowPlaying.value = _nowPlaying.value.copy(
                isPlaying = false,
                error = error.localizedMessage ?: error.errorCodeName,
            )
        }
    }
}

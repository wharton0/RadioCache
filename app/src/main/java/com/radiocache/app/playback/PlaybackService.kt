package com.radiocache.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground MediaSession service.
 *
 * This is what gives RadioCache background playback, lock-screen /
 * notification controls, Bluetooth headset buttons, and future
 * Android Auto support — all for free via Media3's session framework.
 * Audio focus + noisy-receiver handling are built into ExoPlayer.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
            player = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
            session = MediaSession.Builder(this, player!!)
                // Tapping the notification / lock-screen entry opens the app.
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, com.radiocache.app.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
            Log.i(TAG, "PlaybackService created")
        } catch (t: Throwable) {
            Log.e(TAG, "PlaybackService init failed", t)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        player?.release()
        session = null
        player = null
        Log.i(TAG, "PlaybackService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
    }
}

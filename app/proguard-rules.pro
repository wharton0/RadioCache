# Keep MediaSession callbacks; R8 full mode can strip them otherwise.
-keep class com.radiocache.app.playback.PlaybackService { *; }
-keep class androidx.media3.session.MediaSessionService { *; }

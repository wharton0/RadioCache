package com.radiocache.app.engine.catchup

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show

/**
 * Catch-up Engine contract — the PREFERRED acquisition path.
 *
 * Each radio family implements this independently. The scheduler picks the
 * first provider whose [supports] returns true; no station logic leaks into
 * UI or scheduler.
 */
interface CatchUpProvider {
    /** Stable ID referenced by Station.providerIds. */
    val providerId: String

    fun supports(show: Show): Boolean

    /**
     * Returns latest officially available broadcast programmes.
     * Providers must resolve audio URLs DYNAMICALLY from public pages/APIs.
     * If audio needs login/DRM/signed-URL bypass, return metadata with
     * `resolvedAudioUrl = null` (catch-up unavailable) — NEVER fake a URL.
     */
    suspend fun fetchProgrammes(show: Show, limit: Int = 7): Result<List<BroadcastProgramme>>

    // Back-compat bridge for the Stage-1 RSS stub.
    suspend fun fetchEpisodes(show: Show): Result<List<CatchUpEpisode>> =
        fetchProgrammes(show).map { list ->
            list.map {
                CatchUpEpisode(
                    episodeId = it.programmeId,
                    title = it.title,
                    audioUrl = it.resolvedAudioUrl ?: "",
                    publishedAtEpochMs = it.broadcastDateEpochMs,
                    durationMs = it.durationMs,
                )
            }
        }
}

/** Legacy podcast-style episode view (kept for the RSS adapter only). */
data class CatchUpEpisode(
    val episodeId: String,
    val title: String,
    val audioUrl: String,
    val publishedAtEpochMs: Long,
    val durationMs: Long? = null,
)

/** Maps a resolved audio URL suffix to a format hint (no network I/O). */
fun guessAudioFormat(url: String?): com.radiocache.app.data.model.AudioFormat =
    when {
        url == null -> com.radiocache.app.data.model.AudioFormat.UNKNOWN
        ".m3u8" in url -> com.radiocache.app.data.model.AudioFormat.HLS
        ".mpd" in url -> com.radiocache.app.data.model.AudioFormat.DASH
        url.endsWith(".m4a") || url.endsWith(".mp4") -> com.radiocache.app.data.model.AudioFormat.M4A_AAC
        url.endsWith(".mp3") -> com.radiocache.app.data.model.AudioFormat.MP3
        else -> AudioFormat.UNKNOWN
    }

package com.radiocache.app.data.model

/**
 * Core domain models — revised for MUSIC RADIO (not generic podcasts).
 *
 * A music broadcast is NOT a podcast episode. One programme contains:
 *   Music → DJ → Music → Ad → News → Music → Interview → Music
 * so the stored unit is a [BroadcastRecording]: a time-bounded capture of
 * a scheduled programme on a station, however it was obtained.
 *
 * UI depends only on these plain models. Room @Entity mapping (Stage 2)
 * mirrors them 1:1; providers construct them; the player reads them.
 */

/** A radio station the user can subscribe to. */
data class Station(
    val id: String,
    val name: String,
    val country: String,
    val type: StationType,
    /** IDs of catch-up providers that know how to fetch this station. */
    val providerIds: List<String>,
    val logoUrl: String? = null,
    val subscribed: Boolean = false,
    /** Public live-stream URL for the capture fallback. Null = unknown. */
    val liveStreamUrl: String? = null,
    /** Machine key used by the provider, e.g. Global brand/station slugs. */
    val providerStationKey: String? = null,
)

enum class StationType {
    MUSIC, TALK, NEWS, SPORTS
}

/** How a show's audio should be obtained. */
enum class DownloadStrategy {
    /** Try catch-up first, fall back to stream capture. (Default) */
    CATCH_UP_PREFERRED,

    /** Only official catch-up/podcast/RSS — never record live. */
    CATCH_UP_ONLY,

    /** Allow live stream capture when catch-up is missing. */
    STREAM_CAPTURE_FALLBACK,
}

/** A subscribable radio programme belonging to a [Station]. */
data class Show(
    val id: String,
    val stationId: String,
    val name: String,
    /** Human schedule, e.g. "Mon–Fri 06:00–10:00". */
    val schedule: String,
    val durationMinutes: Int,
    val strategy: DownloadStrategy = DownloadStrategy.CATCH_UP_PREFERRED,
    /** Feed URL or provider-specific identifier. Null = discover via adapter. */
    val feedUrl: String? = null,
    /**
     * Provider-side programme key, e.g.:
     * - Global: catchup show id ("46w7Auo")
     * - BBC: programme brand PID ("b0080x5m")
     * Null = resolve by name matching.
     */
    val providerProgrammeKey: String? = null,
    val subscribed: Boolean = false,
    /** Last sync wall-clock ms (0 = never). Surfaced in UI, not used for logic. */
    val lastSyncEpochMs: Long = 0L,
    /** Human-readable last sync outcome, e.g. "Downloaded Tue episode". */
    val lastSyncResult: String? = null,
)

/** Where a recording's bytes came from. */
enum class SourceType {
    CATCH_UP, STREAM_CAPTURE, LOCAL_FILE
}

/** Lifecycle of a locally stored audio item. */
enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED, EXPIRED
}

/**
 * Locally stored BROADCAST RECORDING playable fully offline.
 *
 * This is the central entity of RadioCache. Every field the acceptance
 * criteria require is present:
 * station / show / broadcast date / scheduled start-end / actual duration /
 * title / description / source type / source URL / resolved URL /
 * local file / download status.
 *
 * @param broadcastDateEpochMs midnight UTC of the broadcast day.
 * @param scheduledStartEpochMs official programme start (for capture timing).
 * @param scheduledEndEpochMs official programme end.
 * @param sourceUrl the canonical page/feed URL the audio was discovered from.
 * @param resolvedAudioUrl the direct file/manifest URL at download time.
 *   Never hardcoded — always resolved dynamically by a provider.
 */
data class Recording(
    val id: String,
    val showId: String,
    val stationId: String,
    val title: String,
    val description: String? = null,
    val broadcastDateEpochMs: Long = 0L,
    val scheduledStartEpochMs: Long? = null,
    val scheduledEndEpochMs: Long? = null,
    val recordedAtEpochMs: Long = 0L,
    val sourceType: SourceType = SourceType.CATCH_UP,
    val sourceUrl: String? = null,
    val resolvedAudioUrl: String? = null,
    val localPath: String?,
    val durationMs: Long = 0L,
    val status: DownloadStatus = DownloadStatus.COMPLETED,
    val downloadProgress: Int = 100,
    /** Resume point for "continue listening". Persisted by the player. */
    val lastPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)

/** One officially available broadcast programme instance. */
data class BroadcastProgramme(
    val programmeId: String,
    val showId: String,
    val stationId: String,
    val title: String,
    val description: String? = null,
    val broadcastDateEpochMs: Long,
    val scheduledStartEpochMs: Long,
    val scheduledEndEpochMs: Long,
    val durationMs: Long? = null,
    /** Canonical discover URL (show page / episode page). */
    val sourceUrl: String? = null,
    /**
     * Direct audio URL if the provider could resolve one without auth/DRM
     * bypass. Null means "metadata only — catch-up unavailable".
     */
    val resolvedAudioUrl: String? = null,
    val audioFormat: AudioFormat = AudioFormat.UNKNOWN,
    val catchUpAvailable: Boolean = resolvedAudioUrl != null,
)

enum class AudioFormat {
    M4A_AAC, MP3, HLS, DASH, UNKNOWN
}
